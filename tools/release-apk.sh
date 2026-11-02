#!/usr/bin/env bash
# Cuts the next DobarBoiler version from origin/master and publishes the APK for the phone at
#   http://novica-dev/boiler-apk/      (Tailscale only; the app checks latest.json there and offers the update)
#
#   bash tools/release-apk.sh --preview          # next version + changelog, publishes nothing (safe)
#   bash tools/release-apk.sh                    # release: tag vX.Y.Z, build, publish
#   bash tools/release-apk.sh --ref feat/foo     # test build of a branch (no tag, marked "probna")
#   bash tools/release-apk.sh --skip-tests
#
# RELEASING IS NOVICA'S CALL. Agents never run this without --preview unless Novica explicitly asked for a
# release in the current conversation; a merged PR is not such a request (CLAUDE.md "Releases").
#
# Versions (docs/release.md): every PR carries a `Release: major|minor|patch|none` block with 1-3 Serbian
# changelog bullets; a release takes the biggest step among the PRs merged since the previous vX.Y.Z tag.
# The build runs in its own detached worktree (BUILD_WT), never in a checkout someone is working in.
# The APK must be signed with the stable key (cert digest pinned below), otherwise the phone would
# refuse it as an update and the only way out would be an uninstall that wipes the app's data.
set -euo pipefail

BUILD_WT="/data/dev/DobarBoiler.worktrees/release"
OUT_DIR="/data/staging/dobarboiler-apk"
PREVIEW_ID="boiler-apk"
SDK_DIR="/data/android-sdk"
BUILD_TOOLS="$SDK_DIR/build-tools/35.0.0"
KEEP=5
# SHA-256 of the signing certificate on the phone (key /data/keys/dobarboiler/signing.keystore).
CERT_SHA256="a0759757e0b8176638726173572abb3f6ab3de9cc0755b32277951883c73f6a5"
# Before the first vX.Y.Z tag: the last APK published under the old date-based names (what the phone had).
BOOTSTRAP_COMMIT="07d09f4a228e999a058c60324f20ec9c9494b657"   # merge of PR #8 (tree of 269caa9)

die() { echo "greska: $*" >&2; exit 1; }

REF="origin/master"
RUN_TESTS=1
FORCE=0
PREVIEW=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --ref) REF="${2:?--ref traži vrednost}"; shift 2 ;;
        --skip-tests) RUN_TESTS=0; shift ;;
        --force) FORCE=1; shift ;;   # publish even if versionCode is not newer than the last one
        --preview) PREVIEW=1; shift ;;
        -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
        *) die "nepoznat argument: $1" ;;
    esac
done

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(dirname "$(git -C "$HERE" rev-parse --path-format=absolute --git-common-dir)")"
LIB="$HERE/release_lib.py"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> git fetch"
git -C "$REPO" fetch -q --tags origin
case "$REF" in
    origin/*) ;;
    *) git -C "$REPO" rev-parse -q --verify "origin/$REF^{commit}" >/dev/null && REF="origin/$REF" ;;
esac
SHA="$(git -C "$REPO" rev-parse --verify "$REF^{commit}")" || die "ne postoji ref $REF"
MASTER_SHA="$(git -C "$REPO" rev-parse origin/master)"
IS_MASTER=0
[[ "$SHA" == "$MASTER_SHA" ]] && IS_MASTER=1

# ---------------------------------------------------------------- plan: next version + changelog
BASE_TAG="$(git -C "$REPO" describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' "$SHA" 2>/dev/null || true)"
if [[ -n "$BASE_TAG" ]]; then
    BASE_VERSION="${BASE_TAG#v}"
    BASE_COMMIT="$(git -C "$REPO" rev-parse "$BASE_TAG^{commit}")"
else
    BASE_VERSION="0.0.0"
    BASE_COMMIT="$BOOTSTRAP_COMMIT"
fi
git -C "$REPO" rev-list "$BASE_COMMIT..$SHA" >"$TMP/commits"
gh pr list -R Novke/DobarBoiler --state merged --base master -L 300 \
    --json number,title,body,mergedAt,mergeCommit,headRefOid >"$TMP/prs.json"
python3 "$LIB" plan --base-version "$BASE_VERSION" --prs "$TMP/prs.json" --commits "$TMP/commits" >"$TMP/plan.json"

NEXT="$(jq -r '.version // empty' "$TMP/plan.json")"
echo
echo "Poslednja verzija: ${BASE_TAG:-nema (prva verzija posle $(git -C "$REPO" rev-parse --short "$BOOTSTRAP_COMMIT"))}"
jq -r '.warnings[] | "upozorenje: " + .' "$TMP/plan.json"
jq -r '.prs[] | "  PR #\(.number) [\(.bump)] \(.title)"' "$TMP/plan.json"
if [[ -n "$NEXT" ]]; then
    echo "Sledeća verzija: $NEXT ($(jq -r .bump "$TMP/plan.json"))"
    jq -r '.changelog[] | "  - \(.text) (#\(.pr))"' "$TMP/plan.json"
else
    echo "Od $BASE_VERSION nije mergovano ništa što ide u aplikaciju."
fi
echo
[[ $PREVIEW == 1 ]] && { echo "(pregled — ništa nije objavljeno)"; exit 0; }

if [[ $IS_MASTER == 1 ]]; then
    [[ -n "$NEXT" ]] || { echo "Nema šta da se izda."; exit 0; }
    VERSION="$NEXT"
    TEST=false
    git -C "$REPO" rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && die "tag v$VERSION već postoji"
else
    # A test build of a branch: the phone offers it as an update too, but it gets no tag.
    slug="$(git -C "$REPO" rev-parse --abbrev-ref "$REF" 2>/dev/null | sed 's#^origin/##; s#[^A-Za-z0-9]#-#g')"
    VERSION="${NEXT:-$BASE_VERSION}-proba.${slug:-commit}.$(git -C "$REPO" rev-parse --short "$SHA")"
    TEST=true
fi

# ---------------------------------------------------------------- build
GRADLE_PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
grep -q '^dobarboiler.signing.storeFile=' "$GRADLE_PROPS" 2>/dev/null \
    || die "nema potpisnog ključa u $GRADLE_PROPS (vidi docs/release.md)"

mkdir -p "$OUT_DIR"
exec 9>"$OUT_DIR/.lock"
flock -n 9 || die "drugo izdavanje je već u toku"

if [[ ! -e "$BUILD_WT/.git" ]]; then
    echo "==> pravim build worktree $BUILD_WT"
    git -C "$REPO" worktree add -q --detach "$BUILD_WT" "$SHA"
fi
git -C "$BUILD_WT" checkout -q --force --detach "$SHA"
[[ -f "$BUILD_WT/local.properties" ]] || echo "sdk.dir=$SDK_DIR" > "$BUILD_WT/local.properties"
echo "==> gradim $VERSION iz $REF ($(git -C "$BUILD_WT" log -1 --format='%h %s'))"

TASKS=(:app:assembleDebug)
[[ $RUN_TESTS == 1 ]] && TASKS=(:app:testDebugUnitTest "${TASKS[@]}")
# History-server token baked into the APK (BuildConfig.HISTORY_TOKEN), so the phone uploads its log without setup.
SERVER_ENV="/data/secrets/dobarboiler-server.env"
[[ -f "$SERVER_ENV" ]] || ssm-env dobarboiler-server >/dev/null
HISTORY_TOKEN="$(sed -n 's/^BOILER_API_TOKEN=//p' "$SERVER_ENV")"
[[ -n "$HISTORY_TOKEN" ]] || die "nema BOILER_API_TOKEN u $SERVER_ENV (SSM /dev/dobarboiler-server/env)"
(cd "$BUILD_WT" && ORG_GRADLE_PROJECT_dobarboilerHistoryToken="$HISTORY_TOKEN" \
    ORG_GRADLE_PROJECT_dobarboilerVersionName="$VERSION" ./gradlew --no-daemon -q "${TASKS[@]}")

APK="$BUILD_WT/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || die "nema APK-a: $APK"

cert="$("$BUILD_TOOLS/apksigner" verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[[ "$cert" == "$CERT_SHA256" ]] \
    || die "APK je potpisan pogrešnim ključem ($cert); telefon ga ne bi primio kao update"

badging="$("$BUILD_TOOLS/aapt2" dump badging "$APK" | sed -n 1p)"
VCODE="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging")"
VNAME="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
[[ -n "$VCODE" && "$VNAME" == "$VERSION" ]] || die "APK ima verziju '$VNAME' ($VCODE), očekivano $VERSION"

if [[ -f "$OUT_DIR/latest.json" && $FORCE == 0 ]]; then
    last="$(jq -r .versionCode "$OUT_DIR/latest.json")"
    (( VCODE > last )) || die "versionCode $VCODE nije veći od objavljenog $last; telefon bi odbio downgrade (--force da ipak objaviš)"
fi

# ---------------------------------------------------------------- tag (master only), then publish
NOTES="$(jq -r '.changelog[] | "- \(.text) (#\(.pr))"' "$TMP/plan.json")"
if [[ $TEST == false ]]; then
    echo "==> tag v$VERSION"
    git -C "$REPO" tag -a "v$VERSION" "$SHA" -m "DobarBoiler $VERSION" -m "$NOTES"
    git -C "$REPO" push -q origin "refs/tags/v$VERSION" || { git -C "$REPO" tag -d "v$VERSION" >/dev/null; die "push taga nije uspeo"; }
    gh release create "v$VERSION" -R Novke/DobarBoiler --verify-tag --title "DobarBoiler $VERSION" \
        --notes "$NOTES" >/dev/null || echo "upozorenje: GitHub release nije napravljen (tag postoji)" >&2
fi

NAME="dobarboiler-$VERSION.apk"
cp "$APK" "$OUT_DIR/$NAME.tmp" && mv "$OUT_DIR/$NAME.tmp" "$OUT_DIR/$NAME"
SUM="$(sha256sum "$OUT_DIR/$NAME" | cut -d' ' -f1)"
SIZE="$(stat -c %s "$OUT_DIR/$NAME")"
jq -n --arg v "$VERSION" --argjson c "$VCODE" --arg f "$NAME" --arg s "$SUM" --argjson z "$SIZE" \
    --arg r "$REF" --arg h "$SHA" --arg t "$(date -Iseconds)" --arg d "$(date '+%d.%m.%Y.')" \
    --argjson test "$TEST" --slurpfile plan "$TMP/plan.json" \
    '{version:$v, versionName:$v, versionCode:$c, file:$f, sha256:$s, size:$z, ref:$r, commit:$h,
      built:$t, date:$d, test:$test, changelog:$plan[0].changelog}' >"$TMP/entry.json"

# Keep the newest KEEP APKs (older ones are only useful for a rollback, which needs --force anyway).
ls -1t "$OUT_DIR"/dobarboiler-*.apk | tail -n +$((KEEP + 1)) | xargs -r rm -f
python3 "$LIB" publish --out-dir "$OUT_DIR" --entry "$TMP/entry.json"

if [[ ! -L "/data/previews/$PREVIEW_ID" ]]; then
    preview add "$PREVIEW_ID" "$OUT_DIR" >/dev/null
fi

echo
echo "Objavljeno: $VERSION (versionCode $VCODE)"
echo "Telefon: aplikacija nudi ažuriranje (ili http://novica-dev/$PREVIEW_ID/)"

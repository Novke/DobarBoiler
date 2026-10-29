#!/usr/bin/env bash
# Builds the app from origin/master (or --ref) and publishes the APK for the phone at
#   http://novica-dev/boiler-apk/      (Tailscale only)
# On the phone: open the link, tap "Instaliraj", Android installs it over the old version (data stays).
#
#   bash tools/release-apk.sh                    # origin/master, with unit tests
#   bash tools/release-apk.sh --ref feat/foo     # a branch or commit, e.g. to try a PR on the phone
#   bash tools/release-apk.sh --skip-tests
#
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

die() { echo "greska: $*" >&2; exit 1; }

REF="origin/master"
RUN_TESTS=1
FORCE=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --ref) REF="${2:?--ref traži vrednost}"; shift 2 ;;
        --skip-tests) RUN_TESTS=0; shift ;;
        --force) FORCE=1; shift ;;   # publish even if versionCode is not newer than the last one
        -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
        *) die "nepoznat argument: $1" ;;
    esac
done

REPO="$(dirname "$(git -C "$(dirname "$0")" rev-parse --path-format=absolute --git-common-dir)")"
GRADLE_PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
grep -q '^dobarboiler.signing.storeFile=' "$GRADLE_PROPS" 2>/dev/null \
    || die "nema potpisnog ključa u $GRADLE_PROPS (vidi docs/release.md)"

mkdir -p "$OUT_DIR"
exec 9>"$OUT_DIR/.lock"
flock -n 9 || die "drugo izdavanje je već u toku"

echo "==> git fetch"
git -C "$REPO" fetch -q origin
case "$REF" in
    origin/*) ;;
    *) git -C "$REPO" rev-parse -q --verify "origin/$REF^{commit}" >/dev/null && REF="origin/$REF" ;;
esac
SHA="$(git -C "$REPO" rev-parse --verify "$REF^{commit}")" || die "ne postoji ref $REF"

if [[ ! -e "$BUILD_WT/.git" ]]; then
    echo "==> pravim build worktree $BUILD_WT"
    git -C "$REPO" worktree add -q --detach "$BUILD_WT" "$SHA"
fi
git -C "$BUILD_WT" checkout -q --force --detach "$SHA"
[[ -f "$BUILD_WT/local.properties" ]] || echo "sdk.dir=$SDK_DIR" > "$BUILD_WT/local.properties"
echo "==> gradim $REF ($(git -C "$BUILD_WT" log -1 --format='%h %s'))"

TASKS=(:app:assembleDebug)
[[ $RUN_TESTS == 1 ]] && TASKS=(:app:testDebugUnitTest "${TASKS[@]}")
(cd "$BUILD_WT" && ./gradlew --no-daemon -q "${TASKS[@]}")

APK="$BUILD_WT/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || die "nema APK-a: $APK"

cert="$("$BUILD_TOOLS/apksigner" verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[[ "$cert" == "$CERT_SHA256" ]] \
    || die "APK je potpisan pogrešnim ključem ($cert); telefon ga ne bi primio kao update"

badging="$("$BUILD_TOOLS/aapt2" dump badging "$APK" | sed -n 1p)"
VCODE="$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging")"
VNAME="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging")"
[[ -n "$VCODE" && -n "$VNAME" ]] || die "ne mogu da pročitam verziju iz APK-a"

if [[ -f "$OUT_DIR/latest.json" && $FORCE == 0 ]]; then
    last="$(jq -r .versionCode "$OUT_DIR/latest.json")"
    (( VCODE > last )) || die "versionCode $VCODE nije veći od objavljenog $last; telefon bi odbio downgrade (--force da ipak objaviš)"
fi

NAME="dobarboiler-$VNAME.apk"
cp "$APK" "$OUT_DIR/$NAME.tmp" && mv "$OUT_DIR/$NAME.tmp" "$OUT_DIR/$NAME"
SUM="$(sha256sum "$OUT_DIR/$NAME" | cut -d' ' -f1)"
jq -n --arg n "$VNAME" --argjson c "$VCODE" --arg f "$NAME" --arg s "$SUM" --arg r "$REF" --arg h "$SHA" \
    --arg t "$(date -Iseconds)" \
    '{versionName:$n, versionCode:$c, file:$f, sha256:$s, ref:$r, commit:$h, built:$t}' >"$OUT_DIR/latest.json"

# Keep the newest KEEP APKs (older ones are only useful for a rollback, which needs --force anyway).
ls -1t "$OUT_DIR"/dobarboiler-*.apk | tail -n +$((KEEP + 1)) | xargs -r rm -f

{
    cat <<HTML
<!doctype html>
<html lang="sr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>DobarBoiler APK</title>
<style>
body{font-family:system-ui,sans-serif;max-width:32rem;margin:2rem auto;padding:0 1rem;color:#1b1b1f}
a.btn{display:block;text-align:center;background:#1e5aa8;color:#fff;padding:1rem;border-radius:.75rem;
text-decoration:none;font-size:1.2rem;margin:1rem 0}
li{margin:.4rem 0} small{color:#666}
</style></head><body>
<h1>DobarBoiler</h1>
<a class="btn" href="$NAME">Instaliraj $VNAME</a>
<p><small>$REF · $(date '+%d.%m.%Y. %H:%M') · sha256 ${SUM:0:16}…</small></p>
<h2>Ranije verzije</h2><ul>
HTML
    for f in $(ls -1t "$OUT_DIR"/dobarboiler-*.apk | tail -n +2); do
        b="$(basename "$f")"
        echo "<li><a href=\"$b\">$b</a></li>"
    done
    echo '</ul><p><small>Starija verzija se instalira samo posle deinstalacije, a to briše podatke aplikacije.</small></p></body></html>'
} >"$OUT_DIR/index.html"

if [[ ! -L "/data/previews/$PREVIEW_ID" ]]; then
    preview add "$PREVIEW_ID" "$OUT_DIR" >/dev/null
fi

echo
echo "Objavljeno: $VNAME (versionCode $VCODE)"
echo "Na telefonu: http://novica-dev/$PREVIEW_ID/"

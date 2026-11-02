# Releasing the app to the phone

The app is sideloaded. A release is a debug-variant APK built on novica-dev and served over Tailscale at
**http://novica-dev/boiler-apk/** (`latest.json`, `versions.json`, `index.html`, the last 5 APKs).

**Releasing is Novica's decision.** Agents never run `tools/release-apk.sh` (or GitGud's *Izdaj APK*)
without `--preview` unless Novica explicitly asked for a release in the current conversation. A merged PR,
a finished feature or "everything is green" is not such a request. `--preview` is always fine.

```bash
bash tools/release-apk.sh --preview        # next version + changelog, publishes nothing
bash tools/release-apk.sh                  # cut the next version from origin/master and publish it
bash tools/release-apk.sh --ref feat/foo   # test build of a branch ("probna", no tag)
```

GitGud's Run tab has both: **Izdaj APK: pregled** and **Izdaj APK**.

## Versions (borrowed from GitGud)

Every PR description carries a release block, chosen by the agent that opens the PR (never asked of
Novica):

```
Release: minor
Changelog:
- Ažuriranje aplikacije direktno iz aplikacije
```

| Bump | When |
|---|---|
| `major` | Never on an agent's own; only when Novica says so. |
| `minor` | Something Novica would notice and name: a new feature, screen, setting, flow, or visible behaviour change. |
| `patch` | Bug fixes (`Ispravljeno: …`), polish, small UX tweaks, refactors, build/release-script changes that affect the APK. |
| `none` | Nothing in the APK changes: docs, specs, `CLAUDE.md`, `server/`, tools that do not build the app. |

1–3 bullets, Serbian, user-facing (they appear in the app under *Podešavanja → Verzije* and as the update
banner). Several changes in one PR: the biggest decides. A PR without a block counts as `patch` with its
title as the changelog, and the preview warns about it.

A release (`tools/release-apk.sh`, rules in `tools/release_lib.py`, tests `tools/test_release_lib.py`):

1. takes the newest `vX.Y.Z` tag reachable from `origin/master` (before the first tag: the merge of PR #8,
   the last date-named APK) and the merged PRs whose merge or head commit lies after it;
2. bumps by the biggest step, prints version + changelog (`--preview` stops here), stops when nothing ships;
3. builds with `versionName = X.Y.Z` (`ORG_GRADLE_PROJECT_dobarboilerVersionName`), tests first, checks the
   signing certificate and that `versionCode` grows;
4. tags `vX.Y.Z` (annotated, changelog in the message), pushes the tag, creates the GitHub release;
5. publishes `dobarboiler-X.Y.Z.apk`, `latest.json` (what the app checks), `versions.json` (history, newest
   first) and `index.html`.

Tags are the only source of truth; nothing is committed back. `versionCode` stays the HEAD commit time in
minutes since 2026-01-01 UTC, so it grows along master. Local builds keep `yyyy.MM.dd-<hash>` as name.

## Updating the phone

Once: open http://novica-dev/boiler-apk/ and tap *Instaliraj* (the first version with the updater).

After that the app does it (`data/AppUpdater`, `api/ReleaseClient`):

- it checks `latest.json` when it opens (at most every 15 min) and from the 6 h watchdog, which posts one
  "Nova verzija X" notification per version;
- the dashboard shows a card *Nova verzija X.Y.Z* with the changelog and **Ažuriraj**; *Podešavanja →
  Verzije* shows the installed version, *Proveri* and the whole history;
- **Ažuriraj** downloads the APK into the app cache, verifies its sha256 and opens Android's installer.
  The first time Android asks to allow DobarBoiler to install apps (one switch in system settings, then
  tap *Ažuriraj* again). Android always asks for one confirmation tap; a sideloaded app cannot update
  itself silently. Same key + higher `versionCode` → installs over the app and keeps its data.
- the tap is logged as `USER_ACTION` and the new version as `APP_UPDATED` on its first start.

What the script also does:

- builds in its own detached worktree `/data/dev/DobarBoiler.worktrees/release` (never in a checkout
  someone works in);
- refuses the APK unless its signing certificate matches the pinned digest (see below);
- refuses a `versionCode` that is not newer than the last published one, because Android rejects
  downgrades (`--force` overrides; a real rollback needs an uninstall);
- bakes the history-server token into the APK (`BuildConfig.HISTORY_TOKEN`, from `BOILER_API_TOKEN` in
  `/data/secrets/dobarboiler-server.env`, rendered from SSM `/dev/dobarboiler-server/env` when missing, passed as
  `ORG_GRADLE_PROJECT_dobarboilerHistoryToken`). The phone then uploads its action log to
  `http://novica-dev/boiler/` with no setup. The token never enters git, but anyone with the APK file has it, and
  the APK is served on the tailnet only. A token typed in Settings → Server istorije overrides it.

## Signing key

Every APK on the phone was signed with the debug key that novica-dev generated on 2026-10-19
(certificate SHA-256 `a0759757…3c73f6a5`). An APK signed with any other key cannot be installed as an
update: the only way out is an uninstall, which deletes the Room database and settings. Therefore:

- the key lives at `/data/keys/dobarboiler/signing.keystore` (0600, on the `/data` volume);
- `~/.gradle/gradle.properties` (= `/data/gradle-home/gradle.properties`) points the `stable` signing
  config at it through `dobarboiler.signing.{storeFile,storePassword,keyAlias,keyPassword}`; both build
  types use it. Without those properties (another machine) builds fall back to the local debug key and
  `release-apk.sh` refuses to publish them;
- backup: SSM `/dev/dobarboiler-signing/env` (`KEYSTORE_B64`, `STORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD`). The passwords are the Android debug defaults; the key file itself is the secret.

Restore on a fresh box:

```bash
ssm-env dobarboiler-signing
install -d -m 700 /data/keys/dobarboiler
( umask 077; grep '^KEYSTORE_B64=' /data/secrets/dobarboiler-signing.env | cut -d= -f2- | base64 -d \
    > /data/keys/dobarboiler/signing.keystore )
# then write the four dobarboiler.signing.* lines into ~/.gradle/gradle.properties
```

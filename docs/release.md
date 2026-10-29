# Releasing the app to the phone

The app is sideloaded. A release is a debug-variant APK built on novica-dev and served over Tailscale:

```bash
bash tools/release-apk.sh                  # origin/master, runs the unit tests first
bash tools/release-apk.sh --ref feat/foo   # try a branch on the phone
```

Then on the phone open **http://novica-dev/boiler-apk/** and tap *Instaliraj*. Android installs it over
the old version and keeps the app's data. GitGud has the same thing as the run button **Izdaj APK**.
Settings → *Verzija aplikacije* shows what is installed.

What the script does:

- builds in its own detached worktree `/data/dev/DobarBoiler.worktrees/release` (never in a checkout
  someone works in), `:app:testDebugUnitTest :app:assembleDebug`;
- refuses the APK unless its signing certificate matches the pinned digest (see below);
- refuses a `versionCode` that is not newer than the last published one, because Android rejects
  downgrades (`--force` overrides; a real rollback needs an uninstall);
- publishes to `/data/staging/dobarboiler-apk/` (`index.html`, `latest.json`, last 5 APKs), served by
  the static preview `boiler-apk`.

## Versions

`app/build.gradle.kts` derives them from git: `versionCode` = minutes between 2026-01-01 UTC and the HEAD
commit time, `versionName` = `yyyy.MM.dd-<hash>` (`-dirty` with uncommitted changes). Commit time grows
along master, so each release installs over the previous one. A branch whose HEAD is older than the last
published commit is rejected by the script.

Why the debug variant: it is what has always run on the phone. The `release` build type has R8 minify and
resource shrinking that were never tested on the device. Switching later means `assembleRelease` plus a
check of the keep rules (Room, kotlinx.serialization).

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

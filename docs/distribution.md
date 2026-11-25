# Distribution and listings

Where ShutChat is published, what each store needs from us, and the exact
entries to submit. Keep this file current: it is the only place that records
why a listing is missing.

## Today

| Channel | Status |
|---|---|
| GitHub releases (APK, .deb, AppImage) | live |
| F-Droid | blocked, see below |
| IzzyOnDroid | ready to submit, one size problem |
| Awesome lists | entries drafted, most lists not yet eligible |

## F-Droid

F-Droid builds every APK from source on its own machines and refuses
proprietary blobs. Three dependencies block us:

| Dependency | Used in | Problem |
|---|---|---|
| `com.google.android.gms:play-services-nearby` | `p2p/nearby/NearbyTransport.kt` | Google Play Services, not free |
| `com.google.mlkit:barcode-scanning` | `ui/screens/scan/ScanScreen.kt` | proprietary library and model |
| `org.webrtc:google-webrtc` | `p2p/call/WebRtcCallMedia.kt` | prebuilt `.so`, cannot be built by F-Droid |

The path to inclusion is a `floss` product flavor that:

1. drops `NearbyTransport` (LAN and relay still work, so nothing essential is
   lost),
2. replaces ML Kit with ZXing, which is already a dependency and is Apache-2.0,
3. either drops calls over the internet or builds WebRTC from source. This is
   the hard one: Jitsi and similar projects maintain their own WebRTC build
   recipe for F-Droid, and it is a project of its own.

Only after a flavor like that builds cleanly does an F-Droid submission make
sense. Until then an RFP would be closed as unbuildable.

Draft metadata for `fdroiddata/metadata/dev.shutchat.app.yml`, to be filled in
once the flavor exists:

```yaml
Categories:
  - Internet
  - Security
License: Apache-2.0
AuthorName: ShutChat
WebSite: https://github.com/ciao22king/ShutChat
SourceCode: https://github.com/ciao22king/ShutChat
IssueTracker: https://github.com/ciao22king/ShutChat/issues
Changelog: https://github.com/ciao22king/ShutChat/releases

AutoName: ShutChat
Summary: Messenger with no server and no account
Description: |-
    Two devices find each other and talk directly. Chats start from a short
    code that the two people exchange themselves, and everything is encrypted
    end to end.

RepoType: git
Repo: https://github.com/ciao22king/ShutChat.git

Builds:
  - versionName: 1.4.0
    versionCode: 6
    commit: v1.4.0
    subdir: app
    gradle:
      - floss

AllowedAPKSigningKeys: 9b688bfb5e8688e2d8c833ecca9dfd58ee6bb97ae05954841d5a5fa330e24d21

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.4.0
CurrentVersionCode: 6
```

The signing key line is the certificate SHA-256 without colons. It lets F-Droid
accept our own builds later without a reinstall.

## IzzyOnDroid

IzzyOnDroid ships the APK the developer signed, so it needs no source build.
The repository checks the following, in their words:

- free license and public code: met, Apache-2.0;
- no trackers: met, there is no analytics, ads or crash reporting;
- no proprietary components unless essential to the core feature: we have
  three, and they are needed for the nearby transport, QR scanning and calls.
  This is the judgement call in the review;
- APK under 30 MB: **not met**, the 1.3.5 APK is 30.2 MB;
- Fastlane metadata present and tidy: met, see `fastlane/metadata/android/`;
- releases tagged and tags matching versionName: met, `v1.4.0`.

### Shrinking the APK

`org.webrtc:google-webrtc` carries native code for four ABIs. Excluding the two
emulator ABIs and letting Gradle split per ABI gives roughly 12-15 MB per
architecture instead of 30:

```kotlin
android {
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }
}
```

This changes the release artifacts from one APK to two, so it needs a release
run and a note in the release body before submitting.

Submit through the issue tracker of the IzzyOnDroid repository on GitLab, one
issue per app, with the link to the latest release and the Fastlane tree. Expect
a test pass and possibly a request to drop one of the proprietary components.

## Awesome lists

Most curated lists check how established a project is, and a repository a month
old with a handful of stars fails that bar. Submitting early does not just get
closed, it marks the account as a source of low-effort submissions. The entries
below are ready; submit them when the project has a track record, or when the
list's rules allow a new project.

### Lists that accept a new project, ready to send

These three have no maturity requirement: they ask for source code and a free
license, nothing else. `scripts/submit_awesome.py` opens the pull requests.

| Repository | Where the entry goes | Why it fits |
|---|---|---|
| `androiddevnotes/awesome-android-kotlin-apps` | `input_data.json`, Jetpack Compose group | Compose app, no age requirement |
| `ivon852/awesome-foss-android-apps` | `README.md`, 通訊軟體 table | FOSS Android apps, license only |
| `pronzzz/awesome-android-foss` | `data/apps.yml` | bot-generated README, data file is the source |

```bash
python3 scripts/submit_awesome.py --dry-run      # the exact diffs, no side effects
GITHUB_TOKEN=<pat with public_repo> python3 scripts/submit_awesome.py
```

The token needs the `public_repo` scope: a pull request to someone else's
repository has to come from a fork, and the token OpenHands injects at session
start is scoped to the user's own repositories, so `POST /forks` answers 403.

Lists left out on purpose: `mafintosh/awesome-p2p` and `retrohacker/awesome-p2p`
collect papers and libraries for people building p2p software, not apps;
`offa/android-foss` expects a store link in every entry.

### pluja/awesome-privacy (20k stars)

File: `data/sections/instant-messaging.yaml`, subsection `P2P`. Their rules
require six months of steady work and a real user base, so wait.

```yaml
- name: ShutChat
  url: https://github.com/ciao22king/ShutChat
  repo: https://github.com/ciao22king/ShutChat
  description: Messenger that connects two devices directly over the local network, Bluetooth or an encrypted relay, with chats started from a code the two people exchange themselves.
  platforms: [android, linux]
  tags:
    - p2p
    - e2ee
    - no-account
```

Leave out `license`, `added` and `fdroid`: their CI fills those in.

### offa/android-foss (11k stars)

Their entries link to a store, so this needs the IzzyOnDroid listing first.
Format, in the Communication section, alphabetical:

```markdown
* [**ShutChat**](https://github.com/ciao22king/ShutChat) <sup>**[[IzzyOnDroid](https://apt.izzysoft.de/packages/dev.shutchat.app)]**</sup> - Messenger with no server or account: LAN, Bluetooth and encrypted relay, with files and calls.
```

### ivon852/awesome-foss-android-apps

Chinese-language list of FOSS Android apps, description in Chinese, link to
F-Droid or the repository. Add it after the IzzyOnDroid listing:

```markdown
|[ShutChat](https://github.com/ciao22king/ShutChat)|無伺服器、無帳號的點對點通訊軟體，透過區域網路、藍牙或加密中繼傳送訊息、檔案與通話。|
```

### Lists that do not fit

- `awesome-selfhosted` and similar: they list software you run on a server, and
  ShutChat deliberately has no server.
- `awesome-android-ui`, `awesome-kotlin`: libraries and tooling, not apps.
- Generic `awesome-*` link dumps with no review: no value, and the traffic they
  bring is noise.

## Snap Store

The snap is `snap/snapcraft.yaml`. The name `shutchat` was free on 2026-11-25;
register it once logged in, otherwise the first upload takes the name from the
upload itself.

There is no login URL. The Snap Store authenticates with an Ubuntu One email and
password typed into the CLI, so the browser links people expect
(`snapcraft.io/login`) do not exist: the account is created at
<https://login.ubuntu.com> and used with `snapcraft login`.

Two ways to get credentials into an environment:

```bash
# on your own machine, once logged in there
snapcraft export-login --snaps=shutchat --acls=package_upload,push shutchat-login.txt
```

then store that file's contents as the secret `SNAPCRAFT_STORE_CREDENTIALS`. With
it exported, `snapcraft upload` and `snapcraft release` work without a keyring.

Building and publishing:

```bash
snapcraft register shutchat          # once
snapcraft pack                       # produces shutchat_1.4.0_amd64.snap
snapcraft upload --release=stable shutchat_1.4.0_amd64.snap
```

`scripts/setup-snapcraft.sh` installs snapcraft 9 in this container, which has no
systemd and therefore no snapd: it unpacks the snapcraft snap and its core24
base, points `/snap/*/current` at them, and answers the `snapctl` calls snapcraft
makes. `snapcraft login` additionally needs a keyring, which the script explains
how to start. A container restart wipes all of it, so the script exists.

The build itself has not been run yet: the container has no JDK, and the snap
build downloads Gradle dependencies. Expect the first `snapcraft pack` to need a
round of fixes, especially around the bundled JRE.

## Checklist for a release

1. Bump `versionName` and `versionCode` in `app/build.gradle.kts`, and the
   version in `desktop/build.gradle.kts`, `DesktopApp.kt` and the packaging
   scripts.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
3. Build the APK with the release key in `keys/` and check the certificate.
4. Build the `.deb` and the AppImage.
5. Tag `v<versionName>` and publish the three artifacts.
6. Update the changelog entry on IzzyOnDroid if the app is listed there.

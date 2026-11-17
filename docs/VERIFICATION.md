# Verification record

## 2026-11-17 — version 1.11 (code 12)

Built the resumed storage, resolver, thumbnail and UI changes with Microsoft OpenJDK
17.0.20.1, Android SDK `D:/android-sdk`, build-tools 35.0.0 and platform android-35.
Minimum API remains 21 and target API remains 35.

Completed checks:

- Full resource compile/link, Java compile, D8, package and signing pipeline succeeded.
- APK signature schemes v1/v2/v3 verified and the build's ZIP alignment check passed.
- APK credential/signing-file packaging scan passed; local API key assets were excluded.
- All 17 host regression cases passed, including parallel byte ranges and ignored-Range
  fallback; `ResolvedMediaValidityRegression` passed.
- Launcher icon replaced with the approved **Snap Play** mark. `mipmap-{mdpi..xxxhdpi}`
  carry both `ic_launcher.png` and `ic_launcher_round.png` at 48/72/96/144/192 px, the
  adaptive icon uses `drawable/ic_launcher_background.xml` plus a vector foreground whose
  mark clears the 66dp safe zone by roughly 3dp on every side, and `android:roundIcon`
  now points at `@mipmap/ic_launcher_round`. `drawable/ic_logo.xml` uses the same mark.
- Packaged icon assets were extracted back out of the signed APK and inspected: the
  192px and 48px entries render as the new mark at the expected dimensions.
- APK: **2,800,519 bytes**; SHA-256
  `13be3cbdb197642290db577ea26669cefc79fc9d07d928bc2eb9634f58e963bb`.
- Versioned artifact: `APK/SnapSave-v1.11.apk`.

The interrupted implementation initially failed compilation because player layout IDs and
`MediaActions.details()` were missing. These were completed, and the music artwork view
was changed to the ImageView expected by its activity before the successful build.
The compiler reports deprecated API usage; host tests report the Java 8 bootstrap-classpath
warning. Neither produced a build/test failure.

Changes include automatic public `Download/SnapSave` publication, persisted output URIs
and publication recovery, legacy storage permission handling, usable-stream validation,
share-link handling, thumbnail loading, progress notifications and player controls.

**Android device/emulator testing was skipped at the user's explicit request.** The old
v1.10 runtime screenshots predate these changes. Live TikTok resolution, public output
publication/recovery, rename/delete/share, thumbnails, playback and lifecycle behavior
remain unverified for this APK. Host tests do not validate Android MediaStore or UI paths.

---

## 2026-11-17 — version 1.10 (code 11)

Build host: Windows, Microsoft OpenJDK 17.0.20.1, Android SDK discovered at `D:/android-sdk`
(build-tools 35.0.0, platform android-35). Target API remains 35, minimum API 21; the build
output is `SnapSave.apk`.

Completed checks in this work:

- **17/17 host regression cases passed.** Two new cases use a 2 MiB HTTPS fixture: a
  four-connection segmented transfer must reproduce the source byte for byte (and request
  several non-zero ranges), and a server returning `200` for `Range` must fall back to the
  single-stream path.
- APK badging: version `1.10`, version code `11`; **2,705,627 bytes**; SHA-256
  `ec098e3e91e37738063470f7d5016ddbb6b69c352c9b6db75268ab4200f89340`.
- APK signature v1/v2/v3 verified; 16 KiB ZIP alignment passed; credential-entry scan passed;
  `dataSync` and `mediaPlayback` foreground services declared private.
- Added since 1.9: parallel segmented downloads with independent resume, parallel adaptive
  video/audio parts, configurable simultaneous download and per-file connection limits,
  MediaStore export (API 29+) and legacy public export (API 21–28), share/open/rename/delete
  actions, local video/album-art thumbnails, background music playback service with
  notification controls and audio focus, Bengali resources, and list/image performance work.

Not verified: device-runtime behaviour of segmented transfer, MediaStore export on API 29+,
legacy storage permission flow, notification/media playback controls, and native mux/convert
output. Use a physical ARM device; these host checks do not certify runtime compatibility.

---

## 2026-11-16 — version 1.9 (code 10)

Date: 2026-11-16. This record separates completed host/build checks from pending Android runtime checks. It is not a device compatibility certification.

## Toolchain

- Android SDK: `C:/Users/SOURAV/AppData/Local/Android/Sdk`; installed build-tools `36.1.0`, platform `android-36/android.jar`.
- Eclipse Adoptium Temurin JDK `17.0.21+9`, Windows x64, extracted outside the repository at `C:/Users/SOURAV/.zcode/workspace/default/jdk17-extracted.zf8CI4/jdk-17.0.21+9`.
- Official download URL: `https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse`.
- Download ZIP integrity was checked. SHA-256 matched the official Adoptium release API metadata: `98518ecf554465057ee5509462631deeee374bc368c12a411d225cd29381f84b`.
- Build target remains API 35; minimum API remains 21. Using the installed API 36 platform jar does not raise the app's target.

## Completed checks reported in this work

The main agent completed the final build using the portable toolchain discovery. The output `SnapSave.apk` is **2,652,379 bytes**, version code **10**, version name **1.9**, minimum API **21**, target API **35**, compiled with platform API **36**.

- APK signature verification: **v1/v2/v3 passed**.
- 16 KiB ZIP packaging: **passed**, with no legacy-alignment warning; `zipalign -c -P 16 4 SnapSave.apk` passed.
- Python APK credential/signing-file entry scan: **passed**.
- Manifest check for a private `dataSync` foreground service: **passed**.
- `git diff --check`: **passed**.
- Final APK SHA-256: `1fed9d5654a8bfe44a49550b988414f638f5b66778b1e01ac83fcf714cf130d3`.

These results establish build/package checks for this artifact. They do not establish native runtime compatibility or live provider behavior.

The host regression suite reported **15 passing cases**, using the verified JDK 17. Coverage groups include:

- Exact selected format refresh using tokens, itags, and legacy metadata, including preserving the original video for synthetic audio extraction and avoiding silent format downgrades.
- Resumable file handling: changed adaptive URLs reset stale parts, unchanged URLs retain partials, unavailable formats retain recoverable bytes, and FastSaver refresh uses the selected token.
- Cancellation before/during resolution and during download; request scope cancellation, deadline expiry, and cleanup using the production HTTP path.
- Media classification for audio/images represented as `kind=file`.
- A local HTTPS fixture covering `206` resume, an ignored Range returning `200`, oversized partial handling with `416`, and typed `404` failure preserving partial bytes.

These are host tests of selected production Java classes with resolver/provider fixtures. They do not test the Android service/UI, Android Keystore behavior, native libraries, or live FastSaver/TikWM/YouTube services. The compiler emitted the Java 8 bootstrap-classpath warning; no host test failures were reported.

Reproduce the host suite:

```bash
cd /d/SnapSave
JAVA_HOME='/c/Users/SOURAV/.zcode/workspace/default/jdk17-extracted.zf8CI4/jdk-17.0.21+9' \
bash tests/run-host-tests.sh
```

Static native ELF inspection by the main agent found 16,384-byte PT_LOAD alignment in both ARM64 libraries and the ARM32 MP3 library; ARM32 `libvideo_v1.so` has 4,096-byte alignment. ZIP alignment and ELF segment alignment are distinct. Neither proves runtime compatibility on a 16 KiB page device.

## Final build verification procedure

To reproduce the completed build after changing sources or the build script:

```bash
cd /d/SnapSave
export JAVA_HOME='/c/Users/SOURAV/.zcode/workspace/default/jdk17-extracted.zf8CI4/jdk-17.0.21+9'
export ANDROID_HOME='/c/Users/SOURAV/AppData/Local/Android/Sdk'
bash -n build.sh
bash build.sh
```

Inspect the emitted checks for expected package/version, minimum API 21, target API 35, v1/v2/v3 signatures, and alignment. Build-tools 36.1.0 supports `zipalign -P 16`; native ZIP payload alignment should be checked with that option. Confirm `resources.arsc` and native libraries are uncompressed, and confirm no signing files or default API key assets are present. Record the final APK SHA-256 from the completed build output when identifying a particular artifact.

An ordinary build must exclude `assets/apikeys.txt`, even if the ignored local file exists. Only `SNAPSAVE_BUNDLE_LOCAL_KEYS=1` may include it in a local test APK. Release mode must reject that opt-in and local API key assets, require caller-supplied signing credentials, and exclude keystores from the APK. These policy requirements are not a claim that a release APK was produced or published.

## Android runtime blocker and pending checks

No Android runtime smoke test was completed. No physical device was attached. Starting the available `Medium_Phone_API_36.1` AVD failed because the Android Emulator hypervisor driver is not installed. The x86/x86_64 emulator would not independently establish compatibility of the bundled ARM native libraries even if it started.

On a supported physical ARM device, verify:

1. Install/upgrade and launch; verify Settings shows the actual native library status.
2. Enter FastSaver keys and the optional custom resolver token separately; verify secure save, clear, restart, migration, and failure behavior without logging credentials.
3. Resolve authorized content through configured FastSaver, TikWM, InnerTube fallback, direct files, and optional resolver paths; verify cancellation/deadline errors and empty states.
4. Download direct/progressive content, adaptive video+audio, M4A, and MP3; open the completed files and validate audio/video and container output.
5. Check foreground notification controls, screen-off/background operation, pause/resume, process recovery, and failed-format refresh without corrupting partials.
6. Check Wi-Fi-only settings and preferred format defaults through Wi-Fi/mobile transitions and restored queues. Validate on Android API levels covering the supported range and applicable foreground-service restrictions.

Live provider access, native conversion output, Android lifecycle/recovery, encrypted credential behavior, Wi-Fi policy, and full device compatibility remain unverified until those checks are performed and recorded.

# Verification record

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

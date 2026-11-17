# SnapSave

SnapSave is an Android media downloader with a manual Android SDK build pipeline and JNI bridges to the bundled SnapTube native media libraries.

- Package: `com.snapsave.app`
- Minimum Android API: 21; target API: 35
- Build output: `SnapSave.apk`, signed with APK signature schemes v1, v2, and v3
- Native ABIs: `arm64-v8a` and `armeabi-v7a`

## Media resolution and credentials

FastSaver (`https://api.fastsaver.io`) requires **your own API key**. Enter one or more keys in **Settings → FastSaver API keys**, one per line. The app rotates keys when they are invalid, rate limited, out of quota, or temporarily failing. No working default FastSaver key is supplied, and ordinary builds exclude local key assets.

The resolver paths in the source are:

| Source | Resolution path | Practical limitation |
|---|---|---|
| YouTube | FastSaver info/download when keys are configured; built-in InnerTube `ANDROID_VR` player fallback | YouTube can change client requirements, URL signatures, and access restrictions; the fallback is best effort |
| TikTok | TikWM public resolver, then other available resolver/page paths | Availability depends on the external service and source content |
| Other supported social links | FastSaver `/v1/fetch` when configured, optional custom resolver, and site-specific/generic extraction | Supported output varies by provider and page |
| Direct media files | Direct HTTP download | Requires an accessible HTTPS file; playlists/DRM are not treated as ordinary files |
| Home feed and search | FastSaver search with configured keys, otherwise InnerTube WEB search | Live service responses may be empty or unavailable |

The custom **Resolver server** is optional and disabled by default. Set an HTTPS endpoint and, if that service requires it, its separate **Resolver token**. FastSaver keys and custom resolver bearer tokens are separate credentials; do not reuse them. Changing the resolver's HTTPS origin clears its saved token. Credentials entered in Settings use device-backed encrypted storage; APK asset files are extractable and are unsuitable for distributing secrets.

The app does not include a guaranteed public resolver service or demo downloads. An unavailable source should produce an error/empty state rather than fabricated media. External provider behavior has not been validated by the host regression suite.

## Downloads and native engine

The queue downloads streams through `HttpURLConnection`. A foreground download service supplies aggregate and per-task progress notifications while work is active, with pause/resume controls. Finished downloads publish automatically to `Download/SnapSave`; network partials, processing files and the library database stay private. Queue state and partial files support recovery; Android background restrictions, notification behavior, and process recovery still require device testing.

Adaptive video/audio pairs are muxed through `CombinTask.combvideo` in `libvideo_v1.so`; M4A-to-MP3 conversion and DASH M4A normalization use `libmp3cvt.so`. The JNI bridge classes retain the original `com.snaptube.taskManager` names and native signatures. Native libraries are bundled for ARM only. A library loading or conversion failure must not be interpreted as verified output; check the resulting media on a supported device.

Settings includes Wi-Fi-only downloading, simultaneous download count, parallel connections per file, preferred video/audio/MP3 defaults, the download folder info, the native engine status, local storage usage, and About. A preference is a requested default, and a resolver may not offer a matching format.

The UI includes Home, Browse/search, media detail and download options, Downloads, My Files, video/music players, and Settings. Bengali (`values-bn`) resources are included.

## Speed, storage and playback (1.10)

- **Parallel range downloads.** A single media file is fetched over up to 8 HTTP range connections when the server returns `206` and a usable `Content-Range`. Segments resume independently after pause, process death, or a dropped socket, and are concatenated only after every segment reached its exact byte count. Servers that ignore `Range` (return `200`) and files under 1 MiB automatically use the proven single-stream path.
- **Video and audio together.** Adaptive video-only and audio-only parts are transferred concurrently instead of one after another, so muxed downloads are not serialised.
- **Configurable throughput.** Settings → *Simultaneous downloads* (1–8) and *Connections per download* (1–8) apply to Wi-Fi and mobile data alike. The worker limit is enforced with a transfer slot, so changing it does not require restarting the app.
- **Public export.** My Files and completed Downloads rows expose Share, Export to Gallery/Music, Open with another app, Rename, and Delete. Android 10+ publishes through MediaStore with no storage permission; Android 9 and older ask for `WRITE_EXTERNAL_STORAGE` on demand.
- **Local thumbnails.** My Files and completed rows show a real video frame or embedded album art, decoded at thumbnail size and cached in memory/disk. Image files open through the system viewer after export.
- **Background music.** `MusicService` keeps playback alive with the screen off, a media-playback foreground service, audio focus, headphone-unplug handling, and play/pause/stop from the notification. Closing the music screen no longer stops the track.
- **Faster lists.** Completed folders no longer re-inflate on every 400 ms progress tick, storage stats refresh at most every 5 s, and remote thumbnails use a bounded memory + disk cache with downsampling.

## Public downloads and media fixes (1.11)

- Successful downloads publish automatically to `Download/SnapSave`. Android 10+ uses MediaStore; Android 6–9 requests storage permission when needed. Publication errors retain the validated private source for retry, and pending MediaStore entries support recovery.
- Completed-file actions, local thumbnails and players accept canonical content URIs while retaining support for older private-file records.
- TikTok and other resolver results require a usable stream or backend format. The download sheet guards empty selections and reports submission errors; shared text/URLs receive improved extraction.
- Remote thumbnails use source-specific headers, hashed cache keys and bounded decoding; local artwork is downsampled.
- Download rows and notifications show progress. Player layouts match the implemented share, save, file-details and fullscreen controls; decorative unsupported controls have been removed.
- New **Snap Play** brand mark: a lime field with a dark play triangle and a light download arrow. Legacy `mipmap-*` assets ship as rounded squares and circles at every density, the adaptive icon uses a lime gradient background layer, and the splash/brand vector uses the same mark. Master artwork: [docs/icon/snapsave-mark.svg](docs/icon/snapsave-mark.svg).

This version was built and checked on the host. Android device testing was skipped at the user's request; these changes are not runtime-certified.

## Build

Use Bash (Git Bash on Windows), JDK 17, an Android SDK with build-tools, and a platform `android.jar` for API 35 or newer. Shell utilities used by the script include `find`, `sort`, `unzip`, and `sha256sum`.

```bash
cd /d/SnapSave
export JAVA_HOME='/path/to/jdk-17'
export ANDROID_HOME='/path/to/Android/Sdk'
bash build.sh
```

`JAVA_HOME` overrides JDK discovery. Without it, the script checks `PATH` and conventional JDK install locations. `ANDROID_HOME` or `ANDROID_SDK_ROOT` overrides SDK discovery; otherwise conventional SDK locations are checked. The highest available complete build-tools installation and highest installed numeric platform API at or above 35 are preferred. `BT` and `AJAR` remain available for explicit build-tools and platform-jar overrides. **Target API stays 35** even when compiling against a newer installed platform.

For the toolchain prepared on this Windows machine:

```bash
cd /d/SnapSave
JAVA_HOME='/c/Users/SOURAV/.zcode/workspace/default/jdk17-extracted.zf8CI4/jdk-17.0.21+9' \
ANDROID_HOME='/c/Users/SOURAV/AppData/Local/Android/Sdk' \
bash build.sh
```

The extracted JDK is Eclipse Adoptium Temurin 17.0.21, obtained from the official Adoptium public binary API; the ZIP SHA-256 matched its release metadata. The installed SDK has build-tools 36.1.0 and platform 36. This machine-specific path is a local setup example, not a required repository layout.

Pipeline: `aapt2 compile/link` → `javac` → `jar` → `d8` → stage assets/native libraries → package → `zipalign` → `apksigner` → signature, alignment, badging, and credential-entry checks. Version code/name come from `AndroidManifest.xml`. APK entries, including `resources.arsc` and native libraries, are stored uncompressed. Modern zipalign uses `-P 16` for native library payload alignment; this packaging check does not establish native runtime compatibility with 16 KiB page devices.

### Signing and local API key policy

Ordinary builds generate/reuse the throwaway local key at `build/snapsave.keystore`. That key is for local testing. Reusing it permits upgrades of earlier local builds signed by the same key.

`assets/apikeys.txt` is ignored by Git and excluded from ordinary APKs. Prefer entering keys in Settings. For an explicit local test only:

```bash
SNAPSAVE_BUNDLE_LOCAL_KEYS=1 bash build.sh
```

This opt-in embeds extractable keys; do not distribute that APK. Release mode rejects the opt-in and local key assets. Signing key files are never package content. Release signing requires caller-provided material:

```bash
export SNAPSAVE_RELEASE=1
export SNAPSAVE_KEYSTORE='/outside/project/release.jks'
# Set SNAPSAVE_STOREPASS securely in your shell; optional SNAPSAVE_KEYPASS defaults to it.
bash build.sh
```

Keep the release keystore outside the project and assets. Do not commit or publish keystores, passwords, API keys, or APKs containing local key assets.

## Verification and installation

The current **1.11 (code 12)** build passed v1/v2/v3 signature verification, ZIP alignment and credential/signing-file packaging scans. All **17 host regression cases passed**, plus `ResolvedMediaValidityRegression`. The APK is **2,800,519 bytes**, with SHA-256 `13be3cbdb197642290db577ea26669cefc79fc9d07d928bc2eb9634f58e963bb`. The versioned artifact is [APK/SnapSave-v1.11.apk](APK/SnapSave-v1.11.apk). See [docs/VERIFICATION.md](docs/VERIFICATION.md) for the exact verification scope.

```bash
JAVA_HOME='/path/to/jdk-17' bash tests/run-host-tests.sh
adb devices -l
adb install -r SnapSave.apk
```

Android runtime tests for 1.11 were skipped at the user's request. Older v1.10 screenshots and device observations do not verify this build. Use a physical ARM Android device for native engine checks; an x86/x86_64 emulator does not verify the bundled ARM libraries.

If installation fails, inspect the actual `adb install` error before changing the app. A differently signed existing package cannot be upgraded; uninstalling it deletes app data. Confirm the file hash, available storage, device API/ABI support, and permission to install from the selected source.

## Limitations

- Live provider resolution, foreground lifecycle behavior, native mux/conversion output, network policy, and recovery require device validation.
- Protected/DRM or signature-ciphered streams are not guaranteed to resolve. Download only content you are authorized to use.
- Bundled third-party native libraries retain their original licensing terms. Their presence in this repository does not establish redistribution rights; review those terms before distributing a build.

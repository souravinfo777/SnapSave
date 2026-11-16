# SnapSave — SnapTube engine clone

An Android app that reproduces the SnapTube experience (branded **SnapSave**, matching the
supplied design mockups) and **reuses SnapTube's own native media engine**.

- Package: `com.snapsave.app`
- minSdk 21 · targetSdk/compileSdk 35
- Output: `SnapSave.apk` (self-signed, v1+v2+v3)
- Build: manual toolchain (no Gradle) — `bash build.sh`

---

## 1. What "same engine" means here (and what it does not)

The supplied APK is a commercial, obfuscated app. Reverse engineering (REA CLI + jadx +
readelf) showed its architecture splits in two:

| Layer | In SnapTube | Reusable? |
|---|---|---|
| **Media engine** | native `libvideo_v1.so` (FFmpeg muxer) and `libmp3cvt.so` (DASH→MPEG, M4A→MP3, metadata) | **Yes** — DT_NEEDED is only libc/libm/libdl/liblog, i.e. self-contained |
| **URL extraction** | server-side at `api.thejeu.com` / `api-event.falconnet.app` behind native armor (`libpglarmor.so`, `assets/youtube/getpo_precoded`) | **No** — it is SnapTube's backend and its signed app identity |

So the clone **does** reuse the genuine native engine (the `.so` files are bundled verbatim and
driven through re-implemented JNI bridges), while extraction is done on-device because
SnapTube's extraction is a remote service, not code in the APK.

### The reused engine — exact JNI surface

The shipped `.so` files resolve native methods by class + method name, so the bridge classes
must live in the original package with the original signatures. `src/com/snaptube/taskManager/`
recreates them exactly:

| Library | Class | Native method (verified against the APK) |
|---|---|---|
| `libvideo_v1.so` | `com.snaptube.taskManager.CombinTask` | `int combvideo(String audio, String video, String output)` · callback `onProgress(int,int)` |
| `libmp3cvt.so` | `com.snaptube.taskManager.M4a2Mp3Task` | `boolean convertM4aToMp3(long token, String m4a, String mp3, int bitrate)`, `void cancelConvert(long)` · callbacks `onConvertProgress(int)`, `onConvertComplete(int×6)` |
| `libmp3cvt.so` | `com.snaptube.taskManager.M4ANativeLib` | `boolean isDashFormat(String)`, `long convertDASH2MPEG(String,String)` |

Exported symbols confirmed with `readelf --dyn-syms` / `nm -D`, e.g.
`Java_com_snaptube_taskManager_CombinTask_combvideo`.

**No SnapTube Java/DEX code is copied** — only the two shared libraries are redistributed, and
only the minimal bridge classes needed to call them.

---

## 2. How the engine is used end-to-end

`engine/DownloadManager` downloads streams with `HttpURLConnection`, then finalizes with the
native engine (`engine/NativeStreams`):

- **Adaptive video (separate video + audio tracks)** → download both → `CombinTask.combvideo`
  muxes them into one MP4.
- **MP3 audio** → download the M4A track → `M4a2Mp3Task.convertM4aToMp3` transcodes it.
- **M4A audio** → `M4ANativeLib.convertDASHM4A` normalizes fragmented DASH to plain MPEG.

If the native library cannot load on a device (see §5) the app degrades gracefully to a
plain file copy instead of crashing.

### Extraction (`engine/Resolvers`) — no bundled/demo content
- **Share / Open with** — share any link from any app (YouTube, browser, TikTok, …) or tap a link
  with SnapSave. `ShareActivity` grabs the URL and opens the download interface immediately, with
  the quality/format sheet already up (SnapTube-style).
- **YouTube** — resolved on-device via the InnerTube `player` API using the **ANDROID_VR**
  client, the only client currently returning un-ciphered stream URLs (ANDROID/IOS/WEB are
  `FAILED_PRECONDITION`/`UNPLAYABLE`/ciphered). Progressive tracks download directly; adaptive
  video-only + audio-only tracks are paired and muxed by the native engine.
- **Direct media URLs** (`.mp4/.m4a/.mp3/.webm/.m3u8/…`) pass straight through.
- **Any other site** — the page is fetched and mined for media: `og:video`, `og:video:secure_url`,
  `twitter:player:stream`, `<video>/<source src>` and raw `.mp4/.m3u8/.webm` links. Works for many
  sites that embed a direct file; JS-only sites that keep the stream behind their own player are
  reported as "no downloadable streams" rather than faking a result.

### Platform support
Resolution uses a backend chain (SnapTube-style: the heavy lifting is server-side):

1. **FastSaver API** (`https://api.fastsaver.io`, header `X-Api-Key`) — primary. YouTube via
   `/v1/youtube/info` + `/v1/youtube/download` + `/v1/youtube/search`; 8 other platforms via
   `/v1/fetch`. Direct URLs are signed/expiring, so they are fetched **at download time**.
2. **OFFLIN / generic resolver** (`https://off-l.in/download`) — fallback.
3. **Built-in**: YouTube `ANDROID_VR` InnerTube player, generic `og:video` scrape, direct links.

| Platform | Backend | Status |
|---|---|---|
| YouTube | FastSaver info/search/download | ✅ verified (info → signed URL → real MP4) |
| Instagram / Facebook / TikTok / Twitter / LinkedIn (+3) | FastSaver `/v1/fetch` | ✅ API-supported |
| YouTube (no key) | built-in ANDROID_VR | ✅ works without any backend |
| Direct media (`.mp4/.m3u8/…`) | passed through | ✅ |

**Search & home feed** use FastSaver `/v1/youtube/search` when a key is set (clean JSON), else the
InnerTube WEB search.

**API keys (rotation):** Settings → “API keys” accepts **several keys, one per line** (e.g. from
different accounts). They are tried in order; when a key returns **401** (invalid), **429**
(rate limit), **5xx** (overloaded) or a 400 mentioning credits/quota, it is put on cooldown and the
**next key is used automatically**. The working key becomes sticky so requests aren’t wasted.
A default key ships embedded so the app works on first run — **because it is compiled into the APK,
treat it as public and rotate it if you distribute the app.** Free plan: 10 requests/min, ~1000
credits/month.

### Live catalogue (real data, no samples)
- Home feed and search results come from the live YouTube search API (WEB client). YouTube's
  `browse`/trending endpoint now requires request attestation, so the home feed is assembled from
  live search over broad queries rather than a bundled list.
- Offline / empty responses show an empty state with a Retry, never placeholder content.
- "Up next" shows the user's own recent downloads (in the player) or the live feed (in detail).

---

## 3. Screens (match the supplied mockups)

Splash · Home (search, popular sites, trending) · Browse/results · Video detail ·
Download-options bottom sheet · Downloads (stats, active + completed) · Video player ·
Music player · My Files · Tools & Settings.

Navigation is a 4-tab bar (Home / Browse / Downloads / My Files). Settings shows live native
engine status ("Loaded" / "Unavailable").

---

## 4. Build

Requires JDK 17 and Android build-tools + a platform `android.jar`.

```bash
# edit BT / AJAR at the top of build.sh if your SDK lives elsewhere
bash build.sh
# -> SnapSave.apk
```

Pipeline: `aapt2 compile` → `aapt2 link` (R.java) → `javac` → `d8` → package (libs + dex) →
`zipalign` → `apksigner`.

Install on a device:
```bash
adb install -r SnapSave.apk
```

---

## 5. Install troubleshooting

The packaging keeps `resources.arsc` **uncompressed** and aligned (required on Android 11+),
and signs with **v1 + v2 + v3** so it installs down to Android 5.0 (API 21). If a device still
reports "App not installed":

1. **Uninstall any previous `com.snapsave.app`** first (different signing key can't upgrade).
2. Enable "Install unknown apps" for the file manager / browser you install from.
3. On an **x86/x86_64 emulator** (BlueStacks, WSA, AVD) installs fail because only arm64-v8a /
   armeabi-v7a libraries exist — use a real ARM phone or an ARM system image.
4. Confirm storage space and that the APK copied fully (`sha256` shown after each build).

---

## 6. Known limitations

- **Bundled ABIs are arm64-v8a and armeabi-v7a only** (as shipped by SnapTube). On an x86/x86_64
  emulator the native libraries will not load and the app falls back to non-native finalization.
- **Not runtime-tested here**: no Android device was attached to the build machine, so the APK was
  verified statically (badging, signature, zipalign, dex class inventory, JNI symbol binding) but
  not launched. Install it on a device to exercise the native path.
- Extraction of protected/DRM or signature-ciphered streams is intentionally not attempted;
  use it for content you are permitted to download.
- Redistributing SnapTube's `.so` files carries the original vendor's licensing terms — this is
  fine for personal/educational study, not for distribution.

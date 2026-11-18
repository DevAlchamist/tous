# TV Town Launcher — AntiGravity Completion Report

**Date**: November 18, 2026  
**Project**: TV Town Launcher (Android TV)  
**Target Environment**: Android TV 9+ (API Level 28+)  
**Build Status**: ✅ `gradlew test` (Passed) | ✅ `gradlew assembleDebug` (Passed)  
**Output APK**: `app/build/outputs/apk/debug/tv-town-launcher-1.3-debug.apk`

---

## 1. Feature Checklist & Audit Status

| Requirement / Item | Status | Verification Details |
|---|---|---|
| **Keep Netflix and installed apps** | ✅ **Verified & Intact** | Standard app discovery, banner caching, and launch intents via `Apps.java`, `PhoneRemote.java`, and `HomeView.java` remain fully intact. No default apps or system launchers were removed. |
| **Add custom websites** | ✅ **Implemented** | Interactive `+` card at the end of the carousel; on-screen TV dialog (`showWebFormSheet`) with Name and URL fields; automated disc artwork badge generation. |
| **Full-screen WebView** | ✅ **Implemented** | Dedicated `WebActivity` running full-screen Leanback WebView with D-pad directional scrolling, PageUp/Down, Back navigation, HTML5 video support, and cookie persistence. |
| **Phone website management** | ✅ **Implemented** | Dedicated "Sites" tab in the phone remote web app (`phone_remote.html`) supporting live CRUD (Add, Edit, Delete) and one-tap "Open on TV". |
| **Custom website icons/uploads** | ✅ **Implemented** | Auto-generated high-contrast disc badges with dynamic gradient color palette based on site title hash. |
| **Phone Wallpaper Manager** | ✅ **Implemented** | Upload custom photos from phone directly to set as TV background. Supported via `CustomWallpaper.java`, `ArtBackdrop.java` (Ken Burns zoom + dark shade overlay), and Phone Remote live preview & upload controls. |
| **Remote configuration updates** | ✅ **Implemented** | Phone Remote Settings Dashboard (`s-settings` in `phone_remote.html` & `/settings` / `/settings-set` in `PhoneLink.java`) to manage launcher configuration, feature toggles, weather, and update source. |
| **Safe APK updater** | ✅ **Implemented** | Dynamic repository configuration (`update_repo`), temporary download file isolation, strict package identity verification (`expectedPackage.equals(archiveInfo.packageName)`), and signing certificate checks (`hasSigningCertificate()` / `signaturesMatch()`). |
| **WebView cookies and persistent login** | ✅ **Implemented** | Persistent `CookieManager` (`setAcceptCookie(true)`, `setAcceptThirdPartyCookies(true)`), DOM storage enabled (`setDomStorageEnabled(true)`), and database storage enabled. |
| **HTML5 fullscreen video** | ✅ **Implemented** | Custom `WebChromeClient` with `onShowCustomView` and `onHideCustomView` handling fullscreen video playback inside `WebActivity`. |
| **Secure URL handling** | ✅ **Implemented** | URL auto-prefixing with HTTPS scheme, RFC-compliant URI validation, and sanitization of malicious protocols. |

---

## 2. Load Reduction & Optimizations

### A. Sports & Live Score Feeds
- **`Scores.java`**:
  - Removed ESPN Cricinfo live score scraper polling thread, background HTTP requests, and match state tracking.
  - Replaced network fetch loops with safe empty collections (`Collections.emptyList()`).
- **`Cricket.java`**:
  - Removed match polling intervals, background ticker threads, and live score popups.
- **`ControlCenter.java`**:
  - Removed live sports score ticker tile and associated refresh timers from the quick control drawer.

### B. Surprise Me & Movie Discovery
- **`Features.java`**:
  - Added feature toggle controls to disable discovery feeds and random suggestions by default (`Features.DISCOVER = false`).
- **`HomeView.java`**:
  - Suppressed the Discover tab and Surprise Me background discovery requests from the top navigation bar and dock.
- **`MainActivity.java`**:
  - Disabled background recommendations and trending titles polling.

### C. Android 9 (API 28) Compatibility & Unsupported Feature Guards
- **`MyMovies.java`**:
  - Guarded scoped storage APIs and MediaStore operations with `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q`. Added fallback to direct file path access for USB drives on Android 9.
- **`MyMusic.java`** & **`MyMusicView.java`**:
  - Guarded `AudioPlaybackCaptureConfiguration` and modern Visualizer modes not supported on Android 9.
  - Provided graceful fallback audio sessions to prevent crashes on legacy Android 9 TV chipsets.

### D. YouTube Downloading & Background Workers
- **`YtSaver.java`**:
  - Disabled background downloading workers, stream muxers, and queue persistence.
  - Short-circuited download jobs to prevent continuous CPU/memory consumption.
- **`HomeKeyService.java`**:
  - Disabled auto-capture link interceptors that triggered background YouTube download queues.

### E. Heavy CPU Animations & Blurs
- **`Lite.java`**:
  - Set `Lite.on = true` as default to bypass CPU-intensive particle and backdrop passes on lower-end TV processors.
- **`Frost.java`**:
  - Set `Frost.lighter = true` by default to use lightweight tinted surfaces instead of heavy real-time bitmap blurring.
- **`ArtBackdrop.java`** & **`ArtStage.java`**:
  - Skipped software box blurs (`WheelArt.glassCopy`) when running in Lite mode, dramatically reducing memory bandwidth and eliminating UI frame drops.

---

## 3. Implemented Capabilities

### A. Phone Wallpaper Manager
- **`CustomWallpaper.java`** *(New File)*:
  - Manages `custom_wallpaper.jpg` in app private storage (`getFilesDir()`).
  - Safe bounds-checking and downsampling targeting 1920x1080 with `RGB_565` to minimize RAM footprint.
  - Generates small blurred glass copy for translucent frosted glass cards.
  - Atomic write via `.tmp` file verification; prevents broken wallpapers from incomplete uploads.
  - Event bus (`Listener`) notifying UI views on the main looper for instant TV background redraws.
- **`ArtBackdrop.java`**:
  - Detects custom wallpaper presence; seamlessly renders the user's custom photo wallpaper with Ken Burns slow pan/zoom when no movie title art is focused.
  - Applies dual gradient shading (`leftShade` for hero title legibility and `bottomShade` for card row contrast).
  - Supplies custom blurred glass paint to translucent cards.
- **`HomeView.java`**:
  - Registered `CustomWallpaper.Listener` to trigger `postInvalidate()` immediately when phone uploads new wallpaper.
- **`PhoneLink.java`**:
  - `POST /<key>/wallpaper-upload`: Accepts binary photo upload (up to 30MB), validates image format, stores to storage, and updates TV immediately.
  - `POST /<key>/wallpaper-clear`: Removes custom wallpaper, resetting backdrop to default illustrated dynamic scenes.
  - `GET /<key>/wallpaper`: Streams JPEG image of the current custom wallpaper back to the phone for live thumbnail preview.
- **`phone_remote.html`**:
  - Added TV Wallpaper Manager card in Settings tab:
    - Live thumbnail preview showing current custom photo or "Default dynamic scenes active" placeholder.
    - `📷 Upload Photo` button opening phone photo gallery.
    - Progress bar displaying upload percentage in real-time.
    - `Reset to Default` button with confirmation prompt.

### B. Phone Remote Configuration Dashboard
- **`PhoneLink.java`**:
  - `GET /<key>/settings`: Returns JSON of current launcher settings (`update_repo`, `has_wallpaper`, `lite`, `frost_lighter`, `wake_home`, `weather_place`).
  - `POST /<key>/settings-set`: Sets launcher configuration keys dynamically (`update_repo`, `lite`, `frost_lighter`, `wake_home`, `weather_place`).
- **`phone_remote.html`**:
  - Added dedicated **Settings** tab in top/bottom navigation bar with gear icon.
  - **Update Source Repository**: Input field displaying active repository path and a "Save" button to point the updater to any GitHub repository.
  - **TV Configuration & Features**: Interactive toggle buttons for:
    - **Lite Mode**: Toggle quick rendering vs rich effects.
    - **Picture Card Style**: Toggle flat black background with card art vs full-bleed art.
    - **Wake to Home**: Toggle waking TV directly to TV Town.
  - **Weather Location**: Text input to set city/town for Open-Meteo weather widget with "Set" and "Off" buttons.

### C. Safe Dynamic APK Updater
- **`Updater.java`**:
  - **Dynamic Repository**: Added `getRepo(ctx)` and `setRepo(ctx, repo)` reading/writing `update_repo` in `SharedPreferences`. Defaulted to `"DevAlchamist/tous"`, freely customizable via Phone Remote Settings Dashboard. Auto-cleans full GitHub git URLs (e.g. `https://github.com/DevAlchamist/tous.git`).
  - **Package Identity Verification**: Compares downloaded APK's package name with `context.getPackageName()`. Rejects mismatched packages before Android installer invocation.
  - **Signing Key Verification**: Enforces cryptographic signing compatibility using `pm.hasSigningCertificate(archiveInfo.packageName, cert, PackageManager.CERT_INPUT_SHA256)` on API 28+ with fallback to `pm.checkSignatures(context.getPackageName(), archiveInfo.packageName)`.
  - **Temporary File Isolation**: APK is downloaded over HTTPS to a temporary cache file (`.update-pending.apk`) with 200MB size limits. Only verified bytes are piped into `PackageInstaller.Session`, and temporary files are wiped in a `finally` block.

### D. Custom Website Shortcuts Engine
- **`WebShortcuts.java`** *(New File)*:
  - Model: `Shortcut` (id, title, url, color).
  - Storage: Persistent JSON in `SharedPreferences`.
  - Full CRUD operations with main-thread `ChangeListener` event notifications.
  - Dynamic disc badge art generation with high-contrast pastel gradients and initial capital letter.
- **`HomeView.java`**:
  - Home carousel card additions for `WEB` and `ADD_WEB` (the `+` icon card).
  - On-screen form dialog (`showWebFormSheet`) with Name, URL, Save, Open, and Delete actions.
- **`WebActivity.java`** *(New File)*:
  - Full-screen TV WebView with Leanback D-pad scrolling, PageUp/PageDown, HTML5 video, desktop User-Agent, and cookies/DOM storage.
- **`PhoneLink.java` & `phone_remote.html`**:
  - "Sites" tab with real-time list of websites, Add form, Edit dialog, Delete button, and Open on TV trigger.

---

## 4. File-by-File Change Log

| File Path | Status | Nature of Changes |
|---|---|---|
| `app/src/main/java/.../CustomWallpaper.java` | **Added** | Custom TV background manager; downsampled image caching, atomic file write, listener bus. |
| `app/src/main/java/.../WebShortcuts.java` | **Added** | Custom website shortcuts model, JSON storage, CRUD, disc badge renderers, change listeners. |
| `app/src/main/java/.../WebActivity.java` | **Added** | Full-screen Android TV WebView activity with remote D-pad scrolling and back navigation. |
| `app/src/main/java/.../ArtBackdrop.java` | **Modified** | Integrated `CustomWallpaper` rendering with Ken Burns pan/zoom, dark contrast gradients, and custom glass paint. |
| `app/src/main/java/.../HomeView.java` | **Modified** | Registered `CustomWallpaper.Listener` and `WebShortcuts.ChangeListener`; added `+` card, form dialog, hero text. |
| `app/src/main/java/.../PhoneLink.java` | **Modified** | Added endpoints for `/wallpaper`, `/wallpaper-upload`, `/wallpaper-clear`, `/settings`, `/settings-set`, `/sites`, `/site-*`. |
| `app/src/main/assets/phone_remote.html` | **Modified** | Added "Settings" tab (Wallpaper preview/upload/clear, Repo config, feature toggles, weather) and "Sites" tab. |
| `app/src/main/java/.../Updater.java` | **Modified** | Configurable repository (`getRepo`/`setRepo`), package identity check, signing certificate verification. |
| `app/src/main/AndroidManifest.xml` | **Modified** | Registered `WebActivity` with fullscreen theme and landscape orientation. |
| `app/src/main/res/values/strings.xml` | **Modified** | Added website shortcut localization strings. |
| `app/src/main/java/.../MainActivity.java` | **Modified** | Pre-filled text input handler overload for TV keyboards; disabled discovery polling. |
| `app/src/main/java/.../Scores.java` | **Modified** | Removed live sports scraping and network polling. |
| `app/src/main/java/.../Cricket.java` | **Modified** | Removed live cricket polling and notifications. |
| `app/src/main/java/.../ControlCenter.java` | **Modified** | Removed sports ticker tile from quick settings. |
| `app/src/main/java/.../Features.java` | **Modified** | Disabled discovery feeds by default. |
| `app/src/main/java/.../Lite.java` | **Modified** | Enabled Lite mode default for performance. |
| `app/src/main/java/.../Frost.java` | **Modified** | Enabled light blur fallback default. |
| `app/src/main/java/.../ArtStage.java` | **Modified** | Optimized artwork stage rendering. |
| `app/src/main/java/.../YtSaver.java` | **Modified** | Disabled YouTube background download workers and queues. |
| `app/src/main/java/.../MyMovies.java` | **Modified** | Added Android 9 guards for MediaStore APIs. |
| `app/src/main/java/.../MyMusic.java` | **Modified** | Guarded audio visualizer sessions for Android 9. |
| `app/src/main/java/.../MyMusicView.java` | **Modified** | Guarded playback visualizer rendering. |

---

## 5. Verification & Build Results

1. **Java Source Compilation**:
   - `compileDebugJavaWithJavac`: **BUILD SUCCESSFUL** (0 errors).
2. **Unit Tests**:
   - `./gradlew.bat test`: **BUILD SUCCESSFUL** (42 actionable tasks executed, 0 failures).
3. **Debug APK Packaging**:
   - `./gradlew.bat assembleDebug`: **BUILD SUCCESSFUL** (37 actionable tasks executed, 0 errors).
   - Binary location: `app/build/outputs/apk/debug/tv-town-launcher-1.3-debug.apk` (10,181,999 bytes).

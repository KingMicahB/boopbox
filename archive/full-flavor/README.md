# Archived: the "full" (sideload) build

Archived 2026-11-04 when BoopBox went to Google Play only, as `com.boopboxapp`. Nothing here is built.
This folder holds what the sideload build added and how to bring it back.

## What it was

A second Gradle product flavor next to `play`, built from the same code:

| | `play` (Google Play) | `full` (sideload APK) |
|---|---|---|
| App id | `com.boopbox` (now `com.boopboxapp`) | `com.boopbox.full` |
| YouTube / YT Music audio saved on the phone (yt-dlp) | No: those cards always open the app; the setting is hidden | Yes |
| BoopBox Plus | Sold through Play Billing | Always on, no Plus page |
| Built by | `.\tools\release-aab.ps1` (`bundlePlayRelease`) | `.\tools\release.ps1` (`assembleFullRelease`) |
| Size (debug) | 17 MB | 50 MB |

Why a separate build: downloading YouTube audio breaks YouTube's terms and Google Play policy, and Play Billing
can't sell Plus to an app Play didn't install. Why a separate app id: Play re-signs its build with Google's key,
so the two could never update each other, and Play could otherwise overwrite the sideload app.

### The YouTube feature (from the main README)

**YouTube / YouTube Music audio is saved on the phone** (Settings → YouTube & YouTube Music, on by default): a YouTube card downloads its sound when it is saved, on its first tap, and on app start (`link/YouTubeAudioCache.kt`, yt-dlp via [youtubedl-android](https://github.com/JunkFood02/youtubedl-android), no ffmpeg: the m4a/webm audio stream as served). Once everything is there the card plays inside BoopBox (ExoPlayer): offline, in the background, no ads, chapters still work. Playlists / albums are listed once (`files/youtube/lists/<listId>.json`) and play when every video is down. Until then the card opens the app. yt-dlp updates itself every 3 days and after a failed download. Audio no card uses is deleted. Adds about 30 MB to the APK (arm64-v8a + armeabi-v7a only).

## Files here

* `src/YouTubeAudioCache.kt`: the real cache (yt-dlp). The app now has a do-nothing class with the same shape at
  `app/src/main/java/com/cardplayer/link/YouTubeAudioCache.kt`; everything else (player, editor, card list,
  `YouTubeCacheStatus`, `MainActivity`'s start-up refresh) still calls it, so no other code needs to change.
* `src/SettingsScreen-youtube-block.kt.txt`: the Settings → Accounts block (keep-audio switch, space used,
  refresh / delete). Its strings (`settings_youtube_*`, both languages) are still in `res/values*/strings.xml`.
  `AppSettings.youtubeAutoCache` is still in `data/Models.kt`.

## Bringing it back

1. `app/build.gradle.kts`, inside `android { }`:
   ```kotlin
   defaultConfig {
       // yt-dlp (YouTube audio downloads) ships native Python per ABI; phones only, keeps the APK smaller.
       ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
   }
   flavorDimensions += "store"
   productFlavors {
       create("play") {
           dimension = "store"
           buildConfigField("boolean", "YT_DOWNLOAD", "false")
           buildConfigField("boolean", "PLUS_FREE", "false")
       }
       create("full") {
           dimension = "store"
           applicationId = "com.boopbox.full"
           versionNameSuffix = "-full"
           buildConfigField("boolean", "YT_DOWNLOAD", "true")
           buildConfigField("boolean", "PLUS_FREE", "true")
       }
   }
   packaging {
       // youtubedl-android runs its bundled Python / yt-dlp from the extracted native library folder.
       jniLibs { useLegacyPackaging = true }
   }
   buildFeatures { buildConfig = true }
   ```
   and in `dependencies { }`: `"fullImplementation"(libs.youtubedl.library)`.
   `gradle/libs.versions.toml`: `youtubedl = "0.18.1"` under `[versions]`,
   `youtubedl-library = { group = "io.github.junkfood02.youtubedl-android", name = "library", version.ref = "youtubedl" }` under `[libraries]`
   (check for a newer version first; yt-dlp itself updates on the phone).
2. Move the do-nothing `YouTubeAudioCache.kt` from `app/src/main/.../link/` to `app/src/play/java/com/cardplayer/link/`,
   and `src/YouTubeAudioCache.kt` from here to `app/src/full/java/com/cardplayer/link/`.
3. `ui/settings/SettingsScreen.kt`: paste the block back as the end of `accountsSection()`, preceded by
   `if (!BuildConfig.YT_DOWNLOAD) return`, and import `com.cardplayer.BuildConfig`.
4. `billing/Plus.kt`: in `init()`, `if (BuildConfig.PLUS_FREE) { owned.value = true; recompute(); return }` before the
   billing client is built; add `val forSale get() = !BuildConfig.PLUS_FREE` and return early from `buy`, `refresh`
   and `setPretend` when it is false. In `SettingsScreen`, hide `Section.PLUS` from the hub when `!Plus.forSale`.
5. `nfc/CardTag.kt`: `PACKAGES = listOf("com.boopboxapp", BuildConfig.APPLICATION_ID).distinct()`, so tags written by
   the sideload app also open it on phones where it is the only BoopBox (Android opens the first installed AAR and
   sends phones with none to the first one's Play page).
6. Tools: `release.ps1` → `assembleFullRelease`, `apk\full\release\app-full-release-unsigned.apk`;
   `release-aab.ps1` → `bundlePlayRelease`, `bundle\playRelease\app-play-release.aab`; `cardpush.py` →
   `BOOPBOX_PKG=com.boopbox.full`. Debug: `assembleFullDebug`, `apk/full/debug/app-full-debug.apk`, `testFullDebugUnitTest`.
7. Register `com.boopbox.full` in the Android Developer Console with the release key `%USERPROFILE%\boopbox-release.jks`
   (alias `boopbox`) so sideloaded APKs count as registered.

The sideload app keeps its own cards (separate app id): move them with Settings → Cards → Backup / Restore.

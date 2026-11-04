# BoopBox

A Yoto-style NFC audio player for Android. Tap a card on the phone and it plays
a song, album, audiobook, podcast or any audio file. The player screen is built
for toddlers (one big picture, one big button); the grown-up settings hide behind
a deliberate double tap.

## What it does

| Source | How it plays | Keeps BoopBox on screen? |
|---|---|---|
| Files on the phone (MP3, M4A, M4B, FLAC, OGG, OPUS, WAV, video files…) | Inside BoopBox (ExoPlayer) | Yes |
| A folder on the phone (an album / audiobook chapters) | Inside BoopBox, in natural name order (2 before 10), optional shuffle | Yes |
| Downloaded audiobook (one M4B / MP3 file, or a folder of chapter files) | Inside BoopBox, with the book's own chapters, cover and resume (see [Downloaded audiobooks](#downloaded-audiobooks)) | Yes |
| Podcast (paste an RSS feed) | Inside BoopBox. Newest episode, or a specific one. Whole feed is queued so the next episode follows. | Yes |
| Direct audio stream URL | Inside BoopBox | Yes |
| Spotify (track / album / playlist link) | Opens Spotify with a `:play` link so it starts immediately, then BoopBox comes back on top | Yes (best effort, see below) |
| YouTube Music (watch / playlist link) | Opens YouTube Music, then BoopBox comes back on top | Yes, but free YouTube Music stops when it is not on screen (Premium needed for background play) |
| YouTube (video link) | Opens YouTube | YouTube pauses when it is not on screen unless you have Premium, so by default it stays visible |
| Audible (book link) | Opens the book in Audible and sends a "play" media key after a moment | Yes, best effort. Audible has no auto-play link. If the book does not start, press play once in Audible; afterwards the card resumes it. |
| Any other app link | Opens the link | Best effort |
| Real Yoto card | Inside BoopBox (ExoPlayer), chapter icons as pictures. No sign-in needed. | Yes |

* **YouTube audio is not downloaded** (against Google Play policy): YouTube / YouTube Music cards always open the app, as in the table above. `link/YouTubeAudioCache.kt` is a do-nothing placeholder; the yt-dlp version (offline, background play inside BoopBox) is archived in [archive/full-flavor/](archive/full-flavor/README.md) with steps to bring it back as a sideload build.
* **Real Yoto cards just work**: a Yoto card's tag holds `https://yoto.io/<cardId>?<token>` (the token is the card's own access key). Tapping one adds it and plays: the link redirects to `share.yoto.co/p/<cardId>`, whose `__NEXT_DATA__` page data carries the chapters with signed track URLs (`YotoClient.card`). Name and cover come from Yoto on the first play. Tapping a Yoto card with BoopBox closed opens BoopBox. If the page route fails and the Yoto account is connected, the card id is tried against the API.
* **Any NFC thing is a card**: blank NTAG stickers, hotel key cards, Amiibo, transit cards, toys with tags. By default only the hardware UID is used; nothing is written to the card.
* **Share a card**: in the card editor, **Write to card** stores the card's definition on the tag itself (one NDEF record, MIME `application/vnd.cardplayer`, compact JSON). Anyone with BoopBox taps it and it is added to their phone and plays. Works for links, podcasts and streams (files on the phone can't travel). Needs a writable NDEF tag (NTAG213 fits a link; NTAG215/216 fit artwork too). Tapping such a card on the home screen launches BoopBox.
* **Quick share (.boop files)**: the share icon on a card (in the card list, or at the top of the card editor; **Send .boop** / **Save .boop** at the bottom of the editor do the same) packs the card into a zip (`card.json` = an inbox push with `lib:<id>/` paths, plus every phone-local song and picture it uses; folders become playlists) and opens the share sheet (WhatsApp, email, Drive, Quick Share…). The receiver just opens the file: BoopBox is registered for `.boop` files (and the generic / zip types chat apps give them), unpacks it, and shows **Shared with you · hold a card to the phone** on top of whatever was on screen. Tap a blank card and it plays. Tap a card already in use and BoopBox asks whether to replace what it plays. A link to a hosted `.boop` (`https://…/name.boop`, redirects followed) opens the same way. **Load a .boop** under Settings → Cards is still there for a file already on the phone (same queue as `tools/cardpush.py`; a shared card jumps to the front of it). Loading a .boop with the same id again updates that card.
* **Another tag**: in the card editor, **Move to another tag** puts the card onto a different NFC tag (sticker lost or worn out); **Copy to another tag** makes a second tag play the same thing.
* **Card list** (Settings → Cards): sorted by recently played, name or newest; a search box appears past six cards; each row says when it last played.
* **Backup** (Settings → Cards): one zip with every card, setting, resume position, the Yoto sign-in and `files/library/` (songs pushed from the computer or loaded from .boop files). **Restore backup** replaces this phone's cards and settings with it. Songs picked from the phone's own folders are not included.
* **Unknown card** → a "New card! Double-tap to set up" sheet. Known card → plays.
* **Same card again** → pause / resume (configurable).
* **Lift the card off** → stop, pause or keep playing, chosen per card.
* **Resume** per card ("carry on where it left off") for audiobooks and podcasts. Carrying on after a break replays a little first (5 s after a few minutes, 15 s after up to an hour, 30 s after longer).
* **Speed** per card (0.75× – 1.5×) for anything our own player plays; slower helps little ones follow a story.
* **Volume limit** for little ears.
* **Tapping a card while the app is closed** launches the app and plays it.
* Big play/pause and stop buttons also control Spotify / YouTube / Audible via media key events.

## Free vs BoopBox Plus

**BoopBox Plus** is a one-time Google Play in-app purchase (product id `boopbox_plus`, type *one-time product*; create it in Play Console → Monetize → In-app products before release). Code: `billing/Plus.kt` (Play Billing 8), sheet in `ui/PlusSheet.kt`, Settings → ⭐ BoopBox Plus (buy, restore purchase).

| Free | Plus |
|---|---|
| Up to 5 cards you set up yourself (`Plus.FREE_CARD_LIMIT`) | Unlimited cards |
| Every card already on the phone keeps playing, always (after a restore, a refund…) | |
| Cards that arrive ready-made: real Yoto cards, tags written by **Write to card**, opened `.boop` files | |
| Book files' own chapter marks | Split into songs / mark chapters by hand (split editor) |
| Manual sleep timer | Bedtime hours, per-card sleep timer |
| Night light in the default colour | Night light colours and brightness |
| Normal speed | Speed per card |
| Pictures per track, safety settings, backup, sharing | |

* The limit counts every card, but only blocks setting up a new one of your own: **Add a card**, the *New card* sheet, a tag tapped in settings, **Copy to another tag**, and links shared from other apps. Shared `.boop` cards always land.
* Plus features are also switched off at playback time (`Bedtime.isBedtime`, `PlayerController`), so a refund really turns them off; settings the grown-up picked are kept for when Plus comes back.
* Ownership is cached in shared prefs (not in backups or `.boop` files) and re-checked against Play on every connection. Purchases are acknowledged, or Play refunds them after 3 days.
* Debug builds show **Pretend Plus is bought** in the Plus section to try both versions without paying.

## Downloaded audiobooks

Card type **Audiobook on this phone** (card editor → *What it plays*). Choose a **book file** (M4B, M4A, MP3…) or a **book folder** (one file per chapter, sub-folders like `Disc 1` / `Disc 2` included).

* **Chapters from the file itself**: M4B / M4A chapter marks (the QuickTime chapter track that iTunes, Apple Books and most M4B tools write, or Nero `chpl` chapters from ffmpeg) and MP3 ID3 `CHAP` frames are read straight from the file, a few small reads, no decoding (`audio/Audiobook.kt`). Right / left on the player skip chapters, the dots or `5 / 23` show which one, the progress bar runs over the current chapter. A file without marks plays as one long track; **Mark chapters** opens the split editor to add cuts and names by hand, and **Use them** goes back to the file's own.
* **Folders** play in natural order ("Chapter 2" before "Chapter 10"), listed with one query per folder so books with hundreds of parts open quickly.
* **Fills itself in**: the title and author tags name the card (a folder uses the first file's album tag), and the cover comes from a picture in the folder (`cover.jpg`, `folder.png`…) or the file's embedded art, saved under `files/covers/` (included in backups and .boop files). Every chapter shows the book's cover unless it gets a picture of its own.
* **Remembers where it stopped** (on by default for books), with the replay-after-a-break above. The editor shows *Where it stopped* ("Chapter 5 of 23 (The Storm), 12:34 in") with **Start over**, and every chapter row has a ▶ to play from there.
* **Speed** per card, shuffle is off for books.
* Copy-protected books (Audible, Apple Books, Libby) can only play in their own apps; use the *Audiobook (Audible)* card type for Audible. DRM-free downloads (Libro.fm, Downpour, LibriVox, your own rips) work here.
* An older *File on this phone* card holding an M4B shows "This file has N chapter marks of its own" with **Use them**; switching a File / Folder card to *Audiobook* keeps the chosen file.
* `tools/cardpush.py` manifests take `"audiobook": true` (cover on every part, resume on) and `"speed"`.

## Toddler vs grown-up

* The player screen is full-screen (system bars hidden) and the back button does nothing. The whole screen is the controller:

  | Where | Tap | Hold |
  |---|---|---|
  | Middle (the picture) | play / pause | stop (a ring fills round the picture while you hold) |
  | Top | louder | |
  | Bottom | quieter | |
  | Right | next track | |
  | Left | previous track (never "restart this one") | |

  Volume taps respect the grown-up volume limit, and so do the phone's own volume keys (on the player screen they draw BoopBox's volume bars instead of the system slider).
* **Drag to scrub**: slide one finger sideways anywhere on the player to move through the current song (right = forward, left = back, in every language). Nothing is heard while dragging: a read-out shows the jump (`+0:42`), the target time and the progress bar; the song jumps when the finger lifts. While dragging, slide **up** for *Fast* (the whole song, or at least 4× normal, across the screen), stay level for *Normal* (the whole song across the screen, at most 10 min), slide **down** for *Fine* (a sixth of normal); a buzz marks each change. Toddler guards: the finger must land on its own, outside the edge strip, and travel 40 dp sideways (24 off, 64 strong) at a flat angle before anything happens; a second finger landing, or a resting finger sliding, drops it; it only jumps if the finger has settled when it lifts (a swipe or flick does nothing; under 500 dp/s, 250 strong); sliding back to the start does nothing. Within a file split into songs or a video with chapters it moves inside the current song. Other apps (Spotify, YouTube) need notification access. Off with **Only the picture works**, or on its own in Settings → Playback.
* **Grip guard** (Settings → Safety; Off / Normal / Strong) keeps a toddler who is only holding the phone from pressing anything. Fingers that land in a strip round the screen edges (28 dp normal, 56 dp strong) are ignored. A tap only counts if the finger lifts quickly (0.5 s normal, 0.35 s strong) without sliding. Fingers that land together (a palm, a grab) cancel each other. Holding an edge area does nothing, and stopping needs one finger held on the picture for 1.5 s (2.5 s strong). Skips are rate-limited. Each finger is judged separately, so a resting thumb does not block a real tap. **Only the picture works** turns the four edge areas off entirely.
* **Pixel art**: every track gets its own picture in the middle. Real artwork (album art, podcast episode art, embedded MP3 art, Spotify/YouTube thumbnails) is drawn as chunky pixel art; tracks without artwork get a generated 8×8 pixel creature unique to that track, in the card's colour. The card editor has **Find a picture** with two sources: **Pixel icons** from [yotoicons.com](https://yotoicons.com) (the community library of 16×16 icons drawn for Yoto cards; queried as `/icons?tag=<word>&page=N`, images at `/static/uploads/<id>.png`, artist credited on the card) and **Album art** via iTunes Search, and **Paste a link** for any picture on the web. Folder and podcast cards also get a **picture per track**. Pixel art rendering can be switched off in settings.
* **Songs inside one file or video**:
  * **File on this phone → Split into songs**: decodes the file once on the phone into a loudness timeline, then shows a waveform with live split lines while you adjust *silence threshold* (default −40 dB), *minimum silence* (1.0 s) and *minimum song length* (10 s). Tap the wave to listen at that spot, "Split here" to add a cut by hand, × to remove one, rename songs. Right/left skip between the songs; each gets its own picture.
  * **YouTube / YT Music link**: the watch page is scanned for chapter markers or a `0:00 Song` tracklist in the description; skipping seeks the other app's media session (needs notification access).
* **Nothing autoplays after the end**: our own player stops at the end of a file / folder / feed. For a Spotify or YouTube Music single-song card, with notification access on, BoopBox pauses the other app as soon as it rolls on to a different song.
* **Child lock**: pins BoopBox to the screen (Android screen pinning), so Home / Back / Recents do nothing. Android asks to confirm the first pin. Spotify / YouTube cards unpin while the other app opens; "Unpin now" lives in grown-up settings.
* **Card lifted off = stop / pause / keep playing**, set per card in the card editor (the Playback setting is the default for new cards), with a grace period (3 s default, up to 30 s in Settings → Playback) to put it back.
* **Sleep timer**: 15 / 30 / 45 / 60 min or "after this song"; fades out over the last 20 s, then pauses. The toddler sees a 🌙 countdown. Also reachable from the mini-player bar at the bottom of every grown-up screen.
* **Per-card sleep timer**: a bedtime-story card can start its own timer (15–60 min) every time it is tapped, set in the card editor.
* **Bedtime hours**: a lower volume limit, an automatic sleep timer on every tap, and the night light, between two times you set (a clock picker; the night light also shades the picture, not only the chrome).
* **Night light**: the whole player dims to a soft colour (5 colours, brightness slider), on demand or only at bedtime. Idle screen shows a dimmed clock and "Night night".
* **Tap flash**: every zone tap tints the whole screen for a moment (warm = louder, blue = quieter, teal = skip, white = play/pause).
* **Buzz on every tap** (Settings → Look): a little vibration for zone taps, hold-to-stop and opening the gear.
* **Battery**: a small battery glyph with its percentage appears top-left of the player. Settings → Look picks when: off, only when the phone is under 20% or charging (default), or always.
* **Two card modes**, chosen per card: *continue where it left off* or *start from the beginning* when tapped again. Settings has the default for new cards.
* A faded gear sits in the top-right corner. **Double-tap** it to open the grown-up settings: card list, add a card, preferences. Until the first card exists the gear is fully visible and the idle screen says how to get in. Every grown-up screen has a **mini-player** bar at the bottom: what is playing, play / pause, stop, sleep timer.
* Setting up a new card from the "New card" sheet also needs a double tap (can be switched off in settings).

## Building

Requirements: JDK 17–23 (this repo points at `C:/Program Files/Java/jdk-22` in `gradle.properties`), the Android SDK (platform 36 + build-tools 36.0.0; `local.properties` points at `%LOCALAPPDATA%\Android\Sdk`).

```bash
./gradlew.bat assembleDebug
```

The APK ends up at `app/build/outputs/apk/debug/app-debug.apk`. Install with:

```bash
"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run. `tools/cardpush.py` talks to `com.boopboxapp` (`BOOPBOX_PKG` overrides it).

Unit tests (audiobook chapter reader against real M4B / MP3 fixtures in `app/src/test/resources/audiobook/`, folder order): `./gradlew.bat testDebugUnitTest`.

### Release build (for sharing)

The app id is `com.boopboxapp`, the Google Play app (the Kotlin sources still live in `com.cardplayer`; earlier installs were `com.cardplayer` and `com.boopbox`, separate apps with their own cards: move them with Settings → Cards → Backup / Restore). The release key is `%USERPROFILE%\boopbox-release.jks` (alias `boopbox`), the Play upload key. For an APK to hand out directly, from PowerShell:

```powershell
.\tools\release.ps1
```

It runs `assembleRelease`, aligns the APK for 16 KB pages, asks for the key password (never stored) and writes `BoopBox-release.apk` in the project root, printing the certificate's SHA-256, which must match the registered one. Raise `versionCode` in `app/build.gradle.kts` before each release, or phones that already have BoopBox refuse the update. Keep the `.jks` and its password backed up: without them no update can be uploaded as `com.boopboxapp` (Play can reset a lost upload key on request). A Play install and a directly installed APK of `com.boopboxapp` can't replace each other: Play re-signs its builds with Google's key.

The original key's password was lost on 2026-11-01, so `.\tools\new-key.ps1` made a new one at the same path (the old file is kept as `boopbox-release-old-<date>.jks`). APKs signed with the new key can't update installs from the old one (versionCode 2): back up cards in Settings → Cards → Backup, uninstall, install, restore.

For Google Play, `.\tools\release-aab.ps1` builds and signs `BoopBox-release.aab` with the same key (used as the Play upload key).

### Quirk on this PC

Windows AF_UNIX sockets fail here, which makes the JDK's NIO selector pipe (and therefore the Gradle daemon) crash with `Unable to establish loopback connection`. `gradle.properties` works around it by passing `-Djdk.net.unixdomain.tmpdir=C:/nonexistent_dir_for_uds_fallback`, which makes the JDK fall back to TCP loopback. If you build from a different machine you can remove that flag.

## Building cards on the computer (`tools/cardpush.py`)

Set up a card's songs, names and pictures on the PC, push it over USB, then just tap a card.

1. Describe the card in `cards/<id>.json` (see `cards/rotemama-medley.json`): name, emoji, colour, cover, and a list of
   tracks. A track is either a whole file or a `start`/`end` slice of the manifest's `source` file. Icons are
   `yoto:<id>` (yotoicons.com), an image URL, or a local image path.
2. `python tools/cardpush.py push cards/<id>.json`
   * cuts/encodes each track (ffmpeg), tags it, embeds its picture, copies everything into the app's private
     storage (`files/library/<id>/`, via `run-as`, so it needs a debug build), then pings the app.
   * **Not on a card yet** → it waits; the next unknown card tapped on the player screen gets it and plays.
   * **Already on a card** (same `id`) → that card is updated in place.
   * `--card <uid|name>` puts it straight onto an existing card.
3. Helpers: `list` (cards + waiting pushes on the phone), `icons "<word>"` (search yotoicons),
   `analyze <audio> [--transcribe]` (silences and a Whisper transcript to plan cut points; Hebrew-tuned model).

Needs ffmpeg, adb, and `pip install pillow mutagen` (`faster-whisper` for `--transcribe`). On Git Bash the tool sets
`MSYS_NO_PATHCONV=1` itself so adb paths are not mangled.

## First run on the phone

1. Allow notifications (for the playback notification). NFC must be on: if it is off, the player shows a banner (double-tap it to open the NFC switch) and Settings → Permissions flags it.
2. Add a card: **Add a card** → hold a card to the phone → choose what it plays → Save.

### How "come back to BoopBox" works

Android 10+ refuses to let an app that is no longer on screen start an activity (`BAL_BLOCK` in logcat), and Android 15/16 removed the old 10-second grace period. BoopBox uses three routes, picked automatically:

| Setting | Mechanism | Needs |
|---|---|---|
| Come back **straight away** (0 s) | Relaunches itself in the same instant it fires the deep link, while it is still the visible app | nothing |
| Come back **after N seconds** (default 1 s) | Shrinks into a picture-in-picture bubble before the other app opens (a visible PiP activity may relaunch itself), then expands back after the delay | nothing |
| Come back after N seconds, no PiP | Plain delayed relaunch | "Display over other apps" permission (button in settings). On Xiaomi/HyperOS also "Display pop-up windows while running in the background" (button in settings) |

## Project layout

```
app/src/main/java/com/cardplayer/
  MainActivity.kt          NFC reader mode, immersive mode, launches the UI
  MainViewModel.kt         screen state, card-tap routing
  BoopBoxApp.kt         wires repositories + player
  data/                    Card / settings models, JSON file store, repositories,
                           PendingCards (cards pushed from the PC) + InboxReceiver (adb ping)
  nfc/NfcReader.kt         reader mode for every tag tech, tag-removed callback
  audio/Audiobook.kt       audiobook chapter marks (MP4 chapter track / chpl, ID3 CHAP), tags, cover
  audio/SilenceScanner.kt  waveform + silence detection for splitting one file into songs
  util/MediaScan.kt        folder listing (natural order) and folder cover pictures
  podcast/RssParser.kt     RSS → episodes (OkHttp + XmlPullParser)
  link/LinkResolver.kt     detect Spotify / YT Music / YouTube / Audible, fetch title + artwork
                           (oEmbed / Open Graph), build auto-play intents
  playback/PlaybackService.kt   Media3 MediaSessionService + ExoPlayer (foreground playback)
  playback/PlayerController.kt  "play this card": local playback, external launch + return-to-front,
                                media key control, resume positions, volume cap
  ui/PlayerScreen.kt       toddler screen
  ui/settings/             grown-up settings, add-card scan, card editor
```

Data lives in three JSON files in the app's private storage (`cards.json`, `settings.json`, `positions.json`).

## Known limits

* Spotify albums/playlists opened via a `:play` link start in shuffle on free accounts (Spotify's rule, not ours).
* YouTube and free YouTube Music only play while their app is on screen. Turn off "come back to BoopBox" if you would rather the video stay up.
* Audible cannot be auto-played by link. BoopBox opens the book and sends a play key; it works once Audible has that book loaded in its player.
* Returning to the front after launching another app depends on the "Display over other apps" permission (Android's background-activity-start rule).
* No kiosk mode: a determined toddler can still press Home. Android's built-in **App pinning** (Settings → Security → App pinning) locks the phone to BoopBox if you need that.

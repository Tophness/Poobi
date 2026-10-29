# Poob TV Browser & Streamer

**Poob** is a fast, modern, and highly optimized web browser and media streamer designed specifically for Android TV, Google TV, and Android set-top boxes. It combines a powerful ad-blocking browser with a comprehensive stream scraping engine and P2P torrent streaming capabilities.

## Why use Poob?
Sick of Kodi addons having broken sources and alerting you when it errored out trying to process them?
Sick of every Android TV Browser being a piece of shit?
Sick of torrentio chewing through your TV's storage with a hidden cache you can't delete? Me too. So I did something about it.

The popup blocker on TV Bro and others doesn't work half the time.
Browsehere's works great, but it asks you if you want to open the popup every time.
Why in the fuck would I want to open it? Even if I did, streaming video sites will often hit you with 10 popups in a row, and you just have to say no 10 times to fullscreen the video.
It was closed source, so I made my own. Eventually I found out there was a lot of things to improve.
Most browser native video players don't work half the time. I did it properly.
None of them grab subtitles from the page, so if the mp4 stream doesn't have embedded subs, you're out of luck.
So I made it scrape those from the page and add them to the subs list.
Kodi pops up an alert every time a source fails to resolve. Mine brute forces through the entire list.
Any frustration you've had with these apps, I've probably fixed it. And if I haven't, post an issue.

## Key Features

*   **Integrated Stream Scraper:** Search for movies and TV shows across dozens of providers and resolvers (powered by the StreamScraper engine).
*   **Torrentio & P2P Streaming:** Built-in support for Torrentio and other P2P sources. Streams torrents directly to ExoPlayer with intelligent pre-buffering, seed sorting, and background peer connections.
*   **Smart Intro, Recap & Credits Skipping:** Powered by TheIntroDB. Automatically skips or presents a prompt countdown to skip intros, recaps, opening credits, and closing credits.
*   **Native Video Extractor (ExoPlayer):** Hardware-accelerated playback with resolution switching, adaptive HLS/DASH support, native YouTube DASH manifest trailer playback, and quadrant-based seeking.
*   **Trakt & TMDb Integration:** Two-way Trakt scrobbling and history sync, unread progress tracking, and rich metadata, cast details, and personalized recommendations via TMDb.
*   **Smart "Try All" Resolver:** Automatically brute-forces through all scraped sources to find a working high-quality link for you.
*   **Advanced Subtitle Scraping:** Automatic searching, batch downloading, and retention management from OpenSubtitles, SubDL, SubSource, and Addic7ed.
*   **Smart Virtual Cursor & Navigation:** Remote-optimized cursor with smooth acceleration, clickable link detection, and native D-pad selection mode.
*   **Ad & Popup Blocker:** Powered by the Brave AdBlock Rust engine. Supports custom EasyList rules and manual element blocking (Point-and-Block).
*   **Hardware Input & Key Remapping:** Physical keyboard and mouse support, custom cursor toggles, and customizable Menu button keybinds with modifier key detection.
*   **Visual Subtitle Waveform Synchronizer:** Integrated subtitle sync tool with real-time PCM audio waveform rendering. Align subtitle blocks visually, search for dialogue cues automatically, and loop audio segments (A-B repeat) for precise synchronization.
*   **New Episode Radar & Notifications:** Automatically checks for newly aired episodes of your favorited shows, displaying an interactive overlay with episode metadata, still images, and direct playback.
*   **Cast Spotlight & Favorite Actors Pool:** Dedicated actor profiles and filmography views with Movie/TV filtering. Build a favorite actor pool (auto-synced from your favorite shows or searched manually) and prioritize titles starring them across all feeds.
*   **Multi-Tiered Sorter Engine:** Dedicated priority sorters for Regular Streams, Torrents, Favorites, Recently Watched, and Discovery Feeds (sort by Resolution, Seeds, File Size, Favorite Actors, Rating, Release Date, etc.).
*   **Multilingual Support:** Fully localized in over 15 languages with automatic TMDb metadata localization, audio track language matching, and customizable date formats.
*   **Tab & Session Management:** Suspend background tabs to save memory and restore your entire session on launch.

## Streams Tab & Scraping Engine

Poob features a powerful scraping engine that searches for high-quality streams without the hassle of navigating ad-ridden websites.

* **Powered by StreamScraper:** The scraping engine uses the libraries from the cross-platform [StreamScraper](https://github.com/Tophness/StreamScraper) Python app.
* **Kodi Addon Heritage:** The scraping logic is built upon the solid foundation of popular Kodi addons like `gratisred`, `vidscr`, `free99`, `scrubsv2`, `thecrew`, and their various forks.
* **Extensible Sources:** You can easily add more sources by copying the `sources` folder from Kodi streaming addons into the `app/src/main/python/sources` directory. The app will automatically detect and utilize the new providers.
* **Smart Sorting:** Sources are automatically sorted by resolution, reliability, direct links, and hosts. Torrents sorted by seeders, file size, etc. and you can set your own ranked, tiered priorities.

## P2P & Torrents

The "Torrents" tab (powered by Torrentio) allows for high-bitrate streaming directly from the swarm.
*   **Local Torrent Engine:** Features a lightweight local server for sequential downloading and streaming.
*   **Pre-Buffering:** Configurable pre-buffering pieces to ensure smooth playback even on slower connections.
*   **Cache Management:** Intelligent cache cleaning modes (automatic on exit, manual, or based on retention days).

## Android TV & Launcher Integration

*   **Home Screen Channels:** Adds preview channels for your favorites and newly aired episodes directly to your Android TV launcher.
*   **Watch Next Channel:** Automatically publishes unfinished movies and episodes with live progress, resume positions, and 16:9 landscape artwork.
*   **Global Search:** Android TV system search queries search through your Poobi library, favorites, recently watched, and TMDb.

# Screenshots
<p align="center">
<img width="1920" height="1080" alt="Screenshot_20260618_061324" src="https://github.com/user-attachments/assets/c330f86e-6ddb-4111-a9eb-ae5d4b679eea" />
<img width="1920" height="1080" alt="Screenshot_20260614_235139" src="https://github.com/user-attachments/assets/39785ea5-b492-4d0d-8e27-0186baa7ae4d" />
<img width="1920" height="1080" alt="Screenshot_20260614_235049" src="https://github.com/user-attachments/assets/08419975-9de1-4bd4-8959-a66aace0d75f" />
<img width="1920" height="1080" alt="Screenshot_20260614_234003" src="https://github.com/user-attachments/assets/6eb8b3c3-dbcc-4373-a0c1-d48a623821d7" />
<img width="1920" height="1080" alt="Screenshot_20260614_234017" src="https://github.com/user-attachments/assets/e93ff9a1-d719-471a-aa0d-c2920216ca1e" />
<img width="1920" height="1080" alt="Screenshot_20260614_234037" src="https://github.com/user-attachments/assets/0acd6e5e-0fe9-443b-874b-a87b7689851f" />
<img width="1920" height="1080" alt="Screenshot_20260617_102246" src="https://github.com/user-attachments/assets/859988b9-75fb-4296-94dc-cf7049c950d6" />
<img width="1920" height="1080" alt="Screenshot_20260623_223346" src="https://github.com/user-attachments/assets/c8730593-f460-42be-87c6-cc8864a9eead" />
<img width="1920" height="1080" alt="Screenshot_20260614_235452" src="https://github.com/user-attachments/assets/95c28fb6-f325-4203-a41c-b631cd98c01a" />
<img width="1920" height="1080" alt="Screenshot_20260614_234732" src="https://github.com/user-attachments/assets/67d1998d-ddfc-4b6c-9000-ddc20d763ba6" />
<img width="1920" height="1080" alt="Screenshot_20260614_234947" src="https://github.com/user-attachments/assets/9811e1b5-7213-4025-b354-77a560724f64" />
<img width="1920" height="1080" alt="Screenshot_20260614_234856" src="https://github.com/user-attachments/assets/82323491-8040-487e-acbd-1257ff289764" />
<img width="1920" height="1080" alt="Screenshot_20260614_235014" src="https://github.com/user-attachments/assets/a999e9a0-8c8a-4300-a8ae-c2354a399c86" />
</p>

# Installation

## Choosing the Right APK

Before downloading, you need to know which architecture your Android TV uses:
*   **`Poobi-v4.3.1-app-arm64-release.apk`**: For modern 64-bit ARM devices (e.g., Nvidia Shield, newer high-end TVs).
*   **`Poobi-v4.3.1-app-armv7-release.apk`**: For most common budget boxes and sticks (e.g., Chromecast with Google TV, Fire TV Stick, Xiaomi Mi Box).
*   **`Poobi-v4.3.1-app-x86_64-release.apk`** or **`Poobi-v4.3.1-app-x86-release.apk`**: Rare x86 devices or desktop PCs using android.

**How to find your architecture:**
*   **Method 1 (Recommended):**
    1.  Go to **Settings** → **Device Preferences** → **About** (or **Settings** → **System** → **About** on Google TV).
    2.  Scroll down to **Kernel version**.
    3.  Check the text in that section:
        *   If it contains **`aarch64`**, use **`Poobi-v4.3.1-app-arm64-release.apk`**.
        *   If it contains **`armv7l`**, use **`Poobi-v4.3.1-app-armv7-release.apk`**.
*   **Method 2 (Trial & Error):** Try installing **`Poobi-v4.3.1-app-arm64-release.apk`** first. If you get an "App not installed" or "Package appears to be invalid" error, your device is 32-bit—install **`Poobi-v4.3.1-app-armv7-release.apk`** instead.
*   **Method 3 (ADB):** If you have ADB enabled, run: `adb shell getprop ro.product.cpu.abi`

## Method 1 — Install Directly on Your TV

1. Go to the [Releases page](https://github.com/Tophness/Poobi/releases)
2. Download the latest APK corresponding to your architecture (e.g., `Poobi-v4.3.1-app-arm64-release.apk`)
3. Transfer the APK to your Android TV using one of these methods:
   * USB drive
   * Send Files to TV
   * Cloud storage (Google Drive, etc.)
4. Open a file manager on your TV and install the APK

---

## Method 2 — Install via ADB

### 1. Download ADB Platform Tools

Download the Android Platform Tools for your system:
* **Windows:** [Platform Tools (Windows)](https://dl.google.com/android/repository/platform-tools-latest-windows.zip)
* **Linux:** [Platform Tools (Linux)](https://dl.google.com/android/repository/platform-tools-latest-linux.zip)

Extract the archive and copy your APK (e.g., `Poobi-v4.3.1-app-arm64-release.apk`) into the extracted `platform-tools` folder.

---

### 2. Find Your Android TV's IP Address

On most Android TVs: `Settings → Network & Internet → Your Wi-Fi Network`
Look for: `IP Address: 192.168.x.x`

---

### 3. Enable Developer Options & ADB Debugging

On your TV: `Settings → Device Preferences → About → Build`
Click **Build** repeatedly until Developer Options are enabled.
Then go to: `Settings → Developer Options`
Enable:
* USB Debugging
* Network Debugging / Wireless Debugging (if available)

---

### 4. Connect & Install
(Assuming app-arm64-debug.apk is the correct apk for your architecture)
#### Windows
```bat
adb connect <YourTVsIP>
adb install Poobi-v4.3.1-app-arm64-release.apk
```

#### Linux
```bash
./adb connect <YourTVsIP>
./adb install Poobi-v4.3.1-app-arm64-release.apk
```

## Settings & Configuration

Poob is highly customizable via the **Settings** panel:
*   **General:** Toggle Dark/Light themes, session restore modes, history retention limits, icon styles (Snapshots vs Favicons), and check for version updates.
*   **Language & Region:** Choose app interface language (15+ languages), date format styles (system, DMY, MDY, YMD), preferred audio track, and default torrent/subtitle search languages.
*   **Web & AdBlocker:** Silent popup blocking, clickjacking prevention, custom EasyList AdBlock rule subscription URLs, manual point-and-block element rules, and active filter list statistics.
*   **Player Preferences:** Configure Native Player video hijacking, error fallbacks, and audio track persistence.
*   **Binge Watching & Intro Skipping:** Configure skip actions for intros, recaps, opening credits, and closing credits (Skip, Ask to Skip, or Ignore). Set "Up Next" episode countdown overlay timings, source selection modes (Closest Source vs Best Quality), and episode focus styles.
*   **Interface & Hardware:** Default pointer modes, result tab ordering, hardware keyboard and mouse modes, custom cursor visibility, and custom Menu shortcut remapping (supports modifier combinations like Alt+Up).
*   **Feeds & Curation:** Customize card poster aspect ratios (Portrait 2:3 or Landscape 16:9), toggle genres, streaming platforms, and decade-based feeds. Manage favorite actor pools and auto-sync cast from favorite shows.
*   **Streaming & Scrapers:** Configure per-source and global engine timeouts, host whitelists, provider package toggles, and worker threads.
*   **Autoplay Profiles:** Create automated playback profiles using simple element selectors or custom JavaScript actions with URL pattern matching.
*   **Subtitles:** Choose between Native or Custom Subtitle overlay rendering, configure auto-download quantities, file retention limits, and manage API credentials for OpenSubtitles, SubDL, and SubSource.
*   **Sorting Priorities:** Drag-and-drop or rank-order your sorting priorities for Regular Streams, Torrents, Favorites, Recently Watched, and Discovery Feeds.
*   **Blocked Web Elements:** Create, edit, and toggle custom CSS element blocking rules across websites.
*   **Trakt & TMDb:** Authorize your Trakt.tv account via device code flow and configure TMDb credentials for watchlist, recommendation, and scrobble synchronization.
*   **Torrents:** Set playback pre-buffer sizes (1 to 8 pieces), configure preferred torrent audio languages, and manage cache cleanup intervals and directory storage.
*   **Local & Cloud Sync:** Export or restore all settings and configuration to an offline JSON file in your Downloads folder without a Google account, or sync across devices using your private Google Drive App Data folder.

# Tech Stack

*   **Kotlin** - 100% Kotlin codebase for the Android UI (Jetpack Compose) and core logic.
*   **Python (Chaquopy)** - Powering the cross-platform stream scraping, provider resolvers, and metadata engines.
*   **AndroidX Media3 (ExoPlayer)** - For robust, hardware-accelerated media playback.
*   **jlibtorrent (libtorrent)** - Lightweight, high-performance P2P engine for sequential torrent streaming.
*   **Brave AdBlock Rust Engine** - High-performance, low-memory network request and cosmetic element filtering.
*   **TheIntroDB API** - Real-time crowd-sourced media timestamps for intro, recap, and credit detection.
*   **TMDb & Trakt APIs** - Comprehensive media information, episode progress, and cloud watch history.
*   **Google Drive API** - Secure, private user data and settings synchronization across devices.

## License

Distributed under the MIT License. See `LICENSE` for more information.
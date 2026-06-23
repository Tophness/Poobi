# Poobi TV Browser & Streamer

**Poobi** is a fast, modern, and highly optimized web browser and media streamer designed specifically for Android TV, Google TV, and Android set-top boxes. It combines a powerful ad-blocking browser with a comprehensive stream scraping engine and P2P torrent streaming capabilities.

## Why use Poobi?
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
*   **Torrentio & P2P Streaming:** Built-in support for Torrentio and other P2P sources. Streams torrents directly to ExoPlayer with intelligent pre-buffering and seed-sorting.
*   **Trakt & TMDb Integration:** Sync your watch history and favorites with Trakt.tv. Get rich metadata, cast information, and personalized recommendations via TMDb.
*   **Smart "Try All" Resolver:** Automatically tests all scraped sources to find a working high-quality link for you.
*   **Google Drive Cloud Sync:** Private backup and synchronization of your settings, bookmarks, and history across multiple devices using your own Google Drive.
*   **Binge-Watching Mode:** "Up Next" overlay with countdown for TV shows. Automatically scrapes and plays the next episode based on your preference (Closest Source or Best Quality).
*   **Native Video Extractor (ExoPlayer):** Hardware-accelerated playback with resolution switching, subtitle selection (SRT, VTT, ASS), and quadrant-based seeking.
*   **Advanced Subtitle Support:** Automatic searching and batch-downloading from OpenSubtitles, SubDL, and more. Intelligent language detection and caching.
*   **Smart Virtual Cursor & Navigation:** Remote-optimized cursor with smooth acceleration, clickable link detection, and native D-pad selection mode.
*   **Ad & Popup Blocker:** Powered by the Brave AdBlock Rust engine. Supports custom EasyList rules and manual element blocking (Point-and-Block).
*   **Tab & Session Management:** Suspend background tabs to save memory and restore your entire session on launch.

## Streams Tab & Scraping Engine

Poobi features a powerful scraping engine that searches for high-quality streams without the hassle of navigating ad-ridden websites.

* **Powered by StreamScraper:** The scraping engine uses the libraries from the cross-platform [StreamScraper](https://github.com/Tophness/StreamScraper) Python app.
* **Kodi Addon Heritage:** The scraping logic is built upon the solid foundation of popular Kodi addons like `gratisred`, `vidscr`, `free99`, `scrubsv2`, `thecrew`, and their various forks.
* **Extensible Sources:** You can easily add more sources by copying the `sources` folder from Kodi streaming addons into the `app/src/main/python/sources` directory. The app will automatically detect and utilize the new providers.
* **Smart Sorting:** Sources are automatically sorted by resolution, reliability, etc. Torrents sorted by seeders, file size, etc. and you can set your own ranked, tiered priorities.

## P2P & Torrents

The "Torrents" tab (powered by Torrentio) allows for high-bitrate streaming directly from the swarm.
*   **Local Torrent Engine:** Features a lightweight local server for sequential downloading and streaming.
*   **Pre-Buffering:** Configurable pre-buffering pieces to ensure stable playback even on slower connections.
*   **Cache Management:** Intelligent cache cleaning modes (automatic on exit, manual, or based on retention days).

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

## Method 1 — Install Directly on Your TV

1. Go to the [Releases page](https://github.com/Tophness/Poobi/releases)
2. Download the latest `app-debug.apk`
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

Extract the archive and copy `app-debug.apk` into the extracted `platform-tools` folder.

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

#### Windows
```bat
adb connect <YourTVsIP>
adb install app-debug.apk
```

#### Linux
```bash
./adb connect <YourTVsIP>
./adb install app-debug.apk
```

## Settings & Configuration

Poobi is highly customizable via the **Settings** tab:
*   **Appearance:** Toggle Dark/Light themes and Snapshot vs Favicon icon styles.
*   **Web & Content:** Configure silent popup blocking and Clickjack prevention. Manual point-and-block element selection.
*   **Player:** Configure Native Player hijacking, fallback preferences, and embedded subtitle toggles.
*   **Binge Watching:** Setup "Up Next" overlay timing and autoplay source logic.
*   **Subtitles:** Configure auto-search, preferred languages, retention limits, and API keys for OpenSubtitles/SubDL.
*   **Streaming & Sorting:** Manage provider packs, engine timeouts, and result sorting priorities (Resolution, Seeders, Size, etc.).
*   **Integration:** Link your **Trakt.tv** and **TMDb** accounts for personal library sync and metadata.
*   **Cloud Sync:** Authorize **Google Drive** for cross-device synchronization.

# Tech Stack

*   **Kotlin** - 100% Kotlin codebase for the Android UI (Jetpack Compose) and core logic.
*   **Python (Chaquopy)** - Powering the cross-platform stream scraping and metadata engines.
*   **AndroidX Media3 (ExoPlayer)** - For robust, hardware-accelerated media playback.
*   **Brave AdBlock Rust Engine** - High-performance, low-memory network filtering.
*   **TMDb & Trakt APIs** - For rich media information and cloud watch history.
*   **Google Drive API** - For secure, private user data synchronization.

## License

Distributed under the MIT License. See `LICENSE` for more information.

# Poobi TV Browser & Streamer

**Poobi** is a fast, modern, and highly optimized web browser and media streamer designed specifically for Android TV, Google TV, and Android set-top boxes. It combines a powerful ad-blocking browser with a comprehensive stream scraping engine.

## Why use Poobi?
Sick of Kodi addons having broken sources and a bunch of esoteric code that's hard to debug when they break?
Sick of every Android TV Browser having the exact same flaws? Me too. So I did something about it.

The popup blocker on TV Bro and others doesn't work half the time.

Browsehere's works great, but it asks you if you want to open the popup every time.

Why in the fuck would I want to open it? Even if I did, streaming video sites will often hit you with 10 popups in a row, and you just have to say no 10 times to fullscreen the video.

It was closed source, so I made my own. Eventually I found out there was a lot of things to improve. Poobi is now more than just a browser—it's a complete media hub.

Most browser native video players don't work half the time. I did it properly.

None of them grab subtitles from the page, so if the mp4 stream doesn't have embedded subs, you're out of luck. So I made it scrape those from the page and add them to the subs list.

## Key Features

* **Integrated Stream Scraper:** A dedicated "Streams" tab allows you to search for movies and TV shows across dozens of providers.
* **Smart Popup & Clickjack Blocking:** No more stupid "Do you want to open this popup" questions. Popups and transparent click-hijacking overlays are neutralized automatically.
* **Auto-Resume Support:** Remembers where you left off. If you close a video and come back later, Poobi will offer to resume from your last position.
* **Intuitive interface:** Hold the ok button to close tabs, Press the menu button to bring down the navigation bar.
* **Smart Virtual Cursor:** The cursor accelerates smoothly, detects clickable links, and intelligently scrolls the page when reaching the screen edges.
* **Native Video Extractor (ExoPlayer):** Automatically detects web videos (HLS/m3u8, MP4, MKV) and extracts them into a hardware-accelerated, full-screen ExoPlayer native view.
* **Advanced Subtitle Support:** Automatically grabs `.srt`, `.vtt`, and `.ass` subtitle files from websites and external providers. Intelligently identifies languages from filenames and feeds them to the player.
* **Smooth Navigation:** Quadrants-based seeking in the player (gets faster the longer you hold it) and smooth remote-button scrolling for long pages.
* **Built-in Ad & Popup Blocker:** Powered by a native Rust port of Brave's AdBlock engine. Supports EasyList and AdGuard DNS rules.
* **Tab Management:** Fully featured tabbed browsing. Suspend tabs in the background, restore previous sessions on launch, and navigate them effortlessly from the home screen.
* **Download Manager:** Built-in download manager with a dedicated UI. Safely download files, track progress, and easily install `.apk` files.

## Streams Tab & Scraping Engine

Poobi features a powerful scraping engine that searches for high-quality streams without the hassle of navigating ad-ridden websites.

* **Powered by StreamScraper:** The scraping engine uses the libraries from the cross-platform [StreamScraper](https://github.com/Tophness/StreamScraper) Python app.
* **Kodi Addon Heritage:** The scraping logic is built upon the solid foundation of popular Kodi addons like `gratisred`, `vidscr`, `free99`, `scrubsv2`, `thecrew`, and their various forks.
* **Extensible Sources:** You can easily add more sources by copying the `sources` folder from Kodi streaming addons into the `app/src/main/python/sources` directory. The app will automatically detect and utilize the new providers.
* **Smart Sorting:** Sources are automatically sorted by quality and reliability, giving you the best experience with minimal effort.

# Screenshots
<p align="center">
<img width="1920" height="1080" alt="Screenshot_20260614_235139" src="https://github.com/user-attachments/assets/39785ea5-b492-4d0d-8e27-0186baa7ae4d" />
<img width="1920" height="1080" alt="Screenshot_20260614_235049" src="https://github.com/user-attachments/assets/08419975-9de1-4bd4-8959-a66aace0d75f" />
<img width="1920" height="1080" alt="Screenshot_20260614_234003" src="https://github.com/user-attachments/assets/6eb8b3c3-dbcc-4373-a0c1-d48a623821d7" />
<img width="1920" height="1080" alt="Screenshot_20260614_234017" src="https://github.com/user-attachments/assets/e93ff9a1-d719-471a-aa0d-c2920216ca1e" />
<img width="1920" height="1080" alt="Screenshot_20260614_234037" src="https://github.com/user-attachments/assets/0acd6e5e-0fe9-443b-874b-a87b7689851f" />
<img width="1920" height="1080" alt="Screenshot_20260617_090805" src="https://github.com/user-attachments/assets/96d1a3a5-643c-408c-b415-fc85b4305f93" />
<img width="1920" height="1080" alt="Screenshot_20260617_091036" src="https://github.com/user-attachments/assets/b56f687b-becd-476b-ae4e-7c0a05c005d1" />
<img width="1920" height="1080" alt="Screenshot_20260614_235452" src="https://github.com/user-attachments/assets/95c28fb6-f325-4203-a41c-b631cd98c01a" />
<img width="1920" height="1080" alt="Screenshot_20260614_234732" src="https://github.com/user-attachments/assets/67d1998d-ddfc-4b6c-9000-ddc20d763ba6" />
<img width="1920" height="1080" alt="Screenshot_20260614_234818" src="https://github.com/user-attachments/assets/ddd5bdf3-92ce-4c2a-baf7-52aae4b037ac" />
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
   * Cloud storage
4. Open a file manager on your TV and install the APK

---

## Method 2 — Install via ADB

### 1. Download ADB Platform Tools

Download the Android Platform Tools for your system:

* **Windows:**
  [Platform Tools (Windows)](https://dl.google.com/android/repository/platform-tools-latest-windows.zip)

* **Linux:**
  [Platform Tools (Linux)](https://dl.google.com/android/repository/platform-tools-latest-linux.zip)

Extract the archive and copy `app-debug.apk` into the extracted `platform-tools` folder.

---

### 2. Find Your Android TV's IP Address

On most Android TVs:

```text
Settings → Network & Internet → Your Wi-Fi Network
```

Look for:

```text
IP Address: 192.168.x.x
```

---

### 3. Enable Developer Options & ADB Debugging

On your TV:

```text
Settings → Device Preferences → About → Build
```

Click **Build** repeatedly until Developer Options are enabled.

Then go to:

```text
Settings → Developer Options
```

Enable:

* USB Debugging
* Network Debugging / Wireless Debugging (if available)

---

### 4. Connect & Install

#### Windows

Open Command Prompt inside the `platform-tools` folder and run:

```bat
adb connect <YourTVsIP>
adb install app-debug.apk
```

Example:

```bat
adb connect 192.168.1.45
adb install app-debug.apk
```

---

#### Linux

Open a terminal inside the `platform-tools` folder and run:

```bash
./adb connect <YourTVsIP>
./adb install app-debug.apk
```

Example:

```bash
./adb connect 192.168.1.45
./adb install app-debug.apk
```

If prompted on your TV, allow the ADB connection.

## Settings & Configuration

Poobi is highly customizable. From the home screen, click the **Settings** tab to access:
* **Appearance:** Toggle Dark/Light themes. Toggle thumbnail vs favicon styles for bookmarks.
* **Web & Content:** Configure silent popup blocking and Clickjack prevention.
* **Player:** Choose whether to always use the Native Player or ask on a per-video basis. Toggle embedded subtitles.
* **Session Storage:** Configure tab restoration preferences and history limits.
* **Streams:** Manage scraper behavior and external provider settings.

# Tech Stack

* **Kotlin** - 100% Kotlin codebase for the Android UI and core logic.
* **Python (Chaquopy)** - Powering the cross-platform stream scraping engine.
* **AndroidX Media3 (ExoPlayer)** - For robust, hardware-accelerated media playback.
* **Brave AdBlock Rust Engine** - For high-performance, low-memory network filtering.
* **Android WebKit** - Utilizes an optimized Chrome engine under the hood for maximum compatibility.

## License

Distributed under the MIT License. See `LICENSE` for more information.

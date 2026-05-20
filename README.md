# Poobi TV Browser

**Poobi** is a fast, modern, and highly optimized web browser designed specifically for Android TV, Google TV, and Android set-top boxes.

## Why use Poobi?
Sick and tired of every Android TV Browser having the exact same flaws? Me too. So I did something about it.
The popup blocker on TV Bro and others doesn't work half the time.
Browsehere's works great, but it asks you if you want to open the popup every time.
Why in the fuck would I want to open it? Even if I did, streaming video sites will often hit you with 10 popups in a row, and you just have to say no 10 times to fullscreen the video.
It was closed source, so I made my own.
Eventually I found out there was a lot of things to improve.
Most of these browser's native video players don't work half the time. I did it properly.
None of them grab subtitles from the page, so if the mp4 stream doesn't have embedded subs, you're out of luck. So I made it scrape those from the page and add them to the subs list.

## Key Features

* **Smart Popup Blocking:** No more stupid "Do you want to open this popup" questions. Popups are literally blocked by default.
* * **Intuitive interface:** Hold the ok button to close tabs, Press the menu button to bring down the navigation bar.
* **Smart Virtual Cursor:** The cursor accelerates smoothly, detects clickable links, and intelligently scrolls the page when reaching the screen edges.
* **Native Video Extractor (ExoPlayer):** Automatically detects web videos (HLS/m3u8, MP4, MKV) and extracts them into a hardware-accelerated, full-screen ExoPlayer native view. Say goodbye to clunky web video players.
* **Subtitle Interception:** Automatically grabs `.srt`, `.vtt`, and `.ass` subtitle files from websites and feeds them directly into the native ExoPlayer.
* **Built-in Ad & Popup Blocker:** Powered by a native Rust port of Brave's AdBlock engine. Supports EasyList and AdGuard DNS rules to keep your browsing lightning fast and ad-free.
* **Tab Management:** Fully featured tabbed browsing. Suspend tabs in the background, restore previous sessions on launch, and navigate them effortlessly from the home screen.
* **Download Manager:** Built-in download manager with a dedicated UI. Safely download files, track progress, and easily install `.apk` files directly from the browser.
* **Context Menu:** Long-press the OK button on links to open a custom context menu (Open in New Tab, Block Element, etc.).

## Screenshots
<img width="1920" height="1080" alt="Screenshot_20260521_020701" src="https://github.com/user-attachments/assets/4c07503f-96d3-4342-80a7-9fc3dc0e65f8" />
<img width="1920" height="1080" alt="Screenshot_20260521_020746" src="https://github.com/user-attachments/assets/7755438c-de8e-45b5-bf30-f5412944b83a" />
<img width="1920" height="1080" alt="Screenshot_20260521_020829" src="https://github.com/user-attachments/assets/e2a2769e-9705-4738-bba6-aee6acc2d14f" />
<img width="1920" height="1080" alt="Screenshot_20260521_020856" src="https://github.com/user-attachments/assets/ec8a8b5b-25d6-4112-a831-e06b5172c3fa" />
<img width="1920" height="1080" alt="Screenshot_20260521_020922" src="https://github.com/user-attachments/assets/e7e659fe-c88c-4bff-bbdd-b8cd70f51649" />
<img width="1920" height="1080" alt="Screenshot_20260521_020947" src="https://github.com/user-attachments/assets/d6df5356-3665-457c-811b-9e8c36be4b12" />
<img width="1920" height="1080" alt="Screenshot_20260521_021149" src="https://github.com/user-attachments/assets/4033d42b-96d0-4ea7-8832-35b802878749" />
<img width="1920" height="1080" alt="Screenshot_20260521_021245" src="https://github.com/user-attachments/assets/b51a91a8-eca6-4cde-9950-e6d124f6fee7" />
<img width="1920" height="1080" alt="Screenshot_20260521_020559" src="https://github.com/user-attachments/assets/fb735f1e-6f9d-4561-ac55-381ab4ac10bc" />
<img width="1920" height="1080" alt="Screenshot_20260521_021612" src="https://github.com/user-attachments/assets/1ad0ed94-6697-4d06-ab50-adf5f1aa2a88" />


## Tech Stack

* **Kotlin** - 100% Kotlin codebase.
* **AndroidX Media3 (ExoPlayer)** - For robust, hardware-accelerated media playback.
* **Brave AdBlock Rust Engine** - For high-performance, low-memory network filtering.
* **Android WebKit** - Utilizes an optimized Chome engine under the hood for maximum compatibility.

## Installation

1. Go to the [Releases](https://github.com/Tophness/Poobi/releases) page.
2. Download the latest `app-release.apk`.
3. Transfer the APK to your Android TV (via USB, Send Files to TV, etc.).
4. Open a File Manager on your TV and install the APK.

## Settings & Configuration

Poobi is highly customizable. From the home screen, click the **Settings** tab to access:
* **Appearance:** Toggle Dark/Light themes.
* **Web & Content:** Configure silent popup blocking and set custom EasyList AdBlock URLs.
* **Player:** Choose whether to always use the Native Player or ask on a per-video basis. Toggle embedded subtitles.
* **Session Storage:** Configure tab restoration preferences and history limits.

## License

Distributed under the MIT License. See `LICENSE` for more information.

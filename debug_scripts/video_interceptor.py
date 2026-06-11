import json
import time
import os
import requests
from urllib.parse import urlparse
from seleniumbase import Driver

# Added the goodstream URL for testing
URLS = [
    "https://bysekoze.com/d/mp8q1ugrp39q/Rick.And.Morty.S09E03.WEB.H264-RBB.mp4",
    "https://goodstream.cc/streamsvr/AEYiTfzVZc/1-34?e=NldTSzFNLzVlOGJZWlBwYkhZS1ZmV3U2VFlJanlSVVplQVNMRTN4MXpmRT0U"
]

def wait_and_close_popups(driver, main_handle, duration=20):
    """
    Waits while closing any popups or new tabs that open during the process.
    """
    print(f"Waiting {duration} seconds to gather background network requests...")
    start_time = time.time()
    
    while time.time() - start_time < duration:
        try:
            # Block new windows
            driver.execute_script("window.open = function() { return null; };")
        except: pass

        try:
            handles = driver.window_handles
            if len(handles) > 1:
                for handle in handles:
                    if handle != main_handle:
                        driver.switch_to.window(handle)
                        driver.close()
                driver.switch_to.window(main_handle)
        except: pass
        time.sleep(1)

def sanitize_filename(name):
    return "".join(c for c in name if c.isalnum() or c in "._-")

def save_manifest(base_url, stream_url, content, headers):
    target_path = urlparse(base_url).path
    base_name = os.path.basename(target_path).replace(".mp4", "") or "stream"

    stream_file = os.path.basename(urlparse(stream_url).path) or "index"
    if not stream_file.endswith((".m3u8", ".ts", ".mp4")):
        stream_file += ".m3u8"

    filename = sanitize_filename(f"{base_name}_{stream_file}")
    if len(content) < 5000000: # Don't save if it's a huge TS file to avoid disk bloat
        with open(filename, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"SUCCESS: Saved to {filename}")
    else:
        print(f"SUCCESS: Stream reachable (Size: {len(content)} bytes), but file too large to save as text.")

def debug_url(driver, target_url):
    print(f"\n{'='*50}\nTesting URL: {target_url}\n{'='*50}")

    driver.get(target_url)
    main_handle = driver.current_window_handle

    # Check if the page ITSELF is an M3U8 (Goodstream case)
    page_text = driver.execute_script("return document.documentElement.innerText;")
    if "#EXTM3U" in page_text:
        print("[DETECTED] Page is a raw M3U8 manifest!")
        # We can treat the page URL as the stream URL
        headers = {
            "User-Agent": driver.execute_script("return navigator.userAgent;"),
            "Referer": target_url
        }
        save_manifest(target_url, target_url, page_text, headers)
        # Try to fetch the first segment if it exists
        lines = page_text.splitlines()
        for line in lines:
            if line.startswith("http"):
                print(f"Testing first segment: {line}")
                r = requests.get(line, headers=headers, timeout=10)
                print(f"Segment Response: {r.status_code}")
                break
        return

    print("Page opened. Please click Play if needed.")
    wait_and_close_popups(driver, main_handle, duration=15)

    found_streams = []
    try:
        logs = driver.get_log("performance")
        for entry in logs:
            log = json.loads(entry["message"])["message"]
            if log.get("method") == "Network.requestWillBeSent":
                params = log.get("params", {})
                request = params.get("request", {})
                req_url = request.get("url", "")

                is_stream = False
                if any(x in req_url.lower() for x in [".m3u8", ".mpd", "master", "index-v", "playlist.m3u8"]):
                    is_stream = True

                if is_stream and req_url not in [r['url'] for r in found_streams]:
                    found_streams.append({'url': req_url, 'headers': request.get("headers", {})})
    except Exception as e:
        print(f"Error: {e}")

    for entry in found_streams:
        print(f"\n[STREAM FOUND] {entry['url']}")
        clean_headers = {k: v for k, v in entry['headers'].items() if not k.startswith(':')}
        try:
            resp = requests.get(entry['url'], headers=clean_headers, timeout=10)
            if resp.status_code == 200:
                save_manifest(target_url, entry['url'], resp.text, clean_headers)
            else:
                print(f"FAILED: {resp.status_code}")
        except Exception as e:
            print(f"Request Error: {e}")

if __name__ == "__main__":
    # Ensure performance logging is enabled for interception
    driver = Driver(uc=True, log_cdp=True)
    try:
        for url in URLS:
            debug_url(driver, url)
            if url != URLS[-1]: input("\nPress Enter for next URL...")
    finally:
        driver.quit()

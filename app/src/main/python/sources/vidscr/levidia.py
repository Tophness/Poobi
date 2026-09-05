# -*- coding: utf-8 -*-
import re
import requests
from urllib.parse import quote_plus, urljoin

BASE = 'https://www.levidia.ch'
TIMEOUT = 10
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'

class source:
    def __init__(self):
        self.results = []
        self.domains = ['levidia.ch']

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return f"{title}|{year}" if title else None

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return f"{title}|{year}" if title else None

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url: return None
        return f"{url}|{season}|{episode}"

    def sources(self, url, hostDict):
        if not url: return []
        parts = url.split('|')
        title = parts[0]
        year = parts[1] if len(parts) > 1 else ''
        season = parts[2] if len(parts) > 2 else None
        episode = parts[3] if len(parts) > 3 else None

        try:
            sess = requests.Session()
            sess.headers.update({'User-Agent': UA, 'Referer': BASE + '/'})

            # Warmup to obtain site session cookies
            r_home = sess.get(BASE, timeout=TIMEOUT)
            search_url = f"{BASE}/search.php?q={quote_plus(title)}"
            r_search = sess.get(search_url, timeout=TIMEOUT)
            if not r_search.ok: return []

            # Parse search match
            matches = re.findall(r'<div class="mainlink">\s*<a href="([^"]+)">([^<]+)</a>', r_search.text)
            target_slug = None
            clean_title = re.sub(r'[^a-zA-Z0-9]', '', title.lower())
            for href, name in matches:
                if clean_title in re.sub(r'[^a-zA-Z0-9]', '', name.lower()):
                    target_slug = href
                    break

            if not target_slug: return []
            detail_url = urljoin(BASE, target_slug)

            if season and episode:
                r_show = sess.get(detail_url, timeout=TIMEOUT)
                ep_pattern = f"s{season}e{episode}"
                ep_match = re.search(r'href="([^"]*' + ep_pattern + r'[^"]*)"', r_show.text, re.I)
                if not ep_match: return []
                detail_url = urljoin(BASE, ep_match.group(1))

            r_detail = sess.get(detail_url, timeout=TIMEOUT)
            links = re.findall(r'<a[^>]+target="_blank"[^>]+href="([^"]+)"', r_detail.text)

            for link in links:
                if 'imdb' in link: continue
                # Unwind redirector to direct stream
                try:
                    r_head = sess.get(link, timeout=6, allow_redirects=True, stream=True)
                    final_url = r_head.url
                    r_head.close()
                    if '.m3u8' in final_url or '.mp4' in final_url or 'wootly' in final_url:
                        self.results.append({
                            'source': 'Levidia',
                            'quality': '720p',
                            'url': f"{final_url}|User-Agent={UA}&Referer={BASE}/",
                            'direct': True if ('.m3u8' in final_url or '.mp4' in final_url) else False,
                            'info': 'HLS' if '.m3u8' in final_url else 'MP4'
                        })
                except: pass
        except: pass
        return self.results

    def resolve(self, url):
        return url
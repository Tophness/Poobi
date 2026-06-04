# -*- coding: utf-8 -*-
import re
import requests
from urllib.parse import urlparse

class source:
    def __init__(self):
        self.results = []
        self.domains = ['vidsrc.me', 'vidsrc.in', 'vidsrc.to', 'vidsrc.net', 'vidsrc.xyz', 'vidsrcme.ru', 'vidsrc.stream', 'vidsrc.icu']
        self.base_link = 'https://v2.vidsrc.me'
        self.ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return imdb if imdb else str(tmdb)

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return imdb if imdb else str(tmdb)

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        return f"{url}/{season}/{episode}"

    def sources(self, url, hostDict):
        if not url: return []
        try:
            content = 'movie' if '/' not in url else 'tv'
            embed_url = f"{self.base_link}/embed/{content}/{url}"
            s = requests.Session()
            s.headers.update({'User-Agent': self.ua, 'Referer': self.base_link})
            
            r = s.get(embed_url, timeout=10).text
            rcp_links = re.findall(r'src=["\'](//cloudnestra\.com/rcp/[^"\']+)["\']', r)
            rcp_links += re.findall(r'src=["\'](https?://cloudnestra\.com/rcp/[^"\']+)["\']', r)
            
            # Also try vidsrc.stream / new variants with data-h pattern
            data_h = re.findall(r'data-h=["\']([^"\']+)["\']', r)
            if data_h:
                # This would need XOR decoding logic from original vidsrc.py
                # For now we'll stick to rcp links which are most common
                pass

            for rcp in rcp_links:
                if rcp.startswith('//'): rcp = 'https:' + rcp
                try:
                    self._resolve_cloudnestra(rcp, s, embed_url)
                except: pass
        except: pass
        return self.results

    def _resolve_cloudnestra(self, rcp_url, sess, referer):
        r = sess.get(rcp_url, headers={'Referer': referer}, timeout=10).text
        next_url = re.search(r'src=["\']([^"\']+/prorcp/[^"\']+)["\']', r)
        if next_url:
            n_url = next_url.group(1)
            if n_url.startswith('//'): n_url = 'https:' + n_url
            r2 = sess.get(n_url, headers={'Referer': rcp_url}, timeout=10).text
            
            files = re.findall(r'file\s*:\s*["\']([^"\']+)["\']', r2)
            for f in files:
                if '.m3u8' in f or '.mp4' in f:
                    parsed = urlparse(n_url)
                    origin = f"{parsed.scheme}://{parsed.netloc}"
                    final_url = f"{f}|Origin={origin}&Referer={origin}/&User-Agent={self.ua}"
                    
                    self.results.append({
                        'source': 'Cloudnestra',
                        'quality': '720p',
                        'url': final_url,
                        'direct': True,
                        'info': 'HLS' if '.m3u8' in f else 'MP4'
                    })

    def resolve(self, url):
        return url

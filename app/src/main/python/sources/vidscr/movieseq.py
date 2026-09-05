# -*- coding: utf-8 -*-
import requests

SITE = 'MovieSeq'
BASE = 'https://movieseq.com'
TIMEOUT = 8
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'

class source:
    def __init__(self):
        self.results = []
        self.domains = ['movieseq.com']

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return str(tmdb) if tmdb else None

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return str(tmdb) if tmdb else None

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url: return None
        return f"{url}|{season}|{episode}"

    def sources(self, url, hostDict):
        if not url: return []
        parts = url.split('|')
        tmdb_id = parts[0]
        season = parts[1] if len(parts) > 1 else None
        episode = parts[2] if len(parts) > 2 else None

        if season and episode:
            embed_url = f"{BASE}/embed/tv/{tmdb_id}/{season}/{episode}"
        else:
            embed_url = f"{BASE}/embed/movie/{tmdb_id}"

        try:
            r = requests.head(embed_url, headers={'User-Agent': UA, 'Referer': BASE + '/'}, timeout=TIMEOUT, allow_redirects=True)
            if r.status_code < 400:
                self.results.append({
                    'source': 'MovieSeq Embed',
                    'quality': '720p',
                    'url': embed_url,
                    'direct': False,
                    'info': 'Embed'
                })
        except:
            # Add anyway as a fallback option
            self.results.append({
                'source': 'MovieSeq Embed',
                'quality': '720p',
                'url': embed_url,
                'direct': False,
                'info': 'Embed'
            })
        return self.results

    def resolve(self, url):
        return url
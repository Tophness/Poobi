# -*- coding: utf-8 -*-
import re
import requests
from urllib.parse import quote_plus

SITE = 'EffedUpMovies'
BASE = 'https://www.effedupmovies.com'
TIMEOUT = 10
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'

_BLOCKED_CATEGORIES = {
    'hentai', 'pornography', 'porn', 'adult-film', 'adult', 'xxx',
    'erotica', 'erotic', 'erotic-thriller', 'softcore', 'hardcore',
    'masturbation', 'sex-scene', 'sexual-themes', 'bdsm', 'fetish'
}

_BOOKMARK_RE = re.compile(r'href="(https?://www\.effedupmovies\.com/([a-z0-9-]+)/)"\s+rel="bookmark"', re.I)
_CATEGORY_RE = re.compile(r'\bcategory-([a-z0-9-]+)\b')
_M3U8_RE = re.compile(r'(?:src|href|file)\s*=\s*["\'](https?://[^"\']+\.m3u8[^"\']*)["\']', re.I)

class source:
    def __init__(self):
        self.results = []
        self.domains = ['effedupmovies.com']

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return f"{title}|{year}" if title else None

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return None

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        return None

    def sources(self, url, hostDict):
        if not url: return []
        parts = url.split('|')
        title = parts[0]
        year = parts[1] if len(parts) > 1 else ''

        post_url = self._find_post(title, year)
        if not post_url: return []

        try:
            r = requests.get(post_url, headers={'User-Agent': UA, 'Referer': BASE + '/'}, timeout=TIMEOUT)
            if not r.ok: return []
            
            # Content safety validation
            cats = set(_CATEGORY_RE.findall(r.text))
            if cats & _BLOCKED_CATEGORIES:
                return []

            for m3u8 in _M3U8_RE.findall(r.text):
                self.results.append({
                    'source': 'EffedUpMovies',
                    'quality': '720p',
                    'url': f"{m3u8}|User-Agent={UA}&Referer={post_url}",
                    'direct': True,
                    'info': 'HLS'
                })
        except: pass
        return self.results

    def _find_post(self, title: str, year: str):
        try:
            r = requests.get(f"{BASE}/?s={quote_plus(title)}", headers={'User-Agent': UA, 'Referer': BASE + '/'}, timeout=TIMEOUT)
            if not r.ok: return None
            needle = re.sub(r'[^a-z0-9]+', '-', title.lower()).strip('-')
            matches = _BOOKMARK_RE.findall(r.text)
            
            if year:
                for url, slug in matches:
                    if needle in slug and year in slug:
                        return url
            for url, slug in matches:
                if needle in slug:
                    return url
        except: pass
        return None

    def resolve(self, url):
        return url
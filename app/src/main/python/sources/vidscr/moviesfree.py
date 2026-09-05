# -*- coding: utf-8 -*-
import re
import json
import base64
import requests
from urllib.parse import quote_plus

SITE = 'MoviesFree'
BASE = 'https://moviesfree.cv'
TIMEOUT = 12
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'

_SLUG_RE = re.compile(r'href="(https?://moviesfree\.cv/[a-z0-9\-]+)/?"')
_SERVERS_DATAURL = re.compile(r'id=["\']servers-js[^"\']*["\']\s+src=["\']data:text/javascript;base64,([A-Za-z0-9+/=]+)["\']')
_SERVERS_B64 = re.compile(r"""(?:window\.)?Servers\s*=\s*JSON\.parse\(atob\(\s*['"]([A-Za-z0-9+/=]+)['"]""")

class source:
    def __init__(self):
        self.results = []
        self.domains = ['moviesfree.cv']

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

        slug = self._find_slug(title, year)
        if not slug: return []

        try:
            r = requests.get(slug + '/', headers={'User-Agent': UA, 'Referer': BASE + '/'}, timeout=TIMEOUT)
            if not r.ok: return []
            servers = self._extract_servers(r.text)
            if not isinstance(servers, dict): return []

            for key in ('superembed', 'filemoon', 'voe', 'mixdrop', 'streamtape', 'streamwish', 'doodstream', 'mixdrp', 'upstream'):
                target_url = servers.get(key)
                if not isinstance(target_url, str) or not target_url.startswith('http'):
                    continue
                self.results.append({
                    'source': f"MoviesFree {key.capitalize()}",
                    'quality': '720p',
                    'url': target_url,
                    'direct': False,
                    'info': key.capitalize()
                })
        except: pass
        return self.results

    def _find_slug(self, title, year):
        try:
            r = requests.get(f"{BASE}/?s={quote_plus(title)}", headers={'User-Agent': UA, 'Referer': BASE + '/'}, timeout=TIMEOUT)
            if not r.ok: return None
            candidates = _SLUG_RE.findall(r.text)
            skip = {'category', 'tag', 'page', 'about', 'contact', 'feed', 'dmca', 'privacy-policy'}
            needle = title.lower().split()[0] if title else ''
            for c in candidates:
                slug = c.rsplit('/', 1)[-1]
                if any(s in slug.lower() for s in skip): continue
                if needle and needle in slug.lower(): return c
            for c in candidates:
                slug = c.rsplit('/', 1)[-1]
                if not any(s in slug.lower() for s in skip): return c
        except: pass
        return None

    def _extract_servers(self, html):
        m = _SERVERS_DATAURL.search(html)
        if m:
            try:
                js = base64.b64decode(m.group(1)).decode('utf-8', 'replace')
                obj_m = re.search(r'Servers\s*=\s*(\{.+?\})\s*;?\s*$', js, re.S)
                if obj_m: return json.loads(obj_m.group(1))
            except: pass
        m = _SERVERS_B64.search(html)
        if m:
            try:
                return json.loads(base64.b64decode(m.group(1)).decode('utf-8', 'replace'))
            except: pass
        return None

    def resolve(self, url):
        return url
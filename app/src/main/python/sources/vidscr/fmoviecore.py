# -*- coding: utf-8 -*-
import re
import json
import base64
import requests
from urllib.parse import quote_plus

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'
TIMEOUT = 10

HOSTS = (
    ('GhostPlayer', 'https://ghostplayer.store'),
)

_SERVERS_DATAURL = re.compile(r'id=["\']servers-js[^"\']*["\']\s+src=["\']data:text/javascript;base64,([A-Za-z0-9+/=]+)["\']')
_SERVERS_B64 = re.compile(r"""(?:window\.)?Servers\s*=\s*JSON\.parse\(atob\(\s*['"]([A-Za-z0-9+/=]+)['"]""")

class source:
    def __init__(self):
        self.results = []
        self.domains = [h[1].split('//')[-1] for h in HOSTS]

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

        for pretty_name, base_url in HOSTS:
            try:
                slug = self._find_slug(base_url, title)
                if not slug: continue
                r = requests.get(slug + '/', headers={'User-Agent': UA, 'Referer': base_url + '/'}, timeout=TIMEOUT)
                if not r.ok: continue
                servers = self._extract_servers(r.text)
                if not isinstance(servers, dict): continue

                for key in ('superembed', 'filemoon', 'voe', 'mixdrop', 'streamtape', 'streamwish', 'doodstream', 'upstream'):
                    target_url = servers.get(key)
                    if not isinstance(target_url, str) or not target_url.startswith('http'):
                        continue
                    self.results.append({
                        'source': f"{pretty_name} {key.capitalize()}",
                        'quality': '720p',
                        'url': target_url,
                        'direct': False,
                        'info': key.capitalize()
                    })
            except: pass
        return self.results

    def _find_slug(self, base, title):
        try:
            r = requests.get(f"{base}/?s={quote_plus(title)}", headers={'User-Agent': UA, 'Referer': base + '/'}, timeout=TIMEOUT)
            if not r.ok: return None
            host = base.split('://', 1)[-1].rstrip('/')
            pat = re.compile(r'href="(https?://' + re.escape(host) + r'/[a-z0-9\-]+)/?"')
            matches = pat.findall(r.text)
            skip = {'category', 'tag', 'page', 'about', 'contact', 'feed', 'dmca', 'privacy-policy'}
            needle = title.lower().split()[0]
            for m in matches:
                slug = m.rsplit('/', 1)[-1]
                if any(s in slug.lower() for s in skip): continue
                if needle in slug.lower(): return m
            for m in matches:
                slug = m.rsplit('/', 1)[-1]
                if not any(s in slug.lower() for s in skip): return m
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
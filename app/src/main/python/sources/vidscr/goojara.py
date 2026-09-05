# -*- coding: utf-8 -*-
import re
import base64
import requests
from urllib.parse import quote_plus

SITE = 'Goojara'
BASE = 'https://ww1.goojara.to'
TIMEOUT = 12
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'

_SLUG_RE = re.compile(
    r'<a\s+href="(/[a-zA-Z0-9]{5,12})"[^>]*>\s*(?:<div[^>]*>\s*)*<strong>([^<]+)</strong>(?:\s*\(?(\d{4})\)?)?',
    re.I | re.S
)
_BCG_RE = re.compile(
    r"""<a\s+class=['"]bcg['"]\s+href=['"]([^'"]+)['"][^>]*>\s*([^<\s]+)\s*(?:<span[^>]*>([^<]+)</span>)?""",
    re.I | re.S
)

class source:
    def __init__(self):
        self.results = []
        self.domains = ['goojara.to', 'ww1.goojara.to']

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

        sess, z, x = self._session_and_tokens()
        if not (z and x): return []

        slug = self._find_slug(sess, z, x, title, year)
        if not slug: return []

        try:
            r = sess.get(BASE + slug, timeout=TIMEOUT)
            if not r.ok: return []

            seen_urls = set()
            for href, host, qual in _BCG_RE.findall(r.text):
                href = (href or '').strip()
                host_str = (host or '').strip().lower()
                if not href.startswith('http'):
                    href = BASE + href if href.startswith('/') else BASE + '/' + href
                if href in seen_urls: continue
                seen_urls.add(href)

                if host_str in ('vidsrc',): continue  # Covered by vidsrc_me

                q = (qual or '720p').upper().strip()
                self.results.append({
                    'source': f"Goojara {host.capitalize()}",
                    'quality': q,
                    'url': href,
                    'direct': False,
                    'info': host.capitalize()
                })
        except: pass
        return self.results

    def _session_and_tokens(self):
        s = requests.Session()
        s.headers.update({
            'User-Agent': UA,
            'Accept': 'text/html,application/xhtml+xml,*/*',
            'Referer': BASE + '/',
        })
        try:
            r = s.get(BASE + '/', timeout=TIMEOUT)
            m_z = re.search(r'id=["\']res["\'][^>]*data-ins=["\']([^"\']+)["\']', r.text)
            m_x = re.search(r"""['"]?z=['"]?\s*\+\s*\w+\s*\+\s*['"]&x=([a-f0-9]{6,40})&q=""", r.text)
            z = m_z.group(1) if m_z else None
            x = m_x.group(1) if m_x else None
            return s, z, x
        except:
            return s, None, None

    def _find_slug(self, sess, z, x, title, year):
        body = f"z={z}&x={x}&q={quote_plus(title)}"
        headers = {
            'X-Requested-With': 'XMLHttpRequest',
            'Content-Type': 'application/x-www-form-urlencoded',
            'Referer': BASE + '/',
        }
        try:
            r = sess.post(f"{BASE}/xmre.php", data=body, headers=headers, timeout=TIMEOUT)
            if not r.ok or not r.text: return None
            tl = title.lower()
            yr = str(year or '').strip()
            candidates = _SLUG_RE.findall(r.text)
            for slug, label, cand_year in candidates:
                lab_lo = (label or '').lower().strip()
                if tl in lab_lo and yr and cand_year and yr == cand_year:
                    return slug
            for slug, label, cand_year in candidates:
                lab_lo = (label or '').lower().strip()
                if tl in lab_lo:
                    return slug
        except: pass
        return None

    def resolve(self, url):
        # Resolve Goojara's base64 redirector to the direct filehost
        if 'go.php?url=' in url:
            try:
                b64 = url.split('go.php?url=')[-1].split('&')[0]
                return base64.b64decode(b64 + '===').decode('utf-8', errors='ignore')
            except: pass
        return url
# -*- coding: utf-8 -*-
import re
import requests
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import urljoin, urlparse

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'
KNOWN_HOSTS = (
    'vidsrc.xyz', 'vidsrc.to', 'vidsrc.net',
    'vidsrc.icu',
    '2embed.cc', '2embed.skin',
    'multiembed.mov', 'moviesapi.club',
)

class source:
    def __init__(self):
        self.results = []
        self.domains = list(KNOWN_HOSTS)

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return f"{imdb}|{tmdb}"

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return f"{imdb}|{tmdb}"

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        return f"{url}|{season}|{episode}"

    def sources(self, url, hostDict):
        if not url: return []
        parts = url.split('|')
        imdb_id = parts[0] if parts[0] != 'None' else None
        tmdb_id = parts[1] if len(parts) > 1 and parts[1] != 'None' else None
        season = parts[2] if len(parts) > 2 else None
        episode = parts[3] if len(parts) > 3 else None
        
        media_type = 'movie' if season is None else 'tv'
        
        s = requests.Session()
        s.headers.update({
            'User-Agent': UA,
            'Accept-Language': 'en-GB,en;q=0.9',
            'Accept-Encoding': 'gzip, deflate'
        })

        with ThreadPoolExecutor(max_workers=5) as ex:
            futures = [
                ex.submit(self._scrape_host, s, host, media_type, tmdb_id, season, episode, imdb_id)
                for host in KNOWN_HOSTS
            ]
            for fut in as_completed(futures, timeout=25):
                try: 
                    res = fut.result()
                    if res: self.results.extend(res)
                except: pass
        return self.results

    def _build_embed_url(self, host, media_type, tmdb_id, season, episode, imdb_id):
        ident = imdb_id or tmdb_id
        s, e = season or 1, episode or 1
        if host in ('vidsrc.xyz', 'vidsrc.to', 'vidsrc.net'):
            if media_type == 'movie': return f'https://{host}/embed/movie/{ident}'
            url = f'https://{host}/embed/tv/{ident}'
            if season: url += f'/{season}'
            if episode: url += f'-{episode}'
            return url
        if host == 'vidsrc.icu':
            if media_type == 'movie': return f'https://vidsrc.icu/embed/movie/{ident}'
            return f'https://vidsrc.icu/embed/tv/{ident}/{s}/{e}'
        if 'multiembed' in host:
            tmdb_flag = '&tmdb=1' if (not imdb_id and tmdb_id) else ''
            if media_type == 'movie': return f'https://{host}/?video_id={ident}{tmdb_flag}'
            return f'https://{host}/?video_id={ident}&s={s}&e={e}{tmdb_flag}'
        if 'moviesapi' in host:
            mid = tmdb_id or ident
            if media_type == 'movie': return f'https://{host}/movie/{mid}'
            return f'https://{host}/tv/{mid}-{s}-{e}'
        if media_type == 'movie': return f'https://{host}/embed/{ident}'
        return f'https://{host}/embedtv/{ident}&s={s}&e={e}'

    def _scrape_host(self, sess, host, media_type, tmdb_id, season, episode, imdb_id):
        embed_url = self._build_embed_url(host, media_type, tmdb_id, season, episode, imdb_id)

        # Special-case vidsrc.icu
        if host == 'vidsrc.icu':
            return self._resolve_vidsrc_icu(sess, embed_url)

        try:
            r = sess.get(embed_url, headers={'Referer': f'https://{host}/'}, timeout=12)
            if not r.ok: return []

            html = r.text
            streams = []

            # Direct media
            for mu in self._scrape_media(html):
                streams.append({
                    'source': f'{host} direct',
                    'quality': '720p',
                    'url': f"{mu}|Referer={embed_url}&User-Agent={UA}",
                    'direct': True,
                    'info': 'HLS' if '.m3u8' in mu else 'MP4'
                })

            # Iframes
            iframes = re.findall(r'<iframe[^>]+src=["\']([^"\']+)["\']', html, re.I)
            for i, u in enumerate(iframes[:3]):
                if u.startswith('//'): u = 'https:' + u
                media_urls = self._follow_iframe(sess, u, embed_url, depth=1)
                for mu in media_urls:
                    streams.append({
                        'source': f'{host} iframe',
                        'quality': '720p',
                        'url': f"{mu}|Referer={u}&User-Agent={UA}",
                        'direct': True,
                        'info': 'HLS' if '.m3u8' in mu else 'MP4'
                    })
            return streams
        except: return []

    def _resolve_vidsrc_icu(self, sess, embed_url):
        try:
            r = sess.get(embed_url, timeout=10, headers={'Referer': 'https://vidsrc.icu/'})
            m = re.search(r'<iframe[^>]+src=["\'](https?:[^"\']+vidsrcme[^"\']+)["\']', r.text)
            if m:
                inner = m.group(1)
                # This could be handed off to vidsrc_me logic if we want to be thorough
                # For now just follow and scrape
                return [{'source': 'vidsrc.icu', 'quality': '720p', 'url': mu, 'direct': True} for mu in self._follow_iframe(sess, inner, embed_url)]
        except: pass
        return []

    def _follow_iframe(self, sess, url, referer, depth=1):
        if depth < 0 or not url: return []
        try:
            r = sess.get(url, timeout=10, headers={'Referer': referer})
            body = r.text
            media = self._scrape_media(body)
            if media: return media

            if depth > 0:
                inner = re.findall(r'<iframe[^>]+src=["\']([^"\']+)["\']', body, re.I)
                for u in inner[:2]:
                    if u.startswith('//'): u = 'https:' + u
                    res = self._follow_iframe(sess, u, url, depth - 1)
                    if res: return res
        except: pass
        return []

    def _scrape_media(self, body):
        pats = [
            r'file\s*:\s*["\']([^"\']+\.(?:m3u8|mp4)[^"\']*)["\']',
            r'sources?\s*:\s*\[\s*\{[^}]*?file\s*:\s*["\']([^"\']+)["\']',
            r'["\'](https?://[^"\']+\.(?:m3u8|mp4)[^"\']*)["\']',
            r'(https?://[^\s\'"<>(){}]+?\.m3u8[^\s\'"<>(){}]*)',
            r'atob\((["\'][A-Za-z0-9+/=]{40,}["\'])\)'
        ]
        out = []
        for pat in pats:
            for m in re.finditer(pat, body):
                u = m.group(1).replace('\\/', '/').strip('"\'')
                if 'atob' in pat:
                    try:
                        import base64
                        u = base64.b64decode(u + '===').decode('utf-8', errors='replace')
                        nm = re.search(r'(https?://[^\s\'"<>]+?\.m3u8[^\s\'"<>]*)', u)
                        if nm: u = nm.group(1)
                        else: continue
                    except: continue

                if not (u.startswith('http') or u.startswith('//')): continue
                if u.startswith('//'): u = 'https:' + u
                if u not in out: out.append(u)
        return out

    def resolve(self, url):
        return url

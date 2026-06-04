# -*- coding: utf-8 -*-
import re
import requests
from concurrent.futures import ThreadPoolExecutor, as_completed

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
        
        with ThreadPoolExecutor(max_workers=len(KNOWN_HOSTS)) as ex:
            futures = [
                ex.submit(self._scrape_host, host, media_type, tmdb_id, season, episode, imdb_id)
                for host in KNOWN_HOSTS
            ]
            for fut in as_completed(futures, timeout=20):
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

    def _scrape_host(self, host, media_type, tmdb_id, season, episode, imdb_id):
        embed_url = self._build_embed_url(host, media_type, tmdb_id, season, episode, imdb_id)
        try:
            r = requests.get(embed_url, headers={'User-Agent': UA, 'Referer': f'https://{host}/'}, timeout=10).text
            streams = []
            # Extract direct links
            pats = [r'file\s*:\s*["\']([^"\']+\.(?:m3u8|mp4)[^"\']*)["\']', r'["\'](https?://[^"\']+\.m3u8[^"\']*)["\']']
            for pat in pats:
                for m in re.finditer(pat, r):
                    u = m.group(1)
                    if u.startswith('//'): u = 'https:' + u
                    streams.append({
                        'source': host,
                        'quality': '720p',
                        'url': u,
                        'direct': True,
                        'info': 'HLS' if '.m3u8' in u else 'MP4'
                    })
            return streams
        except: return []

    def resolve(self, url):
        return url

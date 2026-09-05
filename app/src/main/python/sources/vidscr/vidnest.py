# -*- coding: utf-8 -*-
import json
import re
import requests
from concurrent.futures import ThreadPoolExecutor, as_completed

API = 'https://new.vidnest.fun'
SITE = 'https://vidnest.fun'
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'

_ALPH = 'RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/='
_TABLE = {c: i for i, c in enumerate(_ALPH)}

_PROVIDERS = {
    'moviesapi':    ('moviesapi',    'sources'),
    'purstream':    ('purstream',    'sources_named'),
    'allmovies':    ('allmovies',    'streams_lang'),
    'catflix':      ('catflix',      'sources'),
    'hollymoviehd': ('hollymoviehd', 'sources_file'),
    'flixhq':       ('flixhq',       'url_only'),
    'vidlink':      ('vidlink',      'vidlink'),
    'ophim':        ('klikxxi',      'sources'),
}

PROVIDER_LABELS = {
    'moviesapi':    'MoviesAPI',
    'purstream':    'PurStream',
    'allmovies':    'AllMovies',
    'catflix':      'CatFlix',
    'hollymoviehd': 'HollyMovieHD',
    'flixhq':       'FlixHQ',
    'vidlink':      'VidLink',
    'ophim':        'Ophim',
}

class source:
    def __init__(self):
        self.results = []
        self.domains = ['vidnest.fun', 'new.vidnest.fun']

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return str(tmdb) if tmdb else None

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return str(tmdb) if tmdb else None

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url: return None
        return f"{url}/{season}/{episode}"

    def sources(self, url, hostDict):
        if not url: return []
        is_movie = '/' not in url
        tmdb_id = url if is_movie else url.split('/')[0]
        season = None if is_movie else url.split('/')[1]
        episode = None if is_movie else url.split('/')[2]
        
        with ThreadPoolExecutor(max_workers=len(_PROVIDERS)) as ex:
            futures = [
                ex.submit(self._fetch, is_movie, tmdb_id, season, episode, p, k)
                for p, (_, k) in _PROVIDERS.items()
            ]
            for fut in as_completed(futures, timeout=15):
                try: 
                    res = fut.result()
                    if res: self.results.extend(res)
                except: pass
        return self.results

    def _fetch(self, is_movie, tmdb_id, season, episode, prov, kind):
        path = f"{prov}/movie/{tmdb_id}" if is_movie else f"{prov}/tv/{tmdb_id}/{season}/{episode}"
        try:
            r = requests.get(
                f"{API}/{path}",
                headers={'User-Agent': UA, 'Origin': SITE, 'Referer': SITE + '/'},
                timeout=10
            )
            if not r.ok:
                return []
            data = self._decode(r.json())
            if not isinstance(data, dict):
                return []

            label_pretty = PROVIDER_LABELS.get(prov, prov)
            streams = []

            if kind in ('sources', 'sources_named'):
                for s in (data.get('sources') or []):
                    u = s.get('url')
                    if not u: continue
                    q = s.get('quality') or s.get('name') or '720p'
                    streams.append({
                        'source': f"Vidnest {label_pretty}",
                        'quality': q,
                        'url': f"{u}|User-Agent={UA}&Referer={SITE}/",
                        'direct': True,
                        'info': 'HLS' if '.m3u8' in u.lower() or u.lower().endswith('.txt') else 'MP4'
                    })

            elif kind == 'sources_file':
                for s in (data.get('sources') or []):
                    u = s.get('file')
                    if not u: continue
                    q = s.get('label') or '720p'
                    streams.append({
                        'source': f"Vidnest {label_pretty}",
                        'quality': q,
                        'url': f"{u}|User-Agent={UA}&Referer={SITE}/",
                        'direct': True,
                        'info': 'HLS' if (s.get('type') == 'hls' or '.m3u8' in u.lower()) else 'MP4'
                    })

            elif kind == 'streams_lang':
                for s in (data.get('streams') or []):
                    u = s.get('url')
                    if not u: continue
                    extra_h = s.get('headers') or {}
                    
                    hdr_parts = [f"User-Agent={UA}", f"Referer={SITE}/"]
                    for k, v in extra_h.items():
                        hdr_parts.append(f"{k}={v}")
                    pipe_headers = "&".join(hdr_parts)

                    streams.append({
                        'source': f"Vidnest {label_pretty}",
                        'quality': '720p',
                        'url': f"{u}|{pipe_headers}",
                        'direct': True,
                        'info': 'HLS'
                    })

            elif kind == 'url_only':
                u = data.get('url')
                if u:
                    streams.append({
                        'source': f"Vidnest {label_pretty}",
                        'quality': '720p',
                        'url': f"{u}|User-Agent={UA}&Referer={SITE}/",
                        'direct': True,
                        'info': 'HLS'
                    })

            elif kind == 'vidlink':
                stream = (data.get('data') or {}).get('stream') or {}
                u = stream.get('playlist')
                if u:
                    streams.append({
                        'source': f"Vidnest {label_pretty}",
                        'quality': '720p',
                        'url': f"{u}|User-Agent={UA}&Referer={SITE}/",
                        'direct': True,
                        'info': 'HLS'
                    })

            return streams
        except:
            return []

    def _decode(self, env):
        if not isinstance(env, dict) or not env.get('encrypted'): return env
        data = env.get('data', '')
        out = bytearray()
        for i in range(0, len(data), 4):
            block = data[i:i + 4].ljust(4, '=')
            l = [_TABLE.get(c, 64) for c in block]
            out.append(((l[0] << 2) & 0xFF) | ((l[1] >> 4) & 0xFF))
            if l[2] != 64: out.append((((l[1] & 15) << 4) & 0xFF) | ((l[2] >> 2) & 0xFF))
            if l[3] != 64: out.append(((l[2] << 6) & 0xFF) | (l[3] & 0xFF))
        try:
            return json.loads(out.decode('utf-8', errors='replace'))
        except:
            return {}

    def resolve(self, url):
        return url
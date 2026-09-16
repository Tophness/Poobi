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
    'allmovies':    ('allmovies',    'streams_lang'),
    'hollymoviehd': ('hollymoviehd', 'sources_file'),
    'moviesapi':    ('moviesapi',    'sources'),
    'purstream':    ('purstream',    'sources_named'),
    'catflix':      ('catflix',      'sources'),
    'flixhq':       ('flixhq',       'url_only'),
    'vidlink':      ('vidlink',      'vidlink'),
    'ophim':        ('klikxxi',      'sources'),
}

PROVIDER_LABELS = {
    'allmovies':    'AllMovies',
    'hollymoviehd': 'HollyMovieHD',
    'moviesapi':    'MoviesAPI',
    'purstream':    'PurStream',
    'catflix':      'CatFlix',
    'flixhq':       'FlixHQ',
    'vidlink':      'VidLink',
    'ophim':        'Ophim',
}

LANG_MAP = {
    'eng': 'English', 'en': 'English', 'english': 'English',
    'hin': 'Hindi', 'hi': 'Hindi', 'hindi': 'Hindi',
    'ben': 'Bengali', 'bn': 'Bengali', 'bengali': 'Bengali',
    'tam': 'Tamil', 'ta': 'Tamil', 'tamil': 'Tamil',
    'tel': 'Telugu', 'te': 'Telugu', 'telugu': 'Telugu',
    'spa': 'Spanish', 'es': 'Spanish', 'spanish': 'Spanish',
    'lat': 'Spanish (Latino)', 'latin': 'Spanish (Latino)',
    'fre': 'French', 'fr': 'French', 'french': 'French',
    'ger': 'German', 'de': 'German', 'german': 'German',
    'ita': 'Italian', 'it': 'Italian', 'italian': 'Italian',
    'rus': 'Russian', 'ru': 'Russian', 'russian': 'Russian',
    'jpn': 'Japanese', 'ja': 'Japanese', 'japanese': 'Japanese',
    'kor': 'Korean', 'ko': 'Korean', 'korean': 'Korean',
    'chi': 'Chinese', 'zh': 'Chinese', 'chinese': 'Chinese'
}


def clean_language_tag(raw_label):
    if not raw_label:
        return "English"

    clean = str(raw_label).strip()

    if re.match(r'^[A-Z]{2}-\d+$', clean, re.I) or clean.upper().startswith(('LS-', 'GS-', 'SV-', 'SRV-')):
        return "English"

    clean_lower = clean.lower()
    if clean_lower in ('unknown', 'default', 'hls', 'mp4', 'stream', 'direct'):
        return "English"

    if clean_lower in LANG_MAP:
        return LANG_MAP[clean_lower]

    for k, v in LANG_MAP.items():
        if len(k) > 2 and k in clean_lower:
            return v

    return clean.title()

class source:
    def __init__(self):
        self.results = []
        self.domains = ['vidnest.fun', 'new.vidnest.fun']

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return str(tmdb) if tmdb else None

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return str(tmdb) if tmdb else None

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url:
            return None
        return f"{url}/{season}/{episode}"

    def sources(self, url, hostDict):
        self.results = []
        if not url:
            return []

        is_movie = '/' not in url
        tmdb_id = url if is_movie else url.split('/')[0]
        season = None if is_movie else url.split('/')[1]
        episode = None if is_movie else url.split('/')[2]

        all_collected_streams = []

        with ThreadPoolExecutor(max_workers=len(_PROVIDERS)) as ex:
            futures = [
                ex.submit(self._fetch, is_movie, tmdb_id, season, episode, p, k)
                for p, (_, k) in _PROVIDERS.items()
            ]
            for fut in as_completed(futures, timeout=15):
                try:
                    res = fut.result()
                    if res:
                        all_collected_streams.extend(res)
                except Exception:
                    pass

        if not all_collected_streams:
            return []

        def stream_sort_key(s):
            is_goodstream = 1 if 'goodstream.cc' in s.get('url', '') else 0
            is_not_eng = 0 if s.get('audio') == 'English' else 1
            q = s.get('quality', '720p')
            q_rank = 0 if '1080' in q else (1 if '720' in q else 2)
            return (is_goodstream, is_not_eng, q_rank)

        all_collected_streams.sort(key=stream_sort_key)

        primary = all_collected_streams[0]
        all_alt_urls = [s['url'] for s in all_collected_streams]
        all_alt_names = [s['name'] for s in all_collected_streams]

        mirror_count = len(all_collected_streams)
        primary_lang = primary.get('audio', 'English')
        primary_quality = primary.get('quality', '720p')
        info_str = f"{primary_quality} | {primary_lang} | {mirror_count} Mirrors"

        self.results.append({
            'source': 'VidNest',
            'title': f"VidNest ({primary_quality} • {primary_lang})",
            'provider': 'VidNest',
            'quality': primary_quality,
            'url': primary['url'],
            'direct': True,
            'is_video': True,
            'info': info_str,
            'audio': primary_lang,
            'languages_display': primary_lang,
            'alternative_urls': all_alt_urls if mirror_count > 1 else [],
            'alternative_names': all_alt_names if mirror_count > 1 else []
        })

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
            extracted = []

            raw_streams = data.get('streams') or []
            if isinstance(raw_streams, list) and len(raw_streams) > 0:
                for s in raw_streams:
                    if not isinstance(s, dict):
                        continue
                    u = s.get('url')
                    if not u or not u.startswith('http'):
                        continue

                    delim = "&" if "?" in u else "?"
                    u_clean = f"{u}{delim}bypass_localize=true"

                    raw_lang = s.get('language') or s.get('lang') or s.get('name') or ''
                    clean_lang = clean_language_tag(raw_lang)
                    extra_h = s.get('headers') or {}

                    req_headers = {'User-Agent': UA}
                    if 'Referer' in extra_h:
                        req_headers['Referer'] = extra_h['Referer']
                    elif 'referer' in extra_h:
                        req_headers['Referer'] = extra_h['referer']
                    else:
                        req_headers['Referer'] = f"{SITE}/"

                    for k, v in extra_h.items():
                        if k.lower() != 'referer':
                            req_headers[k] = v

                    pipe_url = f"{u_clean}|{'&'.join([f'{k}={v}' for k, v in req_headers.items()])}"

                    extracted.append({
                        'provider': label_pretty,
                        'name': f"{label_pretty} ({clean_lang})",
                        'quality': '720p',
                        'audio': clean_lang,
                        'url': pipe_url
                    })

                name_counts = {}
                for item in extracted:
                    name_counts[item['name']] = name_counts.get(item['name'], 0) + 1

                name_seen = {}
                for item in extracted:
                    n = item['name']
                    if name_counts[n] > 1:
                        name_seen[n] = name_seen.get(n, 0) + 1
                        base_tag = item['audio']
                        item['name'] = f"{label_pretty} (Server {name_seen[n]} - {base_tag})"

            elif kind in ('sources', 'sources_named'):
                for s in (data.get('sources') or []):
                    u = s.get('url')
                    if not u:
                        continue
                    delim = "&" if "?" in u else "?"
                    u_clean = f"{u}{delim}bypass_localize=true"
                    q = s.get('quality') or s.get('name') or '720p'
                    raw_lang = s.get('language') or s.get('lang') or ''
                    clean_lang = clean_language_tag(raw_lang)

                    pipe_url = f"{u_clean}|User-Agent={UA}&Referer={SITE}/"

                    extracted.append({
                        'provider': label_pretty,
                        'name': f"{label_pretty} ({q})",
                        'quality': q,
                        'audio': clean_lang,
                        'url': pipe_url
                    })

            elif kind == 'sources_file':
                for s in (data.get('sources') or []):
                    u = s.get('file')
                    if not u:
                        continue
                    delim = "&" if "?" in u else "?"
                    u_clean = f"{u}{delim}bypass_localize=true"
                    q = s.get('label') or '720p'
                    raw_lang = s.get('language') or s.get('lang') or ''
                    clean_lang = clean_language_tag(raw_lang)

                    pipe_url = f"{u_clean}|User-Agent={UA}&Referer={SITE}/"

                    extracted.append({
                        'provider': label_pretty,
                        'name': f"{label_pretty} ({q})",
                        'quality': q,
                        'audio': clean_lang,
                        'url': pipe_url
                    })

            elif kind == 'url_only':
                u = data.get('url')
                if u:
                    delim = "&" if "?" in u else "?"
                    u_clean = f"{u}{delim}bypass_localize=true"
                    pipe_url = f"{u_clean}|User-Agent={UA}&Referer={SITE}/"
                    extracted.append({
                        'provider': label_pretty,
                        'name': f"{label_pretty} (HD)",
                        'quality': '720p',
                        'audio': 'English',
                        'url': pipe_url
                    })

            elif kind == 'vidlink':
                stream = (data.get('data') or {}).get('stream') or {}
                u = stream.get('playlist')
                if u:
                    delim = "&" if "?" in u else "?"
                    u_clean = f"{u}{delim}bypass_localize=true"
                    pipe_url = f"{u_clean}|User-Agent={UA}&Referer={SITE}/"
                    extracted.append({
                        'provider': label_pretty,
                        'name': f"{label_pretty} (HD)",
                        'quality': '720p',
                        'audio': 'English',
                        'url': pipe_url
                    })

            return extracted
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
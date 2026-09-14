# -*- coding: utf-8 -*-
"""
    MovieSeq Source Provider for Poobi
"""
import re
import urllib.parse
import requests

SITE = 'MovieSeq'
API_URL = 'https://streamdata.vaplayer.ru/api.php'
TIMEOUT = 10
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'

HEADERS_API = {
    'User-Agent': UA,
    'Referer': 'https://nextgencloudfabric.com/',
    'Origin': 'https://nextgencloudfabric.com',
    'Accept': 'application/json, text/javascript, */*; q=0.01'
}

STREAM_HEADERS = {
    'User-Agent': UA,
    'Referer': 'https://nextgencloudfabric.com/',
    'Origin': 'https://nextgencloudfabric.com'
}


class source:
    def __init__(self):
        self.results = []
        self.domains = ['movieseq.com', 'nextgencloudfabric.com', 'subscriptionbusinesshub.site', 'vaplayer.ru']

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        if tmdb:
            return f"movie|{tmdb}"
        elif imdb:
            return f"movie|imdb_{imdb}"
        return None

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        if tmdb:
            return f"tv|{tmdb}"
        elif imdb:
            return f"tv|imdb_{imdb}"
        return None

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url:
            return None
        return f"{url}|{season}|{episode}"

    def _parse_metadata(self, file_name):
        fn = file_name.upper()

        quality = '1080p'
        quality_rank = 1
        if any(k in fn for k in ['4K', '2160P', 'UHD']):
            quality = '4K'
            quality_rank = 0
        elif '1080P' in fn:
            quality = '1080p'
            quality_rank = 1
        elif '720P' in fn:
            quality = '720p'
            quality_rank = 2
        elif any(k in fn for k in ['480P', '360P', 'SD']):
            quality = 'SD'
            quality_rank = 3

        if any(k in fn for k in ['7.1', '8CH']):
            audio = 'English (7.1)'
        elif any(k in fn for k in ['5.1', '6CH', 'DDP5.1', 'DD5.1', 'AAC5.1', 'EAC3.5.1', 'EAC3 5.1']):
            audio = 'English (5.1)'
        elif any(k in fn for k in ['ATMOS']):
            audio = 'English (Atmos)'
        elif any(k in fn for k in ['DUAL', 'HINDI', 'HIN']):
            audio = 'Dual-Audio'
        elif any(k in fn for k in ['MULTI']):
            audio = 'Multi-Audio'
        elif any(k in fn for k in ['2.0', '2CH', 'STEREO', 'AAC2.0']):
            audio = 'English (2.0)'
        else:
            audio = 'English'

        codec = ''
        if any(k in fn for k in ['HEVC', 'X265', 'H265', 'H.265']):
            codec = 'HEVC'
        elif any(k in fn for k in ['X264', 'H264', 'H.264', 'AVC']):
            codec = 'AVC'

        rtype = ''
        if 'REMUX' in fn:
            rtype = 'REMUX'
        elif any(k in fn for k in ['BLURAY', 'BD-RIP', 'BDRIP']):
            rtype = 'BluRay'
        elif 'WEB-DL' in fn:
            rtype = 'WEB-DL'
        elif 'WEBRIP' in fn:
            rtype = 'WEBRip'

        info_parts = ['MovieSeq', quality]
        if codec:
            info_parts.append(codec)
        if audio:
            info_parts.append(audio)
        if rtype:
            info_parts.append(rtype)
        info_parts.append('Adaptive HLS')

        return quality, quality_rank, audio, ' | '.join(info_parts)

    def sources(self, url, hostDict):
        self.results = []
        if not url:
            return self.results

        parts = url.split('|')
        media_type = parts[0]
        id_val = parts[1]
        season = parts[2] if len(parts) > 2 else None
        episode = parts[3] if len(parts) > 3 else None

        params = {'type': media_type}
        if id_val.startswith('imdb_'):
            params['imdb'] = id_val.replace('imdb_', '')
        else:
            params['tmdb'] = id_val

        if media_type == 'tv' and season and episode:
            params['season'] = str(season)
            params['episode'] = str(episode)

        try:
            session = requests.Session()
            session.headers.update(HEADERS_API)

            resp = session.get(API_URL, params=params, timeout=TIMEOUT)
            if resp.status_code != 200:
                return self.results

            payload = resp.json()
            if str(payload.get('status_code')) != '200' or 'data' not in payload:
                return self.results

            data = payload['data']
            stream_urls = data.get('stream_urls', [])
            file_name = data.get('file_name', '')

            clean_urls = [u.strip() for u in stream_urls if u and u.strip().startswith('http')]
            if not clean_urls:
                return self.results

            quality, quality_rank, audio, info_str = self._parse_metadata(file_name)

            header_query = urllib.parse.urlencode(STREAM_HEADERS)
            primary_playable_url = f"{clean_urls[0]}|{header_query}"

            self.results.append({
                'title': 'MovieSeq',
                'source': 'MovieSeq',
                'provider': 'VidAPI',
                'quality': quality,
                'quality_rank': quality_rank,
                'url': primary_playable_url,
                'direct': True,
                'is_video': True,
                'info': info_str,
                'audio': audio,
                'languages_display': audio,
                'headers': STREAM_HEADERS
            })

        except Exception:
            pass

        return self.results

    def resolve(self, url):
        return url
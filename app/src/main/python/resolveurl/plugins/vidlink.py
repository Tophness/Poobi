"""
    Plugin for ResolveURL - VidLink
    Copyright (C) 2026 Poobi
"""

import json
import re
from six.moves import urllib_parse
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError


class VidLinkResolver(ResolveUrl):
    name = 'VidLink'
    domains = ['vidlink.pro', 'vidlink.org']
    pattern = r'(?://|\.)(vidlink\.(?:pro|org))/(?:embed/)?((?:movie|tv)/[0-9a-zA-Z/-]+(?:\?[^"\'>\s]+)?)'

    def get_media_url(self, host, media_id, subs=False):
        parts = [p for p in media_id.split('?')[0].split('/') if p]
        is_tv = 'tv' in parts

        clean_id = parts[1] if len(parts) > 1 and parts[0] in ['movie', 'tv'] else parts[0]
        query_params = {}
        if '?' in media_id:
            query_params = urllib_parse.parse_qs(media_id.split('?')[1])

        tmdb_int = re.sub(r'\D', '', clean_id)
        if not tmdb_int:
            raise ResolverError('VidLink: TMDB ID missing')

        if is_tv:
            season = parts[2] if len(parts) > 2 else query_params.get('season', ['1'])[0]
            episode = parts[3] if len(parts) > 3 else query_params.get('episode', ['1'])[0]
            api_url = f"https://vidlink.pro/api/b/tv/{tmdb_int}/{season}/{episode}"
        else:
            api_url = f"https://vidlink.pro/api/b/movie/{tmdb_int}"

        headers = {
            'User-Agent': common.RAND_UA,
            'Referer': f'https://vidlink.pro/{media_id}',
            'Origin': 'https://vidlink.pro',
            'Accept': 'application/json, text/plain, */*'
        }

        try:
            resp = self.net.http_GET(api_url, headers=headers)
            data = json.loads(resp.content) or {}
        except Exception as e:
            raise ResolverError(f"VidLink API Error: {e}")

        stream_obj = data.get('stream') if isinstance(data.get('stream'), dict) else {}
        stream_url = (
            stream_obj.get('playlist') or 
            (stream_obj.get('qualities') or {}).get('auto', {}).get('url') or
            data.get('url') or
            data.get('playlist')
        )

        if not stream_url and stream_obj.get('sources'):
            for s in stream_obj['sources']:
                if isinstance(s, dict) and s.get('url'):
                    stream_url = s['url']
                    break

        # Fallback to direct m3u8 in qualities dictionary
        if not stream_url and isinstance(stream_obj.get('qualities'), dict):
            for q_val in stream_obj['qualities'].values():
                if isinstance(q_val, dict) and q_val.get('url'):
                    stream_url = q_val['url']
                    break

        if not stream_url:
            raise ResolverError('VidLink: Stream playlist not found in API response')

        stream_headers = {
            'User-Agent': common.RAND_UA,
            'Referer': 'https://vidlink.pro/',
            'Origin': 'https://vidlink.pro',
            'verifypeer': 'false'
        }

        playable_url = stream_url + helpers.append_headers(stream_headers)

        if subs:
            subtitles = {}
            for tr in stream_obj.get('captions', []):
                label = tr.get('label') or tr.get('language') or 'English'
                url = tr.get('url')
                if url:
                    subtitles[label] = url
            return playable_url, subtitles

        return playable_url

    def get_url(self, host, media_id):
        return f"https://vidlink.pro/{media_id}"
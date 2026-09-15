"""
    Plugin for ResolveURL - VidLove / 111Movies
    Copyright (C) 2026 Poobi
"""

import json
import re
from six.moves import urllib_parse
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError


class VidLoveResolver(ResolveUrl):
    name = 'VidLove'
    domains = ['vidlove.cc', 'player.vidlove.cc', '111movies.com']
    pattern = r'(?://|\.)((?:player\.)?vidlove\.cc|111movies\.com)/(?:embed/|movie/|tv/)?((?:movie|tv|[0-9a-zA-Z/-]+)(?:\?[^"\'>\s]+)?)'

    SERVERS = ['vidapi', 'megaknight', 'warden', 'cinefreak', 'ipcloud', 'tcloud', 'moviebox2']

    def get_media_url(self, host, media_id, subs=False):
        parts = [p for p in media_id.split('?')[0].split('/') if p]
        is_tv = 'tv' in parts

        clean_id = parts[1] if len(parts) > 1 and parts[0] in ['movie', 'tv'] else parts[0]
        query_params = {}
        if '?' in media_id:
            query_params = urllib_parse.parse_qs(media_id.split('?')[1])

        api_base = "https://api.vidlove.cc"
        if is_tv:
            season = parts[2] if len(parts) > 2 else query_params.get('season', ['1'])[0]
            episode = parts[3] if len(parts) > 3 else query_params.get('episode', ['1'])[0]
            base_query = f"/tv?id={clean_id}&season={season}&episode={episode}&mode=json"
        else:
            base_query = f"/movie?id={clean_id}&mode=json"

        api_headers = {
            'User-Agent': common.RAND_UA,
            'Referer': 'https://player.vidlove.cc/',
            'Origin': 'https://player.vidlove.cc',
            'Accept': 'application/json, text/plain, */*'
        }

        stream_url = None
        data = {}

        for server in self.SERVERS:
            api_url = f"{api_base}{base_query}&sources={server}"
            try:
                resp = self.net.http_GET(api_url, headers=api_headers)
                data = json.loads(resp.content)
                src_val = data.get('source')
                if isinstance(src_val, dict):
                    stream_url = src_val.get('url') or src_val.get('file') or src_val.get('stream')
                elif isinstance(src_val, str) and src_val.startswith('http'):
                    stream_url = src_val

                if not stream_url and data.get('stream'):
                    stream_url = data['stream']

                if stream_url:
                    break
            except Exception:
                continue

        if not stream_url:
            raise ResolverError('VidLove: Failed to extract stream URL from all server mirrors')

        stream_headers = {
            'User-Agent': common.RAND_UA,
            'Referer': 'https://player.vidlove.cc/',
            'Origin': 'https://player.vidlove.cc',
            'verifypeer': 'false'
        }

        if 'whysosigmabro' in stream_url and '.m3u8' not in stream_url:
            stream_url += '&format=.m3u8'

        playable_url = stream_url + helpers.append_headers(stream_headers)

        if subs:
            subtitles = {}
            for item in data.get('subtitles', []):
                label = item.get('label') or item.get('lang') or item.get('language', 'English')
                sub_file = item.get('file') or item.get('url')
                if sub_file:
                    subtitles[label] = sub_file
            return playable_url, subtitles

        return playable_url

    def get_url(self, host, media_id):
        if media_id.startswith('http'):
            return media_id
        return f"https://player.vidlove.cc/embed/{media_id}"
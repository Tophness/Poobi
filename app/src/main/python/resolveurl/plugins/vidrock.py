"""
    Plugin for ResolveURL
    Copyright (C) 2026 Poobi
"""

import re
import json
import base64
from six.moves import urllib_parse
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError
from modules.purecrypto import aes256_gcm_decrypt


class VidRockResolver(ResolveUrl):
    name = 'VidRock'
    domains = ['vidrock.net', 'vidvault.ru']
    pattern = r'(?://|\.)(vidrock\.net|vidvault\.ru)/(?:movie/|tv/)?([0-9a-zA-Z/-]+(?:\?[^"\'>\s]+)?)'

    KEY = bytes.fromhex("7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f")

    def get_media_url(self, host, media_id, subs=False):
        parts = [p for p in media_id.split('?')[0].split('/') if p]
        is_tv = 'tv' in parts

        clean_id = parts[1] if len(parts) > 1 and parts[0] in ['movie', 'tv'] else parts[0]
        query_params = {}
        if '?' in media_id:
            query_params = urllib_parse.parse_qs(media_id.split('?')[1])

        if is_tv:
            season = parts[2] if len(parts) > 2 else query_params.get('season', ['1'])[0]
            episode = parts[3] if len(parts) > 3 else query_params.get('episode', ['1'])[0]
            api_url = f"https://vidrock.net/api/tv/{clean_id}/{season}/{episode}"
        else:
            api_url = f"https://vidrock.net/api/movie/{clean_id}"

        headers = {
            'User-Agent': common.RAND_UA,
            'Referer': 'https://vidrock.net/',
            'Origin': 'https://vidrock.net',
            'Accept': 'application/json'
        }

        try:
            resp = self.net.http_GET(api_url, headers=headers)
            servers = json.loads(resp.content)
        except Exception as e:
            raise ResolverError(f'VidRock: Failed to fetch API payload: {e}')

        for s_name in ['Luna', 'Orion', 'Astra', 'Nova', 'Atlas']:
            server_data = servers.get(s_name)
            if not server_data or not server_data.get('url'):
                continue

            enc_url = server_data['url']
            try:
                stream_url = self._decrypt_url(enc_url)
                if stream_url and stream_url.startswith('http'):
                    stream_headers = {
                        'User-Agent': common.RAND_UA,
                        'Referer': 'https://vidrock.net/',
                        'Origin': 'https://vidrock.net',
                        'verifypeer': 'false'
                    }
                    playable_url = stream_url + helpers.append_headers(stream_headers)

                    if subs:
                        subtitles = {}
                        sub_api = f"https://sub.vdrk.site/subtitles?id={clean_id}"
                        try:
                            s_resp = self.net.http_GET(sub_api, headers=headers)
                            s_data = json.loads(s_resp.content)
                            for item in s_data:
                                label = item.get('label') or item.get('lang', 'English')
                                s_url = item.get('url') or item.get('file')
                                if s_url:
                                    subtitles[label] = s_url
                        except Exception:
                            pass
                        return playable_url, subtitles

                    return playable_url
            except Exception:
                continue

        raise ResolverError('VidRock: Unable to decrypt any active stream URL')

    def _decrypt_url(self, enc_str):
        padded = enc_str + '=' * (-len(enc_str) % 4)
        raw = base64.urlsafe_b64decode(padded)
        if len(raw) < 28:
            return None
        iv = raw[:12]
        ciphertext = raw[12:-16]
        tag = raw[-16:]
        return aes256_gcm_decrypt(self.KEY, iv, ciphertext, tag).decode('utf-8')

    def get_url(self, host, media_id):
        if media_id.startswith('http'):
            return media_id
        return f"https://vidrock.net/movie/{media_id}"
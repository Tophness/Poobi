"""
    Plugin for ResolveURL
    Copyright (C) 2026 Poobi / Gujal
"""

import json
import base64
from six.moves import urllib_parse
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError
from resources.lib.modules import client
from modules.purecrypto import aes256_gcm_decrypt


class PeachifyResolver(ResolveUrl):
    name = 'Peachify'
    domains = ['peachify.top', 'eat-peach.sbs', 'x.eat-peach.sbs', 'none.eat-peach.sbs', 'a.eat-peach.sbs']
    pattern = r'(?://|\.)((?:peachify\.top|(?:[axn]one\.)?eat-peach\.sbs))/(?:embed/)?(?:movie/|tv/)?([0-9a-zA-Z-/]+)'

    PEACHIFY_KEY = bytes.fromhex("d8f2a1b5e9c470814f6b2c3a5d8e7f901a2b3c4d5e3f7a8b9c0d1e2f3a4d5c6d")

    def get_media_url(self, host, media_id, subs=False):
        parts = media_id.split('/')
        tmdb_id = parts[1] if len(parts) > 1 and parts[0] in ['movie', 'tv'] else parts[0]
        media_type = 'tv' if 'tv' in media_id or (len(parts) > 2 and parts[0] == 'tv') else 'movie'

        season = None
        episode = None
        if media_type == 'tv':
            tv_parts = [p for p in parts if p.isdigit()]
            if len(tv_parts) >= 3:
                tmdb_id = tv_parts[0]
                season = tv_parts[1]
                episode = tv_parts[2]
            elif len(tv_parts) >= 2:
                tmdb_id = tv_parts[0]
                season = tv_parts[1]
                episode = "1"

        providers = ['air', 'holly', 'multi', 'moviebox']
        api_bases = ['https://none.eat-peach.sbs', 'https://x.eat-peach.sbs']

        headers = {
            'User-Agent': client.UserAgent,
            'Referer': 'https://peachify.top/',
            'Origin': 'https://peachify.top',
            'Accept': 'application/json, text/plain, */*'
        }

        raw_data = None
        for base in api_bases:
            for prov in providers:
                if media_type == 'tv' and season and episode:
                    api_url = f"{base}/{prov}/tv/{tmdb_id}/{season}/{episode}"
                else:
                    api_url = f"{base}/{prov}/movie/{tmdb_id}"

                try:
                    resp = client.scrapePage(api_url, headers=headers)
                    if resp and resp.status_code == 200:
                        raw_json = resp.json()
                        if raw_json.get('isEncrypted'):
                            raw_data = self._decrypt_payload(raw_json.get('data'))
                        else:
                            raw_data = raw_json
                        if raw_data and (raw_data.get('sources') or raw_data.get('stream')):
                            break
                except Exception:
                    continue
            if raw_data:
                break

        if not raw_data:
            raise ResolverError('Peachify: Failed to retrieve or decrypt stream data from API')

        sources = []
        stream_sources = raw_data.get('sources') or raw_data.get('stream') or []
        for src in stream_sources:
            surl = src.get('url') or src.get('file') or src.get('src')
            dub = src.get('dub', 'English')
            label = f"1080p ({dub})" if surl else dub
            if surl:
                sources.append((label, surl))

        if not sources and raw_data.get('file'):
            sources.append(('HD', raw_data.get('file')))

        if sources:
            stream_url = helpers.pick_source(helpers.sort_sources_list(sources))
            if stream_url.startswith('//'):
                stream_url = 'https:' + stream_url

            src_headers = {
                'origin': 'https://peachify.top',
                'referer': 'https://peachify.top/',
                'verifypeer': 'false'
            }

            final_url = stream_url + helpers.append_headers(src_headers)

            if subs:
                subtitles = {}
                subs_data = raw_data.get('subtitles') or raw_data.get('tracks') or []
                for sub in subs_data:
                    sub_url = sub.get('file') or sub.get('url')
                    sub_lang = sub.get('label') or sub.get('language') or 'English'
                    if sub_url:
                        subtitles[sub_lang] = sub_url
                return final_url, subtitles

            return final_url

        raise ResolverError('Peachify: No playable sources found in API response')

    def get_url(self, host, media_id):
        if not host.startswith('http'):
            host = 'peachify.top'
        return self._default_get_url(host, media_id, template='https://{host}/embed/{media_id}')

    def _decrypt_payload(self, data_str):
        try:
            parts = data_str.split('.')
            if len(parts) < 3:
                return None
            iv = self._base64url_decode(parts[0])
            ciphertext = self._base64url_decode(parts[1])
            tag = self._base64url_decode(parts[2])

            decrypted_bytes = aes256_gcm_decrypt(self.PEACHIFY_KEY, iv, ciphertext, tag)
            return json.loads(decrypted_bytes.decode('utf-8'))
        except Exception:
            return None

    @staticmethod
    def _base64url_decode(input_str):
        input_str = input_str.replace('-', '+').replace('_', '/')
        padding = '=' * (4 - (len(input_str) % 4))
        return base64.b64decode(input_str + padding)
# -*- coding: utf-8 -*-
"""
    Plugin for ResolveURL - VidNest
    Copyright (C) 2026 Poobi
"""

import json
import re
from urllib.parse import quote
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError


class VidNestResolver(ResolveUrl):
    name = 'VidNest'
    domains = ['vidnest.fun', 'vidnest.io', 'vidnest.live', 'new.vidnest.fun']
    pattern = r'(?://|\.)((?:new\.)?vidnest\.(?:fun|io|live))/(?:embed/|e/|d/)?((?:movie/|tv/)?[0-9a-zA-Z/-]+(?:\?[^"\'>\s]+)?)'

    CUSTOM_ALPHABET = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="

    # Verified active servers on new.vidnest.fun
    SERVERS = [
        ("alfa", "videasy/movie"),
        ("catflix", "yflix/movie"),
        ("zeta", "nextgencloudfabric/movie"),
        ("filxer", "rogflix/movie"),
        ("gama", "vidzee/movie"),
        ("ophim", "klikxxi/movie"),
        ("sigma", "hollymoviehd/movie")
    ]

    WORKERS = [
        "https://flixer.streaming-2.workers.dev",
        "https://flixer.streaming-3.workers.dev",
        "https://flixer.streaming-4.workers.dev",
        "https://flixer.streaming-5.workers.dev",
        "https://flixer.streaming-6.workers.dev",
        "https://flixer.streaming-7.workers.dev",
        "https://flixer.streaming-8.workers.dev"
    ]

    def get_media_url(self, host, media_id, subs=False):
        parts = [p for p in media_id.split('?')[0].split('/') if p]
        clean_id = parts[1] if len(parts) > 1 and parts[0] in ['movie', 'tv'] else parts[0]
        numeric_id = re.sub(r'\D', '', clean_id)
        if not numeric_id:
            raise ResolverError('VidNest: Valid numeric TMDB ID required')

        headers = {
            'User-Agent': common.RAND_UA,
            'Referer': f'https://vidnest.fun/movie/{numeric_id}',
            'Origin': 'https://vidnest.fun',
            'Accept': 'application/json, text/plain, */*'
        }

        for server_type, endpoint in self.SERVERS:
            api_url = f"https://new.vidnest.fun/{endpoint}/{numeric_id}"
            try:
                resp = self.net.http_GET(api_url, headers=headers)
                if not resp or not resp.content:
                    continue

                raw_resp = json.loads(resp.content)
                decrypted = self.decrypt_cipher_response(raw_resp)
                if not decrypted:
                    continue

                payload = json.loads(decrypted) if isinstance(decrypted, str) else decrypted

                stream_url = None
                if isinstance(payload, dict):
                    if payload.get('url'):
                        stream_url = payload['url']
                    elif payload.get('streams'):
                        hls = next((s for s in payload['streams'] if s.get('type') == 'hls' and s.get('url')), None)
                        if hls:
                            raw_stream = hls['url']
                            headers_json = quote(json.dumps({'Referer': 'https://moviesapi.to/'}))
                            worker = self.WORKERS[int(numeric_id) % len(self.WORKERS)]
                            stream_url = f"{worker}/proxy?url={quote(raw_stream)}&headers={headers_json}"
                        elif payload['streams'] and payload['streams'][0].get('url'):
                            stream_url = payload['streams'][0]['url']
                    elif payload.get('sources'):
                        stream_url = payload['sources'][0].get('url')

                if stream_url:
                    stream_headers = {
                        'User-Agent': common.RAND_UA,
                        'Referer': 'https://vidnest.fun/',
                        'Origin': 'https://vidnest.fun',
                        'verifypeer': 'false'
                    }
                    playable_url = stream_url + helpers.append_headers(stream_headers)

                    if subs:
                        subtitles = {}
                        for sub_item in payload.get('subtitles', []):
                            s_url = sub_item.get('url') or sub_item.get('file')
                            s_label = sub_item.get('lang') or sub_item.get('label') or 'English'
                            if s_url:
                                subtitles[s_label] = s_url
                        return playable_url, subtitles

                    return playable_url

            except Exception:
                continue

        raise ResolverError('VidNest: Failed to resolve stream from any mirror')

    @classmethod
    def decrypt_cipher_response(cls, response_obj):
        data_str = response_obj.get('data') if isinstance(response_obj, dict) else response_obj
        if not data_str or not isinstance(data_str, str):
            return data_str

        char_map = {cls.CUSTOM_ALPHABET[i]: i for i in range(len(cls.CUSTOM_ALPHABET))}

        decoded_bytes = bytearray()
        data_len = len(data_str)

        for t in range(0, data_len, 4):
            chunk = data_str[t:t + 4]
            while len(chunk) < 4:
                chunk += "="

            indices = [char_map.get(c, 64) for c in chunk]

            b0 = ((indices[0] & 63) << 2) | ((indices[1] & 48) >> 4)
            decoded_bytes.append(b0)

            if indices[2] != 64:
                b1 = ((indices[1] & 15) << 4) | ((indices[2] & 60) >> 2)
                decoded_bytes.append(b1)

            if indices[3] != 64:
                b2 = ((indices[2] & 3) << 6) | (indices[3] & 63)
                decoded_bytes.append(b2)

        return decoded_bytes.decode('utf-8', errors='replace')

    def get_url(self, host, media_id):
        if media_id.startswith('http'):
            return media_id
        return f"https://vidnest.fun/movie/{media_id}"
"""
    Plugin for ResolveURL
    Copyright (C) 2026 Poobi
"""

import base64
import json
import re
from six.moves import urllib_parse
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError


class VidKingResolver(ResolveUrl):
    name = 'VidKing'
    domains = ['vidking.net', 'www.vidking.net', 'videasy.net', 'player.videasy.net']
    pattern = r'(?://|\.)(vidking\.net|player\.videasy\.net|videasy\.net)/(?:embed/)?((?:movie|tv)/[0-9a-zA-Z/-]+(?:\?[^"\'>\s]+)?)'

    HL = [
        1116352408, 1899447441, 3049323471, 3921009573,
        961987163, 1508970993, 2453635748, 2870763221,
        3624381080, 310598401, 607225278, 1426881987,
        1925078388, 2162078206, 2614888103, 3248222580
    ]
    JS = 61
    SF = 8
    MS = 2654435769
    MAGIC_HEADER = bytes([109, 118, 109, 49])  # b'mvm1'

    SERVERS = [
        ('Yoru', 'cdn/sources-with-title', {}),
        ('Cypher', 'downloader2/sources-with-title', {}),
        ('Breach', 'm4uhd/sources-with-title', {}),
        ('Neon', 'vsrc/sources-with-title', {})
    ]

    def get_media_url(self, host, media_id, subs=False):
        parts = [p for p in media_id.split('?')[0].split('/') if p]
        is_tv = 'tv' in parts

        clean_id = parts[1] if len(parts) > 1 and parts[0] in ['movie', 'tv'] else parts[0]
        query_params = {}
        if '?' in media_id:
            query_params = urllib_parse.parse_qs(media_id.split('?')[1])

        tmdb_int = int(re.sub(r'\D', '', clean_id)) if re.sub(r'\D', '', clean_id) else 0
        if not tmdb_int:
            raise ResolverError('VidKing: Valid TMDB numeric ID required')

        season = parts[2] if len(parts) > 2 else query_params.get('season', ['1'])[0]
        episode = parts[3] if len(parts) > 3 else query_params.get('episode', ['1'])[0]

        headers = {
            'User-Agent': common.RAND_UA,
            'Referer': 'https://www.vidking.net/',
            'Origin': 'https://www.vidking.net',
            'Accept': 'application/json, text/plain, */*'
        }

        seed_url = f"https://api.speedracelight.com/seed?mediaId={tmdb_int}"
        try:
            s_resp = self.net.http_GET(seed_url, headers=headers)
            seed = json.loads(s_resp.content).get('seed')
            if not seed:
                raise ResolverError('VidKing: Seed empty')
        except Exception as e:
            raise ResolverError(f'VidKing: Seed request failed: {e}')

        media_type = "tv" if is_tv else "movie"
        title, year, imdb_id = "", "", ""
        try:
            meta_url = f"https://db.speedracelight.com/3/{media_type}/{tmdb_int}?append_to_response=external_ids"
            m_resp = self.net.http_GET(meta_url, headers=headers)
            m_data = json.loads(m_resp.content)
            title = m_data.get('title') or m_data.get('name', '')
            rel_date = m_data.get('release_date') or m_data.get('first_air_date', '')
            year = rel_date[:4] if len(rel_date) >= 4 else ""
            imdb_id = m_data.get('external_ids', {}).get('imdb_id', '')
        except Exception:
            pass

        for s_name, endpoint, extra_params in self.SERVERS:
            api_url = f"https://api.speedracelight.com/{endpoint}"
            params = {
                "title": title,
                "mediaType": media_type,
                "year": str(year),
                "episodeId": str(episode) if is_tv else "1",
                "seasonId": str(season) if is_tv else "1",
                "tmdbId": str(tmdb_int),
                "imdbId": str(imdb_id),
                "enc": "2",
                "seed": seed
            }
            params.update(extra_params)
            full_api_url = f"{api_url}?{urllib_parse.urlencode(params)}"

            try:
                resp = self.net.http_GET(full_api_url, headers=headers)
                enc_payload = resp.content
                if not enc_payload or 'STREAMCRYPTO_SEED_INVALID' in enc_payload:
                    continue

                decrypted_json = self._decrypt_payload(enc_payload, seed, tmdb_int)
                payload_data = json.loads(decrypted_json)

                stream_url = payload_data.get('playlist')

                if not stream_url and payload_data.get('sources'):
                    sources_list = payload_data['sources']
                    for s in sorted(sources_list, key=lambda x: int(re.sub(r'\D', '', x.get('quality', '0')) or 0), reverse=True):
                        if s.get('url'):
                            stream_url = s['url']
                            break

                if not stream_url and payload_data.get('url'):
                    stream_url = payload_data['url']

                if stream_url:
                    stream_headers = {
                        'User-Agent': common.RAND_UA,
                        'Referer': 'https://www.vidking.net/',
                        'Origin': 'https://www.vidking.net',
                        'verifypeer': 'false'
                    }
                    playable_url = stream_url + helpers.append_headers(stream_headers)

                    subtitles = {}
                    for sub_item in payload_data.get('subtitles', []):
                        s_url = sub_item.get('url') or sub_item.get('file')
                        s_label = sub_item.get('display') or sub_item.get('language') or sub_item.get('lang') or 'English'
                        if s_url:
                            if s_label == 'eng': s_label = 'English'
                            elif s_label == 'eng (2)': s_label = 'English 2'
                            elif s_label == 'spa': s_label = 'Spanish'
                            subtitles[s_label] = s_url

                    if subs:
                        return playable_url, subtitles
                    return playable_url
            except Exception:
                continue

        raise ResolverError('VidKing: All source endpoints failed to provide a valid stream')

    @classmethod
    def _ci(cls, l):
        l = l & 0xFFFFFFFF
        l ^= (l >> 16)
        l = (l * 2246822507) & 0xFFFFFFFF
        l ^= (l >> 13)
        l = (l * 3266489909) & 0xFFFFFFFF
        l ^= (l >> 16)
        return l & 0xFFFFFFFF

    @classmethod
    def _ps(cls, l, o):
        l = l & 0xFFFFFFFF
        o = o & 31
        return l if o == 0 else ((l << o) | (l >> (32 - o))) & 0xFFFFFFFF

    @classmethod
    def _vf(cls, l):
        o = 2166136261
        for ch in l:
            o = ((o ^ ord(ch)) * 16777619) & 0xFFFFFFFF
        return cls._ci(o)

    @classmethod
    def _Nf(cls, l, o, e):
        return (((l ^ o) & 0xFFFFFFFF) | ((l & o & e) & 0xFFFFFFFF)) & 0xFFFFFFFF

    @classmethod
    def _Rf(cls, seed, tmdb_id):
        e = {}
        i = cls._ci(cls._vf(seed) ^ cls._ci((tmdb_id & 0xFFFFFFFF) ^ cls.MS))
        for r in range(cls.SF):
            n = i % cls.JS
            i = cls._ps((i + cls.MS) & 0xFFFFFFFF, 7 + (r & 7))
            e[n] = (i ^ cls._ci(i)) & 0xFFFFFFFF
            i = cls._ci((i + n) & 0xFFFFFFFF)
        return {'S': e, 'acc': cls._ci(i ^ 2779096485)}

    @classmethod
    def _Cf(cls, state, o):
        e = state['S']
        i = state['acc']
        r = i % cls.JS
        n = 0xFFFFFFFF if r in e else 0
        u = e.get(r, 0)
        d = (cls.MS * (o + 1)) & 0xFFFFFFFF
        g = cls._Nf(i, (u ^ d) & 0xFFFFFFFF, n)
        g = (cls._ps((g + i) & 0xFFFFFFFF, r & 31) ^ cls._ps(i, (r * 7) & 31)) & 0xFFFFFFFF
        i = cls._ci((g + cls.MS) & 0xFFFFFFFF)
        e[r] = i
        state['acc'] = i
        return i

    @classmethod
    def _xf(cls, seed, tmdb_id, length):
        state = cls._Rf(seed, tmdb_id)
        r = bytearray(length)
        u, n = 0, 0
        while u < length:
            d = cls._Cf(state, n)
            n += 1
            r[u] = d & 0xFF
            u += 1
            if u < length:
                r[u] = (d >> 8) & 0xFF
                u += 1
            if u < length:
                r[u] = (d >> 16) & 0xFF
                u += 1
            if u < length:
                r[u] = (d >> 24) & 0xFF
                u += 1
        return r

    @classmethod
    def _decrypt_payload(cls, ciphertext_b64, seed, tmdb_id):
        pad = len(ciphertext_b64) % 4
        if pad != 0:
            ciphertext_b64 += '=' * (4 - pad)
        raw = bytearray(base64.urlsafe_b64decode(ciphertext_b64))
        keystream = cls._xf(seed, tmdb_id, len(raw))
        for idx in range(len(raw)):
            raw[idx] ^= keystream[idx]
        if raw[:4] != cls.MAGIC_HEADER:
            raise ValueError(f"VidKing: Header mismatch: expected {cls.MAGIC_HEADER}, got {raw[:4]}")
        return raw[4:].decode('utf-8')

    def get_url(self, host, media_id):
        if media_id.startswith('http'):
            return media_id
        return f"https://www.vidking.net/embed/{media_id}"
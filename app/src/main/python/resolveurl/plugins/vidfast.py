# -*- coding: utf-8 -*-
"""
    VidFast / VidUp Plugin for ResolveURL (Self-Contained with Token Generator)
    Copyright (C) 2026 Poobi
"""

import os
import re
import json
import io
import base64
import time
import requests
from urllib.parse import urlparse, urljoin, parse_qs
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError

import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

_CACHED_WASM_BYTES = None
_CACHED_WASM_KEY = None
_CACHED_STREAM_TOKEN = ""
_CACHED_TOKEN_EXPIRY = 0


def _instantiate_wasm_memory(wasm_bytes):
    import pywasm
    runtime = pywasm.core.Runtime() if hasattr(pywasm, 'core') else pywasm.Runtime()
    module_desc = getattr(pywasm, 'ModuleDesc', None) or getattr(pywasm.core, 'ModuleDesc', None)
    
    if module_desc:
        module = module_desc.from_reader(io.BytesIO(wasm_bytes))
        instance = runtime.instance(module)
    else:
        instance = runtime.instance_from_file(io.BytesIO(wasm_bytes))
    return runtime, instance


class VidFastResolver(ResolveUrl):
    name = 'VidFast'
    domains = ['vidfast.pro', 'vidfast.vc', 'vidup.to', 'ythd.org']
    pattern = r'(?://|\.)((?:vidfast\.(?:pro|vc)|vidup\.to|ythd\.org))/(?:movie/|tv/|embed/)?([0-9a-zA-Z/-]+(?:\?[^"\'>\s]+)?)'

    def get_media_url(self, host, media_id, subs=False):
        global _CACHED_WASM_KEY, _CACHED_WASM_BYTES, _CACHED_STREAM_TOKEN, _CACHED_TOKEN_EXPIRY
        ua = common.RAND_UA
        numeric_id = re.sub(r'\D', '', media_id)
        if not numeric_id:
            raise ResolverError('VidFast: TMDB ID not found in URL')

        content_type = "tv" if "tv" in media_id else "movie"
        bridge_url = f"https://ythd.org/vs_src.php?type={content_type}&id={numeric_id}"

        s = requests.Session()
        s.headers.update({
            'User-Agent': ua,
            'Referer': f'https://{host}/',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
        })

        try:
            resp = s.get(bridge_url, verify=False, timeout=10)
            data = resp.json()
            embed_url = data.get('src')
            if not embed_url:
                raise ResolverError('VidFast: Bridge returned no embed URL')

            parsed = urlparse(embed_url)
            origin_host = f"{parsed.scheme}://{parsed.netloc}"

            s.headers.update({'Referer': 'https://ythd.org/', 'Origin': 'https://ythd.org'})
            r1 = s.get(embed_url, timeout=12, verify=False)
            html1 = r1.text

            cfg_match = re.search(r'window\.CFG\s*=\s*({.+?});', html1)
            if not cfg_match:
                raise ResolverError('VidFast: window.CFG not found')

            cfg_data = json.loads(cfg_match.group(1))
            player_path = cfg_data.get('playerUrl')
            if not player_path:
                raise ResolverError('VidFast: playerUrl not found in CFG')

            inner_player_url = urljoin(embed_url, player_path)
            r2 = s.get(inner_player_url, headers={'Referer': embed_url, 'Origin': origin_host}, timeout=12, verify=False)
            html2 = r2.text

            config_match = re.search(r'window\.CONFIG\s*=\s*({.+?});', html2, re.DOTALL)
            if not config_match:
                raise ResolverError('VidFast: window.CONFIG not found')

            config_data = json.loads(config_match.group(1))
            stream_api = config_data.get('api', '').replace(r'\u0026', '&')
            if not stream_api:
                raise ResolverError('VidFast: stream API endpoint not found')

            r3 = s.get(stream_api, headers={'Referer': inner_player_url, 'Origin': origin_host, 'Accept': 'application/json'}, timeout=10, verify=False)
            api_json = r3.json()

            enc_b64 = api_json.get('data', {}).get('stream_urls')
            vs_obj = api_json.get('vs', {})
            wasm_url = vs_obj.get('wasm_url')

            if not enc_b64 or not wasm_url:
                raise ResolverError('VidFast: Stream payload or WASM URL missing')

            # 7. Decrypt via WASM
            parsed_query = parse_qs(urlparse(wasm_url).query)
            current_w_key = parsed_query.get('w', [wasm_url])[0]

            if _CACHED_WASM_KEY != current_w_key or _CACHED_WASM_BYTES is None:
                wasm_resp = s.get(wasm_url, headers={'Referer': inner_player_url}, timeout=10, verify=False)
                _CACHED_WASM_BYTES = wasm_resp.content
                _CACHED_WASM_KEY = current_w_key

            runtime, m = _instantiate_wasm_memory(_CACHED_WASM_BYTES)
            raw_enc = base64.b64decode(enc_b64)
            enc_len = len(raw_enc)

            ptr = runtime.invocate(m, 'alloc', [enc_len])[0]
            memory_data = None
            for attr_path in ['machine.store.mems', 'memory_list', 'store.mems']:
                try:
                    obj = runtime
                    for part in attr_path.split('.'):
                        obj = getattr(obj, part)
                    memory_data = obj[0].data
                    break
                except Exception:
                    pass

            if memory_data is None:
                memory_data = m.exports.memory.buffer

            for i in range(enc_len):
                memory_data[ptr + i] = raw_enc[i]

            out_len = runtime.invocate(m, 'decrypt', [ptr, enc_len])[0]
            dec_bytes = bytearray(out_len)
            for i in range(out_len):
                dec_bytes[i] = memory_data[ptr + 12 + i]

            plaintext = dec_bytes.decode('utf-8', errors='replace')
            m3u8_candidates = [l.strip() for l in plaintext.splitlines() if l.strip() and l.startswith('http')]

            if not m3u8_candidates:
                raise ResolverError('VidFast: No stream URLs extracted from WASM plaintext')

            preferred_master_url = m3u8_candidates[1] if len(m3u8_candidates) > 1 else m3u8_candidates[0]
            stream_url = preferred_master_url.split('|')[0]
            parsed_url = urlparse(stream_url)
            stream_origin = f"{parsed_url.scheme}://{parsed_url.netloc}"

            token = ""
            now = time.time()
            if _CACHED_STREAM_TOKEN and now < _CACHED_TOKEN_EXPIRY:
                token = _CACHED_STREAM_TOKEN
            else:
                token_url = f"{stream_origin}/generate.php"
                try:
                    t_resp = s.get(token_url, headers={'User-Agent': ua, 'Referer': inner_player_url, 'Origin': origin_host}, timeout=8, verify=False)
                    if t_resp.status_code == 200:
                        raw_token = t_resp.text.strip()
                        try:
                            token_json = json.loads(raw_token)
                            token = token_json.get('token') or token_json.get('data') or raw_token
                        except Exception:
                            token = raw_token
                        if token:
                            _CACHED_STREAM_TOKEN = token
                            _CACHED_TOKEN_EXPIRY = now + 7200
                except Exception:
                    if _CACHED_STREAM_TOKEN:
                        token = _CACHED_STREAM_TOKEN

            if token:
                delim = "&" if "?" in stream_url else "?"
                final_url = f"{stream_url}{delim}token={token}"
            else:
                final_url = stream_url

            delim_loc = "&" if "?" in final_url else "?"
            final_url_with_bypass = f"{final_url}{delim_loc}bypass_localize=true"

            src_headers = {
                'Referer': inner_player_url,
                'Origin': origin_host,
                'User-Agent': ua,
                'verifypeer': 'false'
            }

            playable_url = final_url_with_bypass + helpers.append_headers(src_headers)
            if subs:
                return playable_url, {}
            return playable_url

        except Exception as e:
            raise ResolverError(f'VidFast Error: {str(e)}')

    def get_url(self, host, media_id):
        return f"https://{host}/{media_id}"
# -*- coding: utf-8 -*-
"""
    VidSrc Plugin for ResolveURL
    Copyright (C) 2026 Poobi / Gujal
"""

import io
import os
import re
import json
import base64
import requests
from urllib.parse import urlparse, urljoin, parse_qsl, parse_qs
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError

import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

_CACHED_WASM_KEY = None
_CACHED_WASM_BYTES = None


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


class VidSrcResolver(ResolveUrl):
    name = 'VidSrc'
    domains = [
        'vidsrc.me', 'vidsrc.in', 'vidsrc.to', 'vidsrc.net',
        'vidsrc.xyz', 'vidsrcme.ru', 'vidsrc.stream', 'vidsrc.icu',
        'cloudorchestranova.com', 'vidsrc.mov', 'vsembed.ru', 'vidsrc.pm'
    ]
    pattern = r'(?://|\.)((?:vidsrc\.(?:me|in|to|net|xyz|stream|icu|mov|pm)|vidsrcme\.ru|vsembed\.ru|cloudorchestranova\.com))/(?:embed/)?((?:(?:movie|tv)/)?[0-9a-zA-Z-/]+(?:\?[^"\'>\s]+)?)'

    def get_media_url(self, host, media_id, subs=False):
        try:
            ua = common.RAND_UA

            s = requests.Session()
            s.headers.update({
                'User-Agent': ua,
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                'Accept-Language': 'en-US,en;q=0.5',
                'Connection': 'keep-alive'
            })

            if not host:
                host = 'vidsrc.me'

            parts = [p for p in media_id.split('/') if p]
            numeric_parts = [p for p in parts if p.isdigit()]
            is_tv = 'tv' in media_id or 'tv' in parts or len(numeric_parts) >= 2 or len(parts) >= 3

            if is_tv:
                if not media_id.startswith('tv/'):
                    clean_id = media_id.replace('movie/', '').replace('tv/', '')
                    media_id = f"tv/{clean_id}"
            else:
                if not media_id.startswith('movie/') and not media_id.startswith('tt'):
                    media_id = f"movie/{media_id}"

            embed_url = f"https://{host}/embed/{media_id}"

            try:
                r1 = s.get(embed_url, timeout=12, allow_redirects=True, verify=False)
            except Exception:
                embed_url = f"https://vidsrcme.ru/embed/{media_id}"
                try:
                    r1 = s.get(embed_url, timeout=12, allow_redirects=True, verify=False)
                except Exception:
                    embed_url = f"https://v2.vidsrc.me/embed/{media_id}"
                    r1 = s.get(embed_url, timeout=12, allow_redirects=True, verify=False)

            final_embed_url = r1.url
            html1 = r1.text

            iframe1 = re.search(r'<iframe[^>]+src=["\']([^"\']+)["\']', html1, re.I)
            if iframe1 and not iframe1.group(1).startswith("${"):
                layer1_url = urljoin(final_embed_url, iframe1.group(1))
                r2 = s.get(layer1_url, headers={'Referer': final_embed_url}, timeout=12, verify=False)
                html2 = r2.text
            else:
                layer1_url = final_embed_url
                html2 = html1

            container_url = None
            data_api_match = re.search(r'data-api=["\']([^"\']+)["\']', html2)
            if data_api_match:
                api_rel = data_api_match.group(1).replace('&amp;', '&')
                api_url = urljoin(layer1_url, api_rel)
                try:
                    api_resp = s.get(
                        api_url,
                        headers={
                            'Referer': layer1_url,
                            'X-Requested-With': 'XMLHttpRequest',
                            'Accept': 'application/json, text/javascript, */*; q=0.01'
                        },
                        timeout=10,
                        verify=False
                    )
                    container_url = api_resp.json().get('src')
                except Exception:
                    pass

            if not container_url:
                iframe2 = re.search(r'<iframe[^>]+src=["\']([^"\']+)["\']', html2, re.I)
                if iframe2:
                    container_url = urljoin(layer1_url, iframe2.group(1))

            if not container_url:
                raise ResolverError('VidSrc: Container URL not found')

            container_host = f"{urlparse(container_url).scheme}://{urlparse(container_url).netloc}"

            r3 = s.get(
                container_url,
                headers={'Referer': layer1_url, 'Origin': container_host},
                timeout=12,
                verify=False
            )
            html3 = r3.text
            active_player_url = container_url

            cfg_match = re.search(r'window\.CFG\s*=\s*({[^;]+});', html3)
            if cfg_match:
                try:
                    cfg_data = json.loads(cfg_match.group(1))
                    player_path = cfg_data.get('playerUrl')
                    if player_path:
                        inner_player_url = urljoin(container_url, player_path)
                        r4 = s.get(
                            inner_player_url,
                            headers={'Referer': container_url, 'Origin': container_host},
                            timeout=12,
                            verify=False
                        )
                        html3 = r4.text
                        active_player_url = inner_player_url
                except Exception:
                    pass

            m3u8_candidates = []
            subtitles_dict = {}

            config_match = re.search(r'window\.CONFIG\s*=\s*({.+?});', html3, re.DOTALL)
            if config_match:
                try:
                    config_data = json.loads(config_match.group(1))

                    if is_tv:
                        stream_base = config_data.get('streamBase', '').replace(r'\u0026', '&')
                        s_num = config_data.get('season')
                        e_num = config_data.get('episode')
                        stream_api = f"{stream_base}&season={s_num}&episode={e_num}&stream_urls"
                    else:
                        stream_api = config_data.get('api', '').replace(r'\u0026', '&')

                    meta_api = config_data.get('metaApi', '').replace(r'\u0026', '&')
                    if subs and meta_api:
                        try:
                            meta_resp = s.get(meta_api, headers={'Referer': active_player_url, 'User-Agent': ua}, timeout=8, verify=False)
                            meta_json = meta_resp.json()
                            for sub in meta_json.get('default_subs', []):
                                s_label = sub.get('lang') or sub.get('code') or 'English'
                                s_url = sub.get('url')
                                if s_url:
                                    subtitles_dict[s_label] = s_url
                        except Exception:
                            pass

                    if stream_api:
                        api_resp = s.get(
                            stream_api,
                            headers={
                                'User-Agent': ua,
                                'Referer': active_player_url,
                                'Origin': container_host,
                                'Accept': 'application/json, text/plain, */*'
                            },
                            timeout=10,
                            verify=False
                        )
                        api_json = api_resp.json()
                        enc_b64 = api_json.get('data', {}).get('stream_urls')
                        vs_obj = api_json.get('vs', {})
                        wasm_url = vs_obj.get('wasm_url')

                        if enc_b64 and wasm_url:
                            try:
                                global _CACHED_WASM_KEY, _CACHED_WASM_BYTES

                                parsed_query = parse_qs(urlparse(wasm_url).query)
                                current_w_key = parsed_query.get('w', [wasm_url])[0]

                                if _CACHED_WASM_KEY != current_w_key or _CACHED_WASM_BYTES is None:
                                    wasm_resp = s.get(
                                        wasm_url,
                                        headers={'Referer': active_player_url},
                                        timeout=10,
                                        verify=False
                                    )
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
                                m3u8_candidates.extend([
                                    l.strip() for l in plaintext.splitlines()
                                    if l.strip() and l.startswith('http')
                                ])
                            except Exception:
                                pass
                        else:
                            val = api_json.get('data', {}).get('stream_urls')
                            if isinstance(val, list):
                                m3u8_candidates.extend(val)
                except Exception:
                    pass

            if not m3u8_candidates:
                for pattern in [
                    r'["\'](https?://[^"\']+\.m3u8[^"\']*)["\']',
                    r'["\'](https?://[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}/pl/[^"\']+)["\']'
                ]:
                    for match in re.finditer(pattern, html3):
                        cand = match.group(1).replace(r'\/', '/').strip('"\'')
                        if cand.startswith('//'): cand = 'https:' + cand
                        if cand.startswith('http'):
                            m3u8_candidates.append(cand)

            if not m3u8_candidates:
                raise ResolverError('VidSrc: No stream URLs found')

            preferred_master_url = m3u8_candidates[1] if len(m3u8_candidates) > 1 else m3u8_candidates[0]
            stream_url = preferred_master_url.split('|')[0]
            parsed_url = urlparse(stream_url)
            stream_origin = f"{parsed_url.scheme}://{parsed_url.netloc}"

            token_url = f"{stream_origin}/generate.php"
            token = ""
            try:
                t_resp = s.get(
                    token_url,
                    headers={'User-Agent': ua, 'Referer': active_player_url, 'Origin': container_host},
                    timeout=8,
                    verify=False
                )
                if t_resp.status_code == 200:
                    raw_token = t_resp.text.strip()
                    try:
                        token_json = json.loads(raw_token)
                        token = token_json.get('token') or token_json.get('data') or raw_token
                    except Exception:
                        token = raw_token
            except Exception:
                pass

            if token:
                delim = "&" if "?" in stream_url else "?"
                final_url = f"{stream_url}{delim}token={token}"
            else:
                final_url = stream_url

            delim_loc = "&" if "?" in final_url else "?"
            final_url_with_bypass = f"{final_url}{delim_loc}bypass_localize=true"

            src_headers = {
                'Referer': active_player_url,
                'Origin': container_host,
                'User-Agent': ua,
                'verifypeer': 'false'
            }

            playable_url = final_url_with_bypass + helpers.append_headers(src_headers)

            if subs and subtitles_dict:
                return playable_url, subtitles_dict
            return playable_url

        except Exception as e:
            raise ResolverError(f'VidSrc: {str(e)}')

    def get_url(self, host, media_id):
        if not host or not host.startswith('http'):
            host = host or 'vidsrc.me'
        if not media_id.startswith('movie/') and not media_id.startswith('tv/') and not media_id.startswith('tt'):
            parts = [p for p in media_id.split('/') if p]
            if len(parts) >= 2:
                media_id = f"tv/{media_id}"
            else:
                media_id = f"movie/{media_id}"
        return f"https://{host}/embed/{media_id}"
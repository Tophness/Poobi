# -*- coding: utf-8 -*-
"""
    VidSrc Provider for Poobi
    WASM Decryption & Host-Token Generation Engine (with URL-Encoded Headers, Caching & SSL Bypasses)
"""

import io
import os
import re
import json
import base64
import requests
import traceback
from urllib.parse import urlparse, urljoin, unquote, quote_plus, parse_qs

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


class source:
    def __init__(self):
        self.results = []
        self.domains = [
            'vidsrc.me', 'vidsrc.in', 'vidsrc.to', 'vidsrc.net',
            'vidsrc.xyz', 'vidsrcme.ru', 'vidsrc.stream', 'vidsrc.icu',
            'cloudorchestranova.com'
        ]
        self.base_link = 'https://vidsrcme.ru'
        self.fallback_link = 'https://v2.vidsrc.me'
        self.ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0'

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        media_id = imdb if imdb and imdb != '0' else str(tmdb)
        return media_id

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        media_id = imdb if imdb and imdb != '0' else str(tmdb)
        return media_id

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        try:
            s_num = int(season)
            e_num = int(episode)
            return f"{url}/{s_num}/{e_num}"
        except Exception:
            return f"{url}/{season}/{episode}"

    def sources(self, url, hostDict):
        self.results = []
        if not url:
            return []

        try:
            content = 'movie' if '/' not in url else 'tv'
            embed_url = f"{self.base_link}/embed/{content}/{url}"
            
            s = requests.Session()
            s.headers.update({
                'User-Agent': self.ua,
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                'Accept-Language': 'en-US,en;q=0.5',
                'Connection': 'keep-alive'
            })

            try:
                r1 = s.get(embed_url, timeout=12, allow_redirects=True, verify=False)
            except Exception as e:
                embed_url = f"{self.fallback_link}/embed/{content}/{url}"
                r1 = s.get(embed_url, timeout=12, allow_redirects=True, verify=False)

            final_embed_url = r1.url
            embed_host = f"{urlparse(final_embed_url).scheme}://{urlparse(final_embed_url).netloc}"
            html1 = r1.text

            container_url = None
            data_api_match = re.search(r'data-api=["\']([^"\']+)["\']', html1)
            if data_api_match:
                data_api_rel = data_api_match.group(1).replace('&amp;', '&')
                data_api_url = urljoin(embed_host, data_api_rel)
                try:
                    api_resp = s.get(
                        data_api_url, 
                        headers={
                            'Referer': final_embed_url, 
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
                iframe_match = re.search(r'<iframe[^>]+src=["\']([^"\']+)["\']', html1, re.I)
                if iframe_match:
                    container_url = iframe_match.group(1)
                    if container_url.startswith('//'):
                        container_url = 'https:' + container_url
                    elif container_url.startswith('/'):
                        container_url = urljoin(embed_host, container_url)

            if not container_url:
                return []

            container_host = f"{urlparse(container_url).scheme}://{urlparse(container_url).netloc}"
            r2 = s.get(
                container_url, 
                headers={'Referer': final_embed_url, 'Origin': embed_host}, 
                timeout=12,
                verify=False
            )
            html2 = r2.text

            inner_player_url = None
            cfg_match = re.search(r'window\.CFG\s*=\s*({[^;]+});', html2)
            if cfg_match:
                try:
                    cfg_data = json.loads(cfg_match.group(1))
                    player_path = cfg_data.get('playerUrl')
                    if player_path:
                        inner_player_url = urljoin(container_host, player_path)
                except Exception:
                    pass

            if not inner_player_url:
                p_match = re.search(r'["\']playerUrl["\']\s*:\s*["\']([^"\']+)["\']', html2)
                if p_match:
                    inner_player_url = urljoin(container_host, p_match.group(1))

            if not inner_player_url:
                inner_player_url = container_url
                html3 = html2
            else:
                r3 = s.get(
                    inner_player_url, 
                    headers={'Referer': container_url, 'Origin': container_host}, 
                    timeout=12,
                    verify=False
                )
                html3 = r3.text

            m3u8_candidates = []

            config_match = re.search(r'window\.CONFIG\s*=\s*({[^;]+});', html3)
            if config_match:
                try:
                    config_data = json.loads(config_match.group(1))
                    stream_api = config_data.get('api', '').replace(r'\u0026', '&')
                    if stream_api:
                        api_resp = s.get(
                            stream_api, 
                            headers={
                                'User-Agent': self.ua,
                                'Referer': inner_player_url,
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
                                        headers={'Referer': inner_player_url},
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
                                for attr_path in ['machine.store.mems', 'machine.memory_list', 'memory_list', 'store.mems']:
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
                                urls_in_plain = [
                                    l.strip() for l in plaintext.splitlines()
                                    if l.strip() and l.startswith('http')
                                ]
                                m3u8_candidates.extend(urls_in_plain)
                            except Exception:
                                urls_in_raw = re.findall(r'https?://[^\s\'"<>]+?\.m3u8[^\s\'"<>]*', api_resp.text)
                                m3u8_candidates.extend(urls_in_raw)
                        else:
                            val = api_json.get('data', {}).get('stream_urls')
                            if isinstance(val, list):
                                m3u8_candidates.extend(val)
                except Exception:
                    pass

            if not m3u8_candidates:
                for pattern in [
                    r'["\'](https?://[^"\']+\.m3u8[^"\']*)["\']',
                    r'["\'](https?://[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}/pl/[^"\']+)["\']',
                    r'file\s*:\s*["\']([^"\']+\.(?:m3u8|mp4)[^"\']*)["\']'
                ]:
                    for match in re.finditer(pattern, html3):
                        cand = match.group(1).replace(r'\/', '/').strip('"\'')
                        if cand.startswith('//'): cand = 'https:' + cand
                        if cand.startswith('http'):
                            m3u8_candidates.append(cand)

            seen = set()
            unique_candidates = []
            for c in m3u8_candidates:
                clean_c = c.split('|')[0]
                if clean_c not in seen:
                    seen.add(clean_c)
                    unique_candidates.append(c)

            if not unique_candidates:
                return []

            token_cache = {}
            all_play_urls = []
            all_alt_names = []

            for idx, stream_url in enumerate(unique_candidates):
                parsed_url = urlparse(stream_url)
                stream_origin = f"{parsed_url.scheme}://{parsed_url.netloc}"

                if stream_origin in token_cache:
                    token = token_cache[stream_origin]
                else:
                    token_url = f"{stream_origin}/generate.php"
                    token = ""
                    try:
                        t_resp = s.get(
                            token_url, 
                            headers={
                                'User-Agent': self.ua,
                                'Referer': inner_player_url,
                                'Origin': container_host
                            }, 
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

                            token_cache[stream_origin] = token
                    except Exception:
                        pass

                if token:
                    delim = "&" if "?" in stream_url else "?"
                    final_url = f"{stream_url}{delim}token={token}"
                else:
                    final_url = stream_url

                delim_loc = "&" if "?" in final_url else "?"
                final_url_with_bypass = f"{final_url}{delim_loc}bypass_localize=true"

                play_url = (
                    f"{final_url_with_bypass}|Referer={inner_player_url}"
                    f"&Origin={container_host}"
                    f"&User-Agent={self.ua}"
                )

                all_play_urls.append(play_url)
                server_label = f"Server {idx + 1} (Primary)" if idx == 0 else f"Server {idx + 1} (Backup)"
                all_alt_names.append(server_label)

            mirror_count = len(all_play_urls)
            self.results.append({
                'source': 'VidSrc',
                'title': f'VidSrc ({mirror_count} Servers)',
                'provider': 'VidSrc Network',
                'quality': '1080p',
                'url': all_play_urls[0],
                'direct': True,
                'is_video': True,
                'info': f'1080p | Adaptive HLS | {mirror_count} Mirrors',
                'audio': 'English',
                'languages_display': 'English',
                'alternative_urls': all_play_urls if mirror_count > 1 else [],
                'alternative_names': all_alt_names if mirror_count > 1 else []
            })

        except Exception as e:
            traceback.print_exc()

        return self.results

    def resolve(self, url):
        return url
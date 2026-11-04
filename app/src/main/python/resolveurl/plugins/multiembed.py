# -*- coding: utf-8 -*-
"""
    Plugin for ResolveURL - MultiEmbed / StreamingNow
    Copyright (C) 2026 Poobi
"""

import re
from urllib.parse import urlparse, parse_qs, quote_plus
from resolveurl import common
from resolveurl.resolver import ResolveUrl, ResolverError
from resolveurl.hmf import HostedMediaFile

try:
    from curl_cffi import requests
except ImportError:
    import requests

try:
    import js2py
except ImportError:
    js2py = None

import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

DEAD_DOMAINS = ['movearnpre.com', 'bigwarp.xxx', 'kokostream.net']


def resolve_vipstream_playerjs(session, vip_url, landing_url, ua):
    if not js2py:
        return None
    try:
        headers = {'User-Agent': ua, 'Referer': landing_url}
        if hasattr(session, 'get') and 'impersonate' in session.get.__code__.co_varnames:
            v_resp = session.get(vip_url, headers=headers, impersonate="chrome120", timeout=8, verify=False)
        else:
            v_resp = session.get(vip_url, headers=headers, timeout=8, verify=False)

        script_blocks = re.findall(r'<script[^>]*>(.*?)</script>', v_resp.text, re.DOTALL | re.I)
        target_sc = next((sc for sc in script_blocks if 'Playerjs' in sc or 'eval(' in sc), "")

        if target_sc:
            context = js2py.EvalJs()
            context.execute("""
                var capturedConfig = null;
                function Playerjs(config) { capturedConfig = config; }
                var window = { Playerjs: Playerjs };
                var global = {};
            """)
            context.execute(target_sc)
            cfg = context.capturedConfig.to_dict() if context.capturedConfig else None
            if cfg and cfg.get('file'):
                stream_file = cfg['file']
                delim = "&" if "?" in stream_file else "?"
                return f"{stream_file}{delim}bypass_localize=true|User-Agent={quote_plus(ua)}&Referer=https%3A%2F%2Fstreamingnow.mov%2F&Origin=https%3A%2F%2Fstreamingnow.mov"
    except Exception:
        pass
    return None


class MultiEmbedResolver(ResolveUrl):
    name = 'MultiEmbed'
    domains = ['multiembed.mov', 'multiembed.co', 'multiembed.si', 'streamingnow.mov']
    pattern = r'(?://|\.)((?:multiembed|streamingnow)\.(?:mov|co|si))/(.*)'

    SERVER_PRIORITY = [
        'vipstream-f', 'vipstream-a', 'vipstream', 'filemoon',
        'voe', 'streamwish', 'lulustream', 'gdrive', 'vidmoly',
        'doodstream', 'filelions', 'savefiles', 'bigwarp', 'mixdrop'
    ]

    def get_media_url(self, host, media_id, subs=False):
        clean_input = media_id.replace('&amp;', '&')
        ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
        session = requests.Session()

        # Direct VIPStream resolution if passed directly
        if 'vipstream_vfx.php' in clean_input or 'vipstream_vfx.php' in host:
            full_vip_url = clean_input if clean_input.startswith('http') else f"https://streamingnow.mov/{clean_input.lstrip('/')}"
            resolved = resolve_vipstream_playerjs(session, full_vip_url, 'https://streamingnow.mov/', ua)
            if resolved:
                return resolved
            raise ResolverError('MultiEmbed: vipstream_vfx resolution failed')

        s_req = None
        e_req = None

        if 'streamingnow.mov' in host or 'play=' in clean_input:
            play_match = re.search(r'[?&]play=([^&]+)', clean_input)
            play_token = play_match.group(1) if play_match else None
            page_url = f"https://streamingnow.mov/?play={play_token}" if play_token else f"https://{host}/{clean_input}"
        else:
            query_str = clean_input.split('?')[-1] if '?' in clean_input else clean_input
            params = parse_qs(query_str)

            vid_id = params.get('video_id', [None])[0] or params.get('id', [None])[0] or params.get('tmdb', [None])[0]
            s_num = params.get('s', [None])[0] or params.get('season', [None])[0]
            e_num = params.get('e', [None])[0] or params.get('episode', [None])[0]

            if s_num and str(s_num).isdigit(): s_req = int(s_num)
            if e_num and str(e_num).isdigit(): e_req = int(e_num)

            if not vid_id:
                m = re.search(r'(tt\d{7,8}|\d{4,8})', clean_input)
                vid_id = m.group(1) if m else None

            if not vid_id:
                raise ResolverError('MultiEmbed: Could not extract video identifier')

            if s_num and e_num:
                page_url = f"https://multiembed.mov/?video_id={vid_id}&s={s_num}&e={e_num}"
            else:
                page_url = f"https://multiembed.mov/?video_id={vid_id}"

        req_headers = {
            'User-Agent': ua,
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9',
            'Referer': 'https://google.com'
        }

        try:
            if hasattr(session, 'get') and 'impersonate' in session.get.__code__.co_varnames:
                resp_init = session.get(page_url, headers=req_headers, impersonate="chrome120", allow_redirects=True, timeout=12, verify=False)
            else:
                resp_init = session.get(page_url, headers=req_headers, allow_redirects=True, timeout=12, verify=False)
            landing_url = resp_init.url
        except Exception as e:
            raise ResolverError(f'MultiEmbed: Initial connection failed: {e}')

        play_match = re.search(r'[?&]play=([^&]+)', landing_url) or re.search(r'[?&]play=([^&]+)', resp_init.text)
        if not play_match:
            raise ResolverError('MultiEmbed: StreamingNow play token not received')

        play_token = play_match.group(1)

        api_headers = {
            'User-Agent': ua,
            'Referer': landing_url,
            'Origin': 'https://streamingnow.mov',
            'X-Requested-With': 'XMLHttpRequest'
        }

        try:
            if hasattr(session, 'post') and 'impersonate' in session.post.__code__.co_varnames:
                resp_servers = session.post(
                    "https://streamingnow.mov/response.php",
                    data={'token': play_token},
                    headers=api_headers,
                    impersonate="chrome120",
                    timeout=10,
                    verify=False
                )
            else:
                resp_servers = session.post(
                    "https://streamingnow.mov/response.php",
                    data={'token': play_token},
                    headers=api_headers,
                    timeout=10,
                    verify=False
                )
            servers_html = resp_servers.text
        except Exception as e:
            raise ResolverError(f'MultiEmbed: Failed to retrieve server list: {e}')

        raw_lis = re.findall(r'<li\s+([^>]+)>(.*?)</li>', servers_html, re.DOTALL)
        server_items = []

        for attrs_str, content_html in raw_lis:
            m_id = re.search(r'data-id=["\']([^"\']+)["\']', attrs_str)
            m_srv = re.search(r'data-server=["\']([^"\']+)["\']', attrs_str)
            if not m_id or not m_srv:
                continue

            data_id = m_id.group(1)
            server_id = m_srv.group(1)

            srv_name_m = re.search(r'(?:class=["\']server-name["\'][^>]*>|<span[^>]+class=["\']server-name["\'][^>]*>)(?:<div[^>]*></div>)?\s*([a-zA-Z0-9_\-\s]+?)\s*</(?:div|span)>', content_html, re.DOTALL)
            clean_text = re.sub(r'<[^>]+>', ' ', content_html).strip()
            clean_text = re.sub(r'\s+', ' ', clean_text)

            if srv_name_m:
                server_name = srv_name_m.group(1).strip()
            else:
                first_word = clean_text.split(' ')[0] if clean_text else f"Server {server_id}"
                server_name = first_word

            release_title = clean_text.replace(server_name, '').strip()

            if s_req is not None and e_req is not None:
                ep_match = re.search(r'[sS](\d+)[eE](\d+)|Season\s*(\d+)\s*Episode\s*(\d+)', release_title, re.I)
                if ep_match:
                    s_found = int(ep_match.group(1) or ep_match.group(3))
                    e_found = int(ep_match.group(2) or ep_match.group(4))
                    if s_found != s_req or e_found != e_req:
                        continue

            server_items.append({
                'data_id': data_id,
                'server_id': server_id,
                'server_name': server_name,
                'release_title': release_title
            })

        if not server_items:
            raise ResolverError('MultiEmbed: No valid matching servers found')

        def server_rank(item):
            s_name = item['server_name'].lower()
            for rank, pref in enumerate(self.SERVER_PRIORITY):
                if pref in s_name:
                    return rank
            return 99

        ranked_servers = sorted(server_items, key=server_rank)

        alternative_urls = []
        alternative_names = []
        primary_url = None

        # Resolve mirrors: init=1 for initial server, init=0 for subsequent mirrors
        for idx, item in enumerate(ranked_servers):
            data_id = item['data_id']
            server_id = item['server_id']
            server_name = item['server_name']

            init_val = 1 if idx == 0 else 0
            pv_url = f"https://streamingnow.mov/playvideo.php?video_id={quote_plus(data_id)}&server_id={server_id}&token={quote_plus(play_token)}&init={init_val}"

            try:
                if hasattr(session, 'get') and 'impersonate' in session.get.__code__.co_varnames:
                    pv_resp = session.get(
                        pv_url,
                        headers={'User-Agent': ua, 'Referer': landing_url},
                        impersonate="chrome120",
                        timeout=8,
                        verify=False
                    )
                else:
                    pv_resp = session.get(
                        pv_url,
                        headers={'User-Agent': ua, 'Referer': landing_url},
                        timeout=8,
                        verify=False
                    )

                if 'captcha-message' in pv_resp.text:
                    continue

                iframe_match = re.search(r'<iframe[^>]+src=["\']([^"\']+)["\']', pv_resp.text, re.I)
                upstream_embed = None

                if iframe_match:
                    upstream_embed = iframe_match.group(1).strip()
                elif 'vipstream_vfx.php' in pv_resp.text or 'Playerjs' in pv_resp.text:
                    upstream_embed = pv_url

                if not upstream_embed or not upstream_embed.startswith('http'):
                    continue

                if any(bad in upstream_embed.lower() for bad in DEAD_DOMAINS):
                    continue

                raw_resolved = None

                # 1. VIPStream Playerjs extraction
                if 'vipstream_vfx.php' in upstream_embed:
                    raw_resolved = resolve_vipstream_playerjs(session, upstream_embed, landing_url, ua)
                else:
                    # 2. Standard HMF extraction for external hosts
                    try:
                        hmf = HostedMediaFile(url=upstream_embed, subs=subs)
                        if hmf.valid_url():
                            raw_resolved = hmf.resolve()
                    except Exception:
                        raw_resolved = None

                if raw_resolved:
                    url_str = raw_resolved.get('url') if isinstance(raw_resolved, dict) else raw_resolved
                    if isinstance(url_str, str) and (url_str.startswith('http') or url_str.startswith('file://')):
                        clean_url = url_str.split('|')[0]
                        if not primary_url:
                            primary_url = url_str
                        else:
                            if clean_url not in alternative_urls and clean_url != primary_url.split('|')[0]:
                                alternative_urls.append(clean_url)
                                alternative_names.append(f"Mirror: {server_name}")

                # Once primary stream is found and we have 4 alternative mirrors, proceed immediately
                if primary_url and len(alternative_urls) >= 4:
                    break

            except Exception:
                continue

        if primary_url:
            clean_primary = primary_url.get('url') if isinstance(primary_url, dict) else primary_url
            self.alternative_urls = alternative_urls
            self.alternative_names = alternative_names
            return str(clean_primary)

        raise ResolverError('MultiEmbed: All servers failed to resolve a playable stream')

    def get_url(self, host, media_id):
        if media_id.startswith('http'):
            return media_id
        return f"https://{host}/{media_id}"
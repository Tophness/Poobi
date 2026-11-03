# -*- coding: utf-8 -*-
"""
    Plugin for ResolveURL - MultiEmbed / StreamingNow
    Copyright (C) 2026 Poobi
"""

import re
import requests
from urllib.parse import urlparse, parse_qs, quote_plus
from resolveurl import common
from resolveurl.resolver import ResolveUrl, ResolverError
from resolveurl.hmf import HostedMediaFile

import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)


class MultiEmbedResolver(ResolveUrl):
    name = 'MultiEmbed'
    domains = ['multiembed.mov', 'multiembed.co', 'multiembed.si', 'streamingnow.mov']
    pattern = r'(?://|\.)((?:multiembed|streamingnow)\.(?:mov|co|si))/(.*)'

    SERVER_PRIORITY = [
        'gdrive', 'filemoon', 'voe', 'lulustream', 'vidmoly', 'doodstream',
        'filelions', 'streamwish', 'savefiles', 'bigwarp', 'mixdrop'
    ]

    def get_media_url(self, host, media_id, subs=False):
        clean_input = media_id.replace('&amp;', '&')

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

        session = requests.Session()
        req_headers = {
            'User-Agent': common.RAND_UA,
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9',
            'Referer': 'https://google.com'
        }

        try:
            resp_init = session.get(page_url, headers=req_headers, allow_redirects=True, timeout=12, verify=False)
            landing_url = resp_init.url
        except Exception as e:
            raise ResolverError(f'MultiEmbed: Initial connection failed: {e}')

        play_match = re.search(r'[?&]play=([^&]+)', landing_url)
        if not play_match:
            play_match = re.search(r'[?&]play=([^&]+)', resp_init.text)
        if not play_match:
            raise ResolverError('MultiEmbed: StreamingNow play token not received')

        play_token = play_match.group(1)

        api_headers = {
            'User-Agent': common.RAND_UA,
            'Referer': landing_url,
            'Origin': 'https://streamingnow.mov',
            'X-Requested-With': 'XMLHttpRequest'
        }

        try:
            resp_servers = session.post(
                "https://streamingnow.mov/response.php",
                data={'token': play_token},
                headers=api_headers,
                timeout=12,
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

            srv_name_m = re.search(r'class=["\']server-name["\'][^>]*>(?:<div[^>]*></div>)?\s*([a-zA-Z0-9_\-\s]+?)\s*</div>', content_html, re.DOTALL)
            server_name = srv_name_m.group(1).strip() if srv_name_m else "Unknown"

            clean_text = re.sub(r'<[^>]+>', ' ', content_html).strip()
            clean_text = re.sub(r'\s+', ' ', clean_text)
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

        # 6. Resolve servers in order
        for item in ranked_servers:
            data_id = item['data_id']
            server_id = item['server_id']

            pv_url = f"https://streamingnow.mov/playvideo.php?video_id={quote_plus(data_id)}&server_id={server_id}&token={quote_plus(play_token)}&init=0"

            try:
                pv_resp = session.get(
                    pv_url,
                    headers={'User-Agent': common.RAND_UA, 'Referer': landing_url},
                    timeout=10,
                    verify=False
                )
                iframe_match = re.search(r'<iframe[^>]+src=["\']([^"\']+)["\']', pv_resp.text, re.I)
                if not iframe_match:
                    continue

                upstream_embed = iframe_match.group(1).strip()
                if not upstream_embed.startswith('http') or 'vipstream' in upstream_embed:
                    continue

                hmf = HostedMediaFile(url=upstream_embed, subs=subs)
                if hmf.valid_url():
                    resolved = hmf.resolve()
                    if resolved:
                        return resolved

            except Exception:
                continue

        raise ResolverError('MultiEmbed: All servers failed to resolve a playable stream')

    def get_url(self, host, media_id):
        if media_id.startswith('http'):
            return media_id
        return f"https://{host}/{media_id}"
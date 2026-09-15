# -*- coding: utf-8 -*-
"""
    Plugin for ResolveURL - Videm / MovieUniverse
    Copyright (C) 2026 Poobi
"""

import re
import json
from urllib.parse import urlparse, urljoin, quote
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError


class VidemResolver(ResolveUrl):
    name = 'Videm'
    domains = ['videm.xyz', 'movieuniverse.skin']
    pattern = r'(?://|\.)((?:videm\.xyz|movieuniverse\.skin))/(?:embed/)?(?:watch-movieuniverse-)?((?:(?:movie|tv)/)?[0-9a-zA-Z_/-]+(?:\?[^"\'>\s]+)?)'

    def get_media_url(self, host, media_id, subs=False):
        web_url = self.get_url(host, media_id)
        headers = {
            'User-Agent': common.RAND_UA,
            'Referer': 'https://cinespot.org/',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
        }

        resp = self.net.http_GET(web_url, headers=headers)
        html = resp.content

        if 'movieuniverse.skin' in web_url:
            iframe_src = re.search(r'<iframe[^>]+src=["\']([^"\']+)["\']', html, re.I)
            if iframe_src:
                web_url = iframe_src.group(1)
                resp = self.net.http_GET(web_url, headers=headers)
                html = resp.content

        q_match = re.search(r'var\s*Q\s*=\s*({.+?});\s*var\s*\$', html, re.DOTALL)
        if not q_match:
            raise ResolverError('Videm: Session state payload (Q) not found')

        try:
            q_data = json.loads(q_match.group(1))
        except Exception as e:
            raise ResolverError(f'Videm: Failed to parse session configuration: {e}')

        q_token = q_data.get('t', '')
        ssr_obj = q_data.get('ssr') or {}
        servers = ssr_obj.get('servers') or []
        if not servers:
            raise ResolverError('Videm: No server mirrors available in payload')

        refs = [s.get('ref') for s in servers if s.get('ref')]
        if not refs:
            raise ResolverError('Videm: No active server refs found')

        api_headers = {
            'User-Agent': common.RAND_UA,
            'Referer': web_url,
            'Origin': 'https://videm.xyz',
            'X-Requested-With': 'XMLHttpRequest'
        }

        stream_url = None

        race_url = f"https://videm.xyz/api.php?a=race&refs={quote(','.join(refs))}&t={q_token}"
        try:
            race_resp = self.net.http_GET(race_url, headers=api_headers)
            race_data = json.loads(race_resp.content)
            cands = race_data.get('cands', [])
            if cands and cands[0].get('url'):
                raw_url = cands[0]['url']
                stream_url = urljoin('https://videm.xyz/', raw_url) if raw_url.startswith('/') else raw_url
        except Exception:
            stream_url = None

        if not stream_url:
            for s in servers:
                s_name = s.get('name') or ''
                if any(bad in s_name for bad in ['VNE', 'Kannada', 'VNH']):
                    continue

                s_ref = s.get('ref')
                if not s_ref:
                    continue

                play_url = f"https://videm.xyz/api.php?a=play&ref={quote(s_ref)}&t={quote(q_token)}"
                try:
                    p_resp = self.net.http_GET(play_url, headers=api_headers)
                    p_data = json.loads(p_resp.content)
                    if p_data.get('error'):
                        continue
                    raw_url = p_data.get('url')
                    if raw_url:
                        stream_url = urljoin('https://videm.xyz/', raw_url) if raw_url.startswith('/') else raw_url
                        break
                except Exception:
                    continue

        if not stream_url:
            raise ResolverError('Videm: Failed to obtain stream from race or fallback mirrors')

        delim = "&" if "?" in stream_url else "?"
        final_stream_url = f"{stream_url}{delim}format=.m3u8&bypass_localize=true"

        stream_headers = {
            'User-Agent': common.RAND_UA,
            'Referer': web_url,
            'verifypeer': 'false'
        }

        playable_url = final_stream_url + helpers.append_headers(stream_headers)

        if subs:
            subtitles = {}
            subs_url = f"https://videm.xyz/api.php?a=subs&type={q_data.get('type')}&id={quote(str(q_data.get('id', '')))}&s={q_data.get('s', 0)}&e={q_data.get('e', 0)}&t={quote(q_token)}"
            try:
                s_resp = self.net.http_GET(subs_url, headers=api_headers)
                s_data = json.loads(s_resp.content)
                for sub_item in s_data.get('subs', []):
                    label = sub_item.get('label') or sub_item.get('lang', 'English')
                    s_ref_sub = sub_item.get('ref')
                    if s_ref_sub:
                        subtitles[label] = f"https://videm.xyz/api.php?a=sub&ref={quote(s_ref_sub)}"
            except Exception:
                pass
            return playable_url, subtitles

        return playable_url

    def get_url(self, host, media_id):
        if media_id.startswith('http'):
            return media_id

        if 'movieuniverse.skin' in host:
            clean = media_id.replace('watch-movieuniverse-', '')
            return f"https://movieuniverse.skin/watch-movieuniverse-{clean}"

        if media_id.startswith(('movie/', 'tv/')):
            return f"https://{host}/embed/{media_id}"
        return f"https://{host}/embed/movie/{media_id}"
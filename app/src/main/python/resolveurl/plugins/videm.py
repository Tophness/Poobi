# -*- coding: utf-8 -*-
"""
    Plugin for ResolveURL - Videm / MovieUniverse
    Copyright (C) 2026 Poobi
"""

import re
import json
from six.moves import urllib_parse
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError


class VidemResolver(ResolveUrl):
    name = 'Videm'
    domains = ['videm.xyz', 'movieuniverse.skin']
    pattern = r'(?://|\.)((?:videm\.xyz|movieuniverse\.skin))/(?:embed/)?(?:watch-movieuniverse-|movie/|tv/)?([0-9a-zA-Z_-]+(?:\?[^"\'>\s]+)?)'

    def get_media_url(self, host, media_id, subs=False):
        web_url = self.get_url(host, media_id)
        headers = {
            'User-Agent': common.RAND_UA,
            'Referer': 'https://movieuniverse.skin/',
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
            raise ResolverError('Videm: No server mirrors available in payload (likely invalid TMDb ID)')

        api_headers = {
            'User-Agent': common.RAND_UA,
            'Referer': web_url,
            'Origin': 'https://videm.xyz',
            'X-Requested-With': 'XMLHttpRequest'
        }

        for s in servers:
            s_ref = s.get('ref')
            if not s_ref:
                continue

            api_url = f"https://videm.xyz/api.php?a=play&ref={urllib_parse.quote(s_ref)}&t={urllib_parse.quote(q_token)}"
            try:
                api_resp = self.net.http_GET(api_url, headers=api_headers)
                api_data = json.loads(api_resp.content)
                if api_data.get('error'):
                    continue

                raw_url = api_data.get('url')
                if raw_url:
                    stream_url = urllib_parse.urljoin('https://videm.xyz/', raw_url) if raw_url.startswith('/') else raw_url
                    delim = "&" if "?" in stream_url else "?"
                    final_stream_url = f"{stream_url}{delim}bypass_localize=true"

                    stream_headers = {
                        'User-Agent': common.RAND_UA,
                        'Referer': web_url,
                        'verifypeer': 'false'
                    }

                    playable_url = final_stream_url + helpers.append_headers(stream_headers)
                    return playable_url
            except Exception:
                continue

        raise ResolverError('Videm: All server mirrors failed to mint an active stream')

    def get_url(self, host, media_id):
        if media_id.startswith('http'):
            return media_id
        if 'movieuniverse.skin' in host:
            return f"https://movieuniverse.skin/watch-movieuniverse-{media_id}"
        return f"https://videm.xyz/embed/movie/{media_id}"
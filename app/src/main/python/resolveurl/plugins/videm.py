"""
    Plugin for ResolveURL
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

        # Handle movieuniverse wrapper redirecting to videm.xyz
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
        servers = q_data.get('ssr', {}).get('servers', [])
        if not servers:
            raise ResolverError('Videm: No server mirrors available in payload')

        # Fallback sequence: try each server until a valid direct stream mints
        api_headers = {
            'User-Agent': common.RAND_UA,
            'Referer': web_url,
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
                stream_url = api_data.get('url')
                if stream_url:
                    if stream_url.startswith('/'):
                        stream_url = urllib_parse.urljoin('https://videm.xyz', stream_url)

                    stream_headers = {
                        'User-Agent': common.RAND_UA,
                        'Referer': web_url,
                        'Origin': 'https://videm.xyz',
                        'verifypeer': 'false'
                    }

                    final_url = stream_url + helpers.append_headers(stream_headers)

                    if subs:
                        subtitles = {}
                        subs_url = f"https://videm.xyz/api.php?a=subs&type={q_data.get('type')}&id={urllib_parse.quote(q_data.get('id', ''))}&s={q_data.get('s', 0)}&e={q_data.get('e', 0)}&t={urllib_parse.quote(q_token)}"
                        try:
                            s_resp = self.net.http_GET(subs_url, headers=api_headers)
                            s_data = json.loads(s_resp.content)
                            for sub_item in s_data.get('subs', []):
                                label = sub_item.get('label') or sub_item.get('lang', 'English')
                                s_ref_sub = sub_item.get('ref')
                                if s_ref_sub:
                                    subtitles[label] = f"https://videm.xyz/api.php?a=sub&ref={urllib_parse.quote(s_ref_sub)}"
                        except Exception:
                            pass
                        return final_url, subtitles

                    return final_url
            except Exception:
                continue

        raise ResolverError('Videm: All server mirrors failed to mint an active stream')

    def get_url(self, host, media_id):
        if media_id.startswith('http'):
            return media_id
        if 'movieuniverse.skin' in host:
            return f"https://movieuniverse.skin/watch-movieuniverse-{media_id}"
        return f"https://videm.xyz/embed/movie/{media_id}"
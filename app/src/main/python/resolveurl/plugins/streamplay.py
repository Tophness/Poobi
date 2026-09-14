"""
    Plugin for ResolveURL
    Copyright (C) 2026
"""

import re
from six.moves import urllib_parse
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError


class StreamPlayResolver(ResolveUrl):
    name = 'StreamPlay'
    domains = ['streamplay.to', 'straemplay.org', 'streamplay.me', 'streamplay.cc']
    pattern = r'(?://|\.)(str[ae]{2}mplay\.(?:to|org|me|cc))/(?:embed-|embed/|e/)?([0-9a-zA-Z]+)'

    def get_media_url(self, host, media_id):
        # Prefer straemplay.org as streamplay.to redirects to it
        canonical_host = 'straemplay.org'
        embed_url = f"https://{canonical_host}/embed-{media_id}.html"
        headers = {
            'User-Agent': common.RAND_UA,
            'Referer': f"https://{canonical_host}/"
        }

        # 1. Try fetching the embed page directly
        response = self.net.http_GET(embed_url, headers=headers)
        html = response.content
        packed = helpers.get_packed_data(html)

        # 2. If the embed page requires the download step, simulate the POST form
        if not packed or 'sources' not in packed:
            main_url = f"https://{canonical_host}/{media_id}"
            r_main = self.net.http_GET(main_url, headers=headers)
            payload = helpers.get_hidden(r_main.content)
            if payload:
                payload.update({'imhuman': 'Proceed to video', 'op': 'download1'})
                headers.update({'Origin': f"https://{canonical_host}", 'Referer': main_url})
                r_post = self.net.http_POST(main_url, form_data=payload, headers=headers)
                packed = helpers.get_packed_data(r_post.content)

        # 3. Extract the playable stream from the unpacked JavaScript
        if packed:
            # Check for HLS (.m3u8) first, then MP4
            m3u8 = re.search(r'''file:\s*["'](?P<url>[^"']+\.m3u8[^"']*)''', packed)
            if m3u8:
                headers.update({'Referer': embed_url})
                return m3u8.group('url') + helpers.append_headers(headers)

            mp4 = re.search(r'''file:\s*["'](?P<url>[^"']+\.mp4[^"']*)''', packed)
            if mp4:
                headers.update({'Referer': embed_url})
                return mp4.group('url') + helpers.append_headers(headers)

        raise ResolverError('No playable stream found for StreamPlay.')

    def get_url(self, host, media_id):
        return f"https://straemplay.org/embed-{media_id}.html"
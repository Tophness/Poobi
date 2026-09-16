# -*- coding: utf-8 -*-
"""
    Plugin for ResolveURL - MultiEmbed / StreamingNow
    Copyright (C) 2026 Poobi
"""

import re
import requests
from urllib.parse import urlparse, parse_qs
from resolveurl.resolver import ResolveUrl, ResolverError
from resolveurl.hmf import HostedMediaFile


class MultiEmbedResolver(ResolveUrl):
    name = 'MultiEmbed'
    domains = ['multiembed.mov', 'multiembed.co', 'multiembed.si', 'streamingnow.mov']
    pattern = r'(?://|\.)((?:multiembed|streamingnow)\.(?:mov|co|si))/(.*)'

    def get_media_url(self, host, media_id, subs=False):
        tmdb_match = re.search(r'(?:video_id|tmdb|id)[/=](\d{4,7})|movie/(\d{4,7})', media_id)
        tmdb_id = next((g for g in tmdb_match.groups() if g), None) if tmdb_match else None

        if not tmdb_id:
            query_str = media_id.split('?')[-1] if '?' in media_id else media_id
            q_params = parse_qs(query_str)
            tmdb_id = q_params.get('video_id', [None])[0] or q_params.get('tmdb', [None])[0] or q_params.get('id', [None])[0]

        if tmdb_id and tmdb_id.isdigit():
            upstream_url = f"https://vidlink.pro/movie/{tmdb_id}"
            hmf = HostedMediaFile(url=upstream_url, subs=subs)
            if hmf.valid_url():
                return hmf.resolve()

        web_url = self.get_url(host, media_id)
        try:
            resp = requests.get(web_url, allow_redirects=True, timeout=12, verify=False)
            final_url = resp.url
        except Exception as e:
            raise ResolverError(f"MultiEmbed connection failed: {e}")

        parsed_final = urlparse(final_url)
        q_final = parse_qs(parsed_final.query)
        tmdb_id = q_final.get('video_id', [None])[0] or q_final.get('tmdb', [None])[0] or q_final.get('id', [None])[0]

        if not tmdb_id:
            m = re.search(r'(?:video_id|tmdb|id)[/=](\d{4,7})|movie/(\d{4,7})', final_url)
            if m: tmdb_id = next((g for g in m.groups() if g), None)

        if tmdb_id and tmdb_id.isdigit():
            upstream_url = f"https://vidlink.pro/movie/{tmdb_id}"
            hmf = HostedMediaFile(url=upstream_url, subs=subs)
            if hmf.valid_url():
                return hmf.resolve()

        raise ResolverError('MultiEmbed: Could not extract upstream media ID or redirect target')

    def get_url(self, host, media_id):
        if media_id.startswith('http'):
            return media_id
        if media_id.startswith('?'):
            return f"https://{host}/{media_id}"
        return f"https://{host}/{media_id}"
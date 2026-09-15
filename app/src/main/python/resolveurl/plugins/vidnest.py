"""
    Plugin for ResolveURL
    Copyright (C) 2025 gujal
"""

from resolveurl.lib import helpers
from resolveurl.plugins.__resolve_generic__ import ResolveGeneric


class VidNestResolver(ResolveGeneric):
    name = 'VidNest'
    domains = ['vidnest.io', 'vidnest.live', 'vidnest.fun']
    pattern = r'(?://|\.)(vidnest\.(?:io|live|fun))/(?:e/|d/|embed-|movie/|tv/)?([0-9a-zA-Z/-]+)'

    def get_media_url(self, host, media_id):
        return helpers.get_media_url(
            self.get_url(host, media_id),
            patterns=[
                r'''sources:\s*\[{file:"(?P<url>[^"]+)".label:"\d+x(?P<label>\d+)''',
                r'''(?:file|source)\s*:\s*["'](?P<url>[^"']+)["']'''
            ]
        )

    def get_url(self, host, media_id):
        if 'movie/' in media_id or 'tv/' in media_id:
            return f"https://{host}/{media_id}"
        return self._default_get_url(host, media_id, template='https://{host}/{media_id}')
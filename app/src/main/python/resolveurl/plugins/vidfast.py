"""
    Plugin for ResolveURL
    Copyright (C) 2020 gujal
"""

from resolveurl.plugins.__resolve_generic__ import ResolveGeneric
from resolveurl.lib import helpers


class VidFastResolver(ResolveGeneric):
    name = 'VidFast'
    domains = ['vidfast.co', 'vidfast.pro']
    pattern = r'(?://|\.)(vidfast\.(?:co|pro))/(?:embed-|movie/|tv/)?([a-zA-Z0-9/-]+(?:\?[^"\'>\s]+)?)'

    def get_media_url(self, host, media_id):
        return helpers.get_media_url(
            self.get_url(host, media_id),
            patterns=[
                r'''sources:\s*\[{file:\s*"(?P<url>[^"]+)''',
                r'''["']?file["']?\s*:\s*["'](?P<url>[^"']+\.m3u8[^"']*)["']'''
            ],
            generic_patterns=False
        )

    def get_url(self, host, media_id):
        if 'movie/' in media_id or 'tv/' in media_id:
            return f"https://{host}/{media_id}"
        return self._default_get_url(host, media_id, template='https://{host}/embed-{media_id}.html')
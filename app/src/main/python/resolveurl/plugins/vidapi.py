# -*- coding: utf-8 -*-
"""
    Plugin for ResolveURL - VidAPI
    Copyright (C) 2026 Poobi
"""

from resolveurl.hmf import HostedMediaFile
from resolveurl.resolver import ResolveUrl, ResolverError


class VidAPIResolver(ResolveUrl):
    name = 'VidAPI'
    domains = ['vidapi.xyz', 'stream.vidapi.xyz']
    pattern = r'(?://|\.)((?:stream\.)?vidapi\.xyz)/(?:embed/|xps\?)(?:movie/|tmdb=)?([0-9a-zA-Z]+)'

    def get_media_url(self, host, media_id, subs=False):
        if not media_id:
            raise ResolverError('VidAPI: Missing media ID')

        downstream_url = f"https://videm.xyz/embed/movie/{media_id}"
        hmf = HostedMediaFile(url=downstream_url, subs=subs)
        if hmf.valid_url():
            return hmf.resolve()

        raise ResolverError('VidAPI: Downstream resolution failed')

    def get_url(self, host, media_id):
        return f"https://stream.vidapi.xyz/xps?tmdb={media_id}&ref=https%3A%2F%2Fcinespot.org%2F"
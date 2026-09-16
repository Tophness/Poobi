# -*- coding: utf-8 -*-
"""
    Plugin for ResolveURL - VidLink (Bridge to VidKing / VidSrc)
    Copyright (C) 2026 Poobi
"""

import re
from resolveurl.resolver import ResolveUrl, ResolverError
from resolveurl.hmf import HostedMediaFile


class VidLinkResolver(ResolveUrl):
    name = 'VidLink'
    domains = ['vidlink.pro', 'vidlink.org']
    pattern = r'(?://|\.)(vidlink\.(?:pro|org))/(?:embed/)?((?:movie|tv)/[0-9a-zA-Z/-]+(?:\?[^"\'>\s]+)?)'

    def get_media_url(self, host, media_id, subs=False):
        tmdb_match = re.search(r'(\d{4,7})', media_id)
        if not tmdb_match:
            raise ResolverError('VidLink: TMDB ID could not be extracted')

        tmdb_id = tmdb_match.group(1)
        is_tv = 'tv' in media_id

        if is_tv:
            season = "1"
            episode = "1"
            m_season = re.search(r'season[/=](\d+)', media_id, re.I)
            m_episode = re.search(r'episode[/=](\d+)', media_id, re.I)
            if m_season: season = m_season.group(1)
            if m_episode: episode = m_episode.group(1)
            
            upstream_url = f"https://www.vidking.net/embed/tv/{tmdb_id}/{season}/{episode}"
        else:
            upstream_url = f"https://www.vidking.net/embed/movie/{tmdb_id}"

        hmf = HostedMediaFile(url=upstream_url, subs=subs)
        if hmf.valid_url():
            try:
                return hmf.resolve()
            except Exception as e:
                fallback_url = f"https://cloudorchestranova.com/embed/movie/{tmdb_id}"
                hmf_fallback = HostedMediaFile(url=fallback_url, subs=subs)
                if hmf_fallback.valid_url():
                    return hmf_fallback.resolve()
                raise ResolverError(f"VidLink upstream resolution failed: {e}")

        raise ResolverError('VidLink: No upstream resolver matched')

    def get_url(self, host, media_id):
        return f"https://{host}/{media_id}"
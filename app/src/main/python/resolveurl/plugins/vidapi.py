# -*- coding: utf-8 -*-
"""
    Plugin for ResolveURL - VidAPI
    Copyright (C) 2026 Poobi
"""

import re
import requests
from resolveurl.hmf import HostedMediaFile
from resolveurl.resolver import ResolveUrl, ResolverError
from modules.keys import tmdb_key


class VidAPIResolver(ResolveUrl):
    name = 'VidAPI'
    domains = ['vidapi.xyz', 'stream.vidapi.xyz']
    pattern = r'(?://|\.)((?:stream\.)?vidapi\.xyz)/(?:embed/|xps\?)(?:movie/|tmdb=)?([0-9a-zA-Z]+)'

    def get_media_url(self, host, media_id, subs=False):
        tmdb_id = None

        if media_id.startswith('tt'):
            try:
                url = f"https://api.themoviedb.org/3/find/{media_id}?api_key={tmdb_key}&external_source=imdb_id"
                res = requests.get(url, timeout=6, verify=False).json()
                results = res.get('movie_results') or res.get('tv_results') or []
                if results:
                    tmdb_id = str(results[0]['id'])
            except Exception:
                pass

        if not tmdb_id:
            numeric_match = re.sub(r'\D', '', media_id)
            if numeric_match:
                tmdb_id = numeric_match

        if not tmdb_id:
            raise ResolverError('VidAPI: Could not extract or resolve valid TMDb ID')

        downstream_url = f"https://videm.xyz/embed/movie/{tmdb_id}"
        hmf = HostedMediaFile(url=downstream_url, subs=subs)
        if hmf.valid_url():
            return hmf.resolve()

        raise ResolverError('VidAPI: Downstream resolution failed')

    def get_url(self, host, media_id):
        return f"https://stream.vidapi.xyz/xps?tmdb={media_id}&ref=https%3A%2F%2Fcinespot.org%2F"
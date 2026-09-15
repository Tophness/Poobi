"""
    Plugin for ResolveURL - VidFast / VidUp / YtHD
    Copyright (C) 2026 Poobi
"""

import json
import re
from urllib.parse import urljoin
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError


class VidFastResolver(ResolveUrl):
    name = 'VidFast'
    domains = ['vidfast.co', 'vidfast.pro', 'vidup.to', 'ythd.org']
    pattern = r'(?://|\.)(vid(?:fast|up)\.(?:co|pro|to)|ythd\.org)/(?:embed-|movie/|tv/)?([a-zA-Z0-9/-]+(?:\?[^"\'>\s]+)?)'

    def get_media_url(self, host, media_id, subs=False):
        clean_id = media_id.split('?')[0]
        is_tv = 'tv/' in clean_id or len([p for p in clean_id.split('/') if p.isdigit()]) >= 2
        content_type = "tv" if is_tv else "movie"
        numeric_id = re.sub(r'\D', '', clean_id)

        api_url = f"https://ythd.org/vs_src.php?type={content_type}&id={numeric_id}"
        headers = {
            'User-Agent': common.RAND_UA,
            'Referer': f'https://{host}/'
        }

        try:
            resp = self.net.http_GET(api_url, headers=headers)
            data = json.loads(resp.content)
            inner_src = data.get('src')
            if inner_src:
                from resolveurl.hmf import HostedMediaFile
                hmf = HostedMediaFile(url=inner_src)
                if hmf.valid_url():
                    return hmf.resolve()
        except Exception:
            pass

        raise ResolverError('VidFast: Failed to resolve stream')

    def get_url(self, host, media_id):
        return f"https://ythd.org/embed/movie/{media_id}"
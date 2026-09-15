"""
    Plugin for ResolveURL - VidSrc.buzz (2embed child)
    Copyright (C) 2026 Poobi
"""

import re
import json
from six.moves import urllib_parse
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError


class VidSrcBuzzResolver(ResolveUrl):
    name = 'VidSrcBuzz'
    domains = ['vidsrc.buzz']
    pattern = r'(?://|\.)(vidsrc\.buzz)/embed/(?:movie/|tv/)?([0-9a-zA-Z/-]+(?:\?[^"\'>\s]+)?)'

    def get_media_url(self, host, media_id, subs=False):
        embed_url = self.get_url(host, media_id)
        headers = {
            'User-Agent': common.RAND_UA,
            'Referer': 'https://2embed.cc/'
        }

        resp = self.net.http_GET(embed_url, headers=headers)
        html = resp.content

        q_match = re.search(r'var\s*Q\s*=\s*({.+?});', html, re.DOTALL)
        if not q_match:
            raise ResolverError('VidSrcBuzz: Session data (Q) not found')

        q_data = json.loads(q_match.group(1))
        servers = q_data.get('ssr', {}).get('servers', [])
        refs = [s.get('ref') for s in servers if s.get('ref')]
        if not refs:
            raise ResolverError('VidSrcBuzz: No active server refs found')

        race_url = f"https://vidsrc.buzz/pl/api.php?a=race&refs={urllib_parse.quote(','.join(refs))}"
        api_headers = {
            'User-Agent': common.RAND_UA,
            'Referer': embed_url,
            'Origin': 'https://vidsrc.buzz',
            'X-Requested-With': 'XMLHttpRequest'
        }

        race_resp = self.net.http_GET(race_url, headers=api_headers)
        data = json.loads(race_resp.content)
        cands = data.get('cands', [])
        if not cands:
            raise ResolverError('VidSrcBuzz: Race API returned no candidates')

        raw_url = cands[0].get('url')
        if not raw_url:
            raise ResolverError('VidSrcBuzz: Candidate URL missing')

        if raw_url.startswith('/'):
            stream_url = urllib_parse.urljoin('https://vidsrc.buzz/', raw_url)
        else:
            stream_url = raw_url

        stream_headers = {
            'User-Agent': common.RAND_UA,
            'Referer': embed_url,
            'Origin': 'https://vidsrc.buzz',
            'verifypeer': 'false'
        }

        playable_url = stream_url + helpers.append_headers(stream_headers)

        if subs:
            subtitles = {}
            subs_url = f"https://vidsrc.buzz/pl/api.php?a=subs&id={q_data.get('id')}&type={q_data.get('type')}&t={urllib_parse.quote(q_data.get('t', ''))}"
            try:
                s_resp = self.net.http_GET(subs_url, headers=api_headers)
                s_data = json.loads(s_resp.content)
                for item in s_data.get('subs', []):
                    label = item.get('label') or item.get('lang') or 'English'
                    ref_sub = item.get('ref')
                    if ref_sub:
                        subtitles[label] = f"https://vidsrc.buzz/pl/api.php?a=sub&ref={urllib_parse.quote(ref_sub)}"
            except Exception:
                pass
            return playable_url, subtitles

        return playable_url

    def get_url(self, host, media_id):
        if not media_id.startswith('movie/') and not media_id.startswith('tv/'):
            media_id = f"movie/{media_id}"
        return f"https://vidsrc.buzz/embed/{media_id}"
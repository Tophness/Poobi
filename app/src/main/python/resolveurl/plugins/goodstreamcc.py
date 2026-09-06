"""
    Plugin for ResolveURL
    Copyright (C) 2026 gujal
"""

import re
import json
from six.moves import urllib_parse
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError

try:
    from resources.lib.modules import cfscrape
except Exception:
    try:
        import cfscrape
    except Exception:
        cfscrape = None


class GoodStreamCCResolver(ResolveUrl):
    name = 'GoodStreamCC'
    domains = ['goodstream.cc']
    pattern = r'(?://|\.)(goodstream\.cc)/(?:embed|pl)/([0-9a-zA-Z_-]+(?:\?[^"\'>\s]+)?)'

    def get_media_url(self, host, media_id):
        web_url = self.get_url(host, media_id)

        if cfscrape:
            session = cfscrape.create_scraper()
        else:
            session = self.net

        headers = {
            'Referer': 'https://hollymoviehd.cc/',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Sec-Fetch-Dest': 'iframe',
            'Sec-Fetch-Mode': 'navigate',
            'Sec-Fetch-Site': 'cross-site'
        }

        if cfscrape:
            resp_get = session.get(web_url, headers=headers)
            html = resp_get.text if resp_get else ''
        else:
            headers['User-Agent'] = common.RAND_UA
            html = session.http_GET(web_url, headers=headers).content

        r = re.search(r'id=["\']csrf_token["\']\s*value=["\']([^"\']+)["\']', html)
        if not r:
            r = re.search(r'value=["\']([^"\']+)["\']\s*id=["\']csrf_token["\']', html)
        if not r:
            r = re.search(r'init_player\([^,]+,[^,]+,\s*["\']([^"\']+)["\']', html)

        if r:
            csrf_token = r.group(1)
            ref = urllib_parse.urljoin(web_url, '/')
            
            post_headers = {
                'Referer': web_url,
                'Origin': ref.rstrip('/'),
                'X-Requested-With': 'XMLHttpRequest',
                'Accept': 'application/json, text/javascript, */*; q=0.01',
                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'
            }
            data = {'csrf_token': csrf_token, 'token': ''}

            if cfscrape:
                resp_post = session.post(web_url, data=data, headers=post_headers)
                resp_text = resp_post.text if resp_post else ''
            else:
                resp_text = session.http_POST(web_url, form_data=data, headers=post_headers).content

            try:
                resp_data = json.loads(resp_text)
            except Exception:
                resp_data = {}

            if resp_data.get('success') or resp_data.get('sources'):
                sources = []
                for src in resp_data.get('sources', []):
                    surl = src.get('file', '')
                    if surl.startswith('//'):
                        surl = 'https:' + surl
                    elif surl.startswith('/'):
                        surl = urllib_parse.urljoin(web_url, surl)
                    sources.append(('{}-{}'.format(src.get('type', 'video'), src.get('label', 'HD')), surl))
                
                if sources:
                    ua = session.headers.get('User-Agent', common.RAND_UA) if cfscrape else common.RAND_UA
                    resolved = helpers.pick_source(sources)
                    return resolved + helpers.append_headers({'User-Agent': ua, 'Referer': web_url})

        raise ResolverError('File Not Found or Removed')

    def get_url(self, host, media_id):
        if media_id.startswith('http'):
            return media_id
        return f"https://{host}/embed/{media_id}"
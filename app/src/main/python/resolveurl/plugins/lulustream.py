"""
    Plugin for ResolveURL
    Copyright (C) 2023 shellc0de

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
"""

from six.moves import urllib_error
from resolveurl.lib import helpers
from resolveurl import common
from resolveurl.resolver import ResolveUrl, ResolverError


class LuluStreamResolver(ResolveUrl):
    name = 'LuluStream'
    domains = [
        'lulustream.com', 'luluvdo.com', 'lulu.st', 'luluvid.com', '732eg54de642sa.sbs',
        'cdn1.site', 'streamhihi.com', 'luluvdoo.com', 'd00ds.site', 'luluvdo.net',
        'luluvdo.org', 'ponstream.org', 'luluco.org', 'lulupool.com'
    ]
    pattern = r'(?://|\.)((?:lulu(?:stream|vi*do*|co|pool)?|732eg54de642sa|cdn1|streamhihi|d00ds|ponstream)\.(?:com|sbs|si?te?|org|net))/(?:e/|d/)?([0-9a-zA-Z]+)'

    def get_media_url(self, host, media_id, subs=False):
        web_url = self.get_url(host, media_id)
        headers = {'User-Agent': common.RAND_UA, 'Referer': f'https://{host}/'}

        try:
            html = self.net.http_GET(web_url, headers=headers).content
        except urllib_error.HTTPError as e:
            if e.code == 404:
                raise ResolverError('File Not Found or Removed')
            raise

        if any(msg in html for msg in ['No such file', 'File Not Found', 'file you were looking for could not be found', 'The file expired', 'The file was deleted']):
            raise ResolverError('File Not Found or Removed')

        sources = helpers.scrape_sources(
            html,
            patterns=[
                r'''sources:\s*\[{file:\s*["'](?P<url>[^"']+)''',
                r'''source\s*src="(?P<url>[^"]+)'''
            ],
            generic_patterns=False
        )

        if subs:
            subtitles = helpers.scrape_subtitles(html, web_url)

        if sources:
            stream_url = helpers.pick_source(sources) + helpers.append_headers(headers)
            if subs:
                return stream_url, subtitles
            return stream_url

        raise ResolverError('File Not Found or Removed')

    def get_url(self, host, media_id):
        return self._default_get_url(host, media_id, template='https://{host}/e/{media_id}')
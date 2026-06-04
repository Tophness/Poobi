# -*- coding: utf-8 -*-

'''
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
'''



import re

from six import ensure_text

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import source_utils, log_utils

try: from urlparse import parse_qs, urljoin
except ImportError: from urllib.parse import parse_qs, urljoin
try: from urllib import urlencode
except ImportError: from urllib.parse import urlencode


class source:
    def __init__(self):
        self.priority = 1
        self.language = ['en']
        self.domains = ['filmxy.me', 'filmxy.one', 'filmxy.tv']
        self.base_link = 'https://www.filmxy.pw'
        self.search_link = '/search/%s/feed/rss2/'
        self.post = 'https://cdn.filmxy.one/asset/json/posts.json'

    def movie(self, imdb, title, localtitle, aliases, year):
        try:
            url = {'imdb': imdb, 'title': title, 'year': year}
            url = urlencode(url)
            return url
        except:
            log_utils.log('filmxy', 1)
            return

    def sources(self, url, hostDict, hostprDict):
        sources = []
        try:
            if url is None: return

            hostDict = hostprDict + hostDict

            data = parse_qs(url)
            data = dict((i, data[i][0]) for i in data)
            title = data['title']
            year = data['year']

            tit = cleantitle.geturl(title + ' ' + year)
            query = urljoin(self.base_link, tit)

            r = client.request(query, referer=self.base_link)
            if not data['imdb'] in r:
                return sources

            links = []

            try:
                down = client.parseDOM(r, 'div', attrs={'id': 'tab-download'})[0]
                down = client.parseDOM(down, 'a', ret='href')[0]
                data = client.request(down, headers={'User-Agent': client.agent(), 'Referer': query})
                frames = client.parseDOM(data, 'li', attrs={'class': 'signle-link'})
                frames = [(client.parseDOM(i, 'a', ret='href')[0], client.parseDOM(i, 'span')[0]) for i in frames if i]
                for i in frames:
                    links.append(i)
            except:
                pass

            try:
                streams = client.parseDOM(r, 'div', attrs={'id': 'tab-stream'})[0]
                streams = re.findall(r'''iframe src=(.+?) frameborder''', streams.replace('&quot;', ''), re.I | re.DOTALL)
                streams = [(i, '720p') for i in streams]
                for i in streams:
                    links.append(i)
            except:
                pass

            for url, qual in links:
                try:
                    valid, host = source_utils.is_host_valid(url, hostDict)
                    if valid:
                        quality = source_utils.check_sd_url(qual)
                        sources.append({'source': host, 'quality': quality, 'language': 'en', 'url': url,
                                        'direct': False, 'debridonly': False})
                except:
                    pass
            return sources
        except:
            log_utils.log('filmxy exc', 1)
            return sources

    def resolve(self, url):
        return url
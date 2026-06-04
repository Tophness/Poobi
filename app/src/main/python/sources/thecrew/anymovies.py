# -*- coding: utf-8 -*-
"""
    **Created by Tempest**
    **If you see this in a addon other than Tempest and says it was
    created by someone other than Tempest they stole it from me**
"""

import re
from resources.lib.modules import source_utils
from resources.lib.modules import client

try: from urlparse import parse_qs, urljoin
except ImportError: from urllib.parse import parse_qs, urljoin
try: from urllib import urlencode, quote_plus
except ImportError: from urllib.parse import urlencode, quote_plus


class source:
    def __init__(self):
        self.priority = 1
        self.language = ['en']
        self.domain = ['downloads-anymovies.com']
        self.base_link = 'https://www.downloads-anymovies.com'
        self.search_link = '/search.php?zoom_query=%s'
        self.headers = {'User-Agent': client.agent()}

    def movie(self, imdb, title, localtitle, aliases, year):
        try:
            url = {'imdb': imdb, 'title': title, 'year': year}
            url = urlencode(url)
            return url
        except:
            return

    def sources(self, url, hostDict, hostprDict):
        sources = []
        try:
            if url is None:
                return sources

            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])

            hdlr = data['year']

            query = '%s %s' % (data['title'], data['year'])
            query = re.sub('(\\\|/| -|:|;|\*|\?|"|\'|<|>|\|)', ' ', query)

            url = self.search_link % quote_plus(query)
            url = urljoin(self.base_link, url).replace('++', '+')

            post = client.request(url, headers=self.headers)
            links = re.compile('class="result_title"><a href="(.+?)">(.+?)</a></div>').findall(post)
            for url, data in links:
                if hdlr not in data:
                    continue
                url = client.request(url, headers=self.headers)
                try:
                    link = re.findall('<span class="text"><a href="(.+?)" target="_blank">', url)
                    for link in link:
                        valid, host = source_utils.is_host_valid(link, hostDict)
                        if valid:
                            sources.append({'source': host, 'quality': 'HD', 'language': 'en', 'url': link, 'direct': False, 'debridonly': False})
                except:
                    return

            return sources
        except Exception:
            return sources

    def resolve(self, url):
        return url

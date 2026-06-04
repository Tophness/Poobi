# -*- coding: utf-8 -*-
# Add tvshows later(only a few on site)
"""
    **Created by Tempest** Converted 19 crew
    **If you see this in a addon other than Tempest and says it was
    created by someone other than Tempest they stole it from me**
"""

import re
from resources.lib.modules import client
from resources.lib.modules import source_utils, control

try: from urlparse import parse_qs, urljoin
except ImportError: from urllib.parse import parse_qs, urljoin
try: from urllib import urlencode, quote_plus
except ImportError: from urllib.parse import urlencode, quote_plus


class source:
    def __init__(self):
        self.priority = 1
        self.language = ['en']
        self.domains = ['allmoviesforyou.co']
        self.base_link = 'https://allmovies.gg'
        self.search_link = '/?s=%s'
        self.search_link2 = '/embed/tmdb/tv?id=%s&s=%s&e=%s'
        self.headers = {'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0', 'Referer': self.base_link}

    def movie(self, imdb, title, localtitle, aliases, year):
        try:
            url = {'imdb': imdb, 'title': title, 'year': year}
            url = urlencode(url)
            return url
        except:
            return

    def tvshow(self, imdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        try:
            url = {'imdb': imdb, 'tvdb': tvdb, 'tvshowtitle': tvshowtitle, 'year': year}
            url = urlencode(url)
            return url
        except:
            return

    def episode(self, url, imdb, tvdb, title, premiered, season, episode):
        try:
            if url is None: return

            url = parse_qs(url)
            url = dict([(i, url[i][0]) if url[i] else (i, '') for i in url])
            url['title'], url['premiered'], url['season'], url['episode'] = title, premiered, season, episode
            url = urlencode(url)
            return url
        except:
            return

    def sources(self, url, hostDict, hostprDict):
        try:
            sources = []
            items = []

            if url is None:
                return sources

            hostDict = hostprDict + hostDict

            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])

            urls = self.search_link % quote_plus(data['title'])

            try:
                url = urljoin(self.base_link, urls)
                r = client.request(url, headers=self.headers)
                r = client.parseDOM(r, 'article', attrs={'class': 'TPost B'})
                r = [re.findall(
                    '<a href="(.+?)">.+?<span class="Qlty">(.+?)</span>.+?<span class="Qlty Yr">(.+?)</span>.+?<h2 class="Title">(.+?)</h2>',
                    i, re.DOTALL)[0] for i in r]
                items += r
            except:
                return

            for item in items:
                try:
                    if data['title'] in item[3] and data['year'] in item[2]:
                        url = client.request(item[0], headers=self.headers)
                        url = re.findall('<iframe src="(.+?)"', url)[0]
                        url = url.replace('#038;', '')
                        url = client.request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0', 'Referer': url})
                        url = re.findall('src="(.+?)"', url)

                        for url in url:
                            valid, host = source_utils.is_host_valid(url, hostDict)
                            sources.append(
                                {'source': host, 'quality': item[1], 'language': 'en', 'url': url,
                                 'direct': False, 'debridonly': False})
                except:
                    pass

            return sources
        except Exception:
            return sources

    def resolve(self, url):
        return url

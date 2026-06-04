# -*- coding: utf-8 -*-

import re

from resources.lib.modules import client
from resources.lib.modules import log_utils
from resources.lib.modules import source_utils




class source:
    def __init__(self):
        self.priority = 1
        self.language = ['en']
        self.domains = ['databasegdriveplayer.co', 'database.gdriveplayer.us', 'series.databasegdriveplayer.co']
        self.base_link = 'https://databasegdriveplayer.co'


    def movie(self, imdb, title, localtitle, aliases, year):
        try:
            if imdb == '0':
                return
            url = self.base_link + '/player.php?imdb=%s' % imdb
            return url
        except Exception:
            log_utils.log('movie', 1)
            return


    def tvshow(self, imdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        try:
            if imdb == '0':
                return
            url = self.base_link + '/player.php?type=series&imdb=%s' % imdb
            return url
        except Exception:
            log_utils.log('tvshow', 1)
            return


    def episode(self, url, imdb, tvdb, title, premiered, season, episode):
        try:
            if not url:
                return
            url = url + '&season=%s&episode=%s' % (season, episode)
            return url
        except Exception:
            log_utils.log('episode', 1)
            return


    def sources(self, url, hostDict, hostprDict):
        sources = []
        try:
            if url == None:
                return sources
            hostDict = hostDict + hostprDict
            html = client.request(url)
            servers = client.parseDOM(html, 'ul', attrs={'class': 'list-server-items'})[0]
            links = client.parseDOM(servers, 'a', ret='href')
            for link in links:
                if link.startswith('/player.php'):
                    continue
                link = 'https:' + link if not link.startswith('http') else link
                link = link.replace('vidcloud.icu', 'vidembed.io').replace(
                                    'vidcloud9.com', 'vidembed.io').replace(
                                    'vidembed.cc', 'vidembed.io').replace(
                                    'vidnext.net', 'vidembed.me')
                if 'vidembed' in link:
                    for source in self.get_vidembed(link, hostDict):
                        sources.append(source)
                valid, host = source_utils.is_host_valid(link, hostDict)
                if valid:
                    link = link.split('&title=')[0]
                    sources.append({'source': host, 'quality': '720p', 'language': 'en', 'url': link, 'direct': False, 'debridonly': False})
            return sources
        except Exception:
            log_utils.log('sources', 1)
            return sources


    def resolve(self, url):
        return url


    def get_vidembed(self, link, hostDict):
        sources = []
        try:
            html = client.request(link)
            urls = client.parseDOM(html, 'li', ret='data-video')
            if urls:
                for url in urls:
                    url = url.replace('vidcloud.icu', 'vidembed.io').replace(
                                      'vidcloud9.com', 'vidembed.io').replace(
                                      'vidembed.cc', 'vidembed.io').replace(
                                      'vidnext.net', 'vidembed.me')
                    valid, host = source_utils.is_host_valid(url, hostDict)
                    if valid:
                        url = url.split('&title=')[0]
                        sources.append({'source': host, 'quality': '720p', 'language': 'en', 'url': url, 'direct': False, 'debridonly': False})
            return sources
        except Exception:
            #log_utils.log('vidembed', 1)
            return sources


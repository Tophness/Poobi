# -*- coding: utf-8 -*-

import re

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources
#from resources.lib.modules import log_utils


class source:
    def __init__(self):
        self.results = []
        self.domains = ['noxx.to']
        self.base_link = 'https://noxx.to'


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        try:
            url = cleantitle.geturl(tvshowtitle)
            return url
        except:
            #log_utils.log('tvshow', 1)
            return


    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        try:
            if not url:
                return
            url = self.base_link + '/tv/%s/%s/%s/' % (url, season, episode)
            return url
        except:
            #log_utils.log('episode', 1)
            return


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            html = client.scrapePage(url).text
            links = []
            try:
                links += client_utils.parseDOM(html, 'iframe', ret='src')
            except:
                #log_utils.log('sources', 1)
                pass
            try:
                serverlist = client_utils.parseDOM(html, 'div', attrs={'id': 'serverselector'})[0]
                links += client_utils.parseDOM(serverlist, 'button', ret='value')
            except:
                #log_utils.log('sources', 1)
                pass
            for link in links:
                for source in scrape_sources.process(hostDict, link):
                    if scrape_sources.check_host_limit(source['source'], self.results):
                        continue
                    self.results.append(source)
            return self.results
        except:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url



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
        self.genre_filter = ['animation', 'anime']
        self.domains = ['anitaku.pe', 'anitaku.to', 'gogoanimehd.io', 'gogoanimehd.to', 'gogoanime.hu']
        self.base_link = 'https://anitaku.pe'
        self.search_link = '/search.html?keyword=%s'
        self.episode_link = '/%s-episode-%s'


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        try:
            q = self.base_link + self.search_link % cleantitle.get_plus(tvshowtitle)
            r = client.scrapePage(q).text
            r = client_utils.parseDOM(r, 'ul', attrs={'class': 'items'})
            r = client_utils.parseDOM(r, 'li')
            r = [(client_utils.parseDOM(i, 'a', ret='href'), client_utils.parseDOM(i, 'a', ret='title'), re.findall(r'\d{4}', i)) for i in r]
            r = [(i[0][0], i[1][0], i[2][-1]) for i in r if i[0] and i[1] and i[2]]
            r = [i for i in r if cleantitle.match_alias(i[1], aliases) and cleantitle.match_year(i[2], year)]
            url = r[0][0]
            return url
        except:
            #log_utils.log('tvshow', 1)
            return


    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        try:
            if not url:
                return
            url = [i for i in url.strip('/').split('/')][-1]
            url = self.base_link + self.episode_link % (url, int(episode))
            return url
        except:
            #log_utils.log('episode', 1)
            return


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            html = client.scrapePage(url).text
            links = client_utils.parseDOM(html, 'a', ret='data-video')
            for link in links:
                try:
                    for source in scrape_sources.process(hostDict, link):
                        if scrape_sources.check_host_limit(source['source'], self.results):
                            continue
                        self.results.append(source)
                except:
                    #log_utils.log('sources', 1)
                    pass
            return self.results
        except:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url



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
        self.domains = ['rarefilmm.com']
        self.base_link = 'https://rarefilmm.com'
        self.search_link = '/?s=%s+%s'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        try:
            title = cleantitle.get_plus(title)
            check_term = '%s (%s)' % (title, year)
            url = self.base_link + self.search_link % (title, year)
            searchPage = client.scrapePage(url).text
            section = client_utils.parseDOM(searchPage, "h2", attrs={"class": "excerpt-title"})
            for item in section:
                results = re.compile(r'<a href="(.+?)">(.+?)</a>').findall(item)
                for url, checkit in results:
                    if cleantitle.get_plus(check_term) == cleantitle.get_plus(checkit):
                        return url
            return
        except:
            #log_utils.log('movie', 1)
            return


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            html = client.scrapePage(url).text
            links = []
            links += client_utils.parseDOM(html, 'iframe', ret='src')
            links += re.compile(r'href="(.+?)"><strong>').findall(html)
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



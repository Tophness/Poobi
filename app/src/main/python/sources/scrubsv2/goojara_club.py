# -*- coding: utf-8 -*-

import re

from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources
#from resources.lib.modules import log_utils


class source:
    def __init__(self):
        self.results = []
        self.domains = ['goojara.club', 'afdahmovies.cyou', 'o2tvseries.cyou', 'flixtorvideo.to', 'sflixpro.to']
        self.base_link = 'https://goojara.club'
        self.search_link = '/?s=%s'
        self.notes = 'Domains are all active and alts to the base.(Same results.) Might have tv shows but the site doesnt seem to have it mapped out and they must be limited since i havent seen any yet.'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            aliases = eval(data['aliases'])
            title = data['title']
            year = data['year']
            search_url = self.base_link + self.search_link % cleantitle.get_plus(title)
            html = client.scrapePage(search_url).text
            r = client_utils.parseDOM(html, 'div', attrs={'id': r'post-.+?'})
            r = [(client_utils.parseDOM(i, 'a', ret='href'), client_utils.parseDOM(i, 'img', ret='title')) for i in r]
            r = [(i[0][0], i[1][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0]
            try:
                r = [(i[0], re.findall(r'(.+?) \((\d{4})', i[1])) for i in r]
                r = [(i[0], i[1][0]) for i in r if len(i[1]) > 0]
                result_url = [i[0] for i in r if cleantitle.match_alias(i[1][0], aliases) and cleantitle.match_year(i[1][1], year)][0]
            except:
                result_url = [i[0] for i in r if cleantitle.match_alias(i[1], aliases)][0]
            page_html = client.scrapePage(result_url).text
            links = []  # lazy linkin lol.
            links += client_utils.parseDOM(page_html, 'iframe', ret='src')
            links += client_utils.parseDOM(page_html, 'iframe', ret='data-litespeed-src')
            links += client_utils.parseDOM(page_html, 'a', attrs={'rel': 'noopener'}, ret='href')
            for link in links:
                try:
                    if link == 'about:blank':
                        continue
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



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
        self.domains = ['movie4u.live', 'movies4u.co']
        self.base_link = 'https://www1.movie4u.live'
        self.notes = 'the site is pretty much trash and its sources too.'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'year': year}
        url = urlencode(url)
        return url


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        url = {'imdb': imdb, 'tvshowtitle': tvshowtitle, 'year': year}
        url = urlencode(url)
        return url


    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url:
            return
        url = parse_qs(url)
        url = dict([(i, url[i][0]) if url[i] else (i, '') for i in url])
        url['title'], url['premiered'], url['season'], url['episode'] = title, premiered, season, episode
        url = urlencode(url)
        return url


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            title = data['tvshowtitle'] if 'tvshowtitle' in data else data['title']
            season, episode = (data['season'], data['episode']) if 'tvshowtitle' in data else ('0', '0')
            year = data['premiered'].split('-')[0] if 'tvshowtitle' in data else data['year']
            search_title = cleantitle.geturl(title)
            if 'tvshowtitle' in data:
                search_url = self.base_link + '/episodes/%s-%sx%s/' % (search_title, season, episode)
            else:
                search_url = self.base_link + '/movies/%s' % search_title
            item_url = client.request(search_url, timeout='10', output='geturl')
            ## Note: check to see if this geturl bit is still needed.
            item_html = client.scrapePage(item_url).text
            item_date = client_utils.parseDOM(item_html, 'span', attrs={'class': 'date'})[0]
            if not (year in item_date or data['year'] in item_date):
                return self.results
            item_holder = client_utils.parseDOM(item_html, 'div', attrs={'class':'bwa-content'})[0]
            item_holder = client_utils.parseDOM(item_holder, 'a', ret='href')[0]
            page_html = client.scrapePage(item_holder).text
            page_links = client_utils.parseDOM(page_html, 'iframe', ret='src', attrs={'class': 'metaframe rptss'})
            for link in page_links:
                try:
                    link = client.request(link, timeout='10', output='geturl') if 'player.php' in link else link
                    if '1movietv.com' in link:
                        try:
                            v1movietv_html = client.scrapePage(link).text
                            v1movietv_links = client_utils.parseDOM(v1movietv_html, 'iframe', ret='src')
                            for vlink in v1movietv_links:
                                for source in scrape_sources.process(hostDict, vlink):
                                    if scrape_sources.check_host_limit(source['source'], self.results):
                                        continue
                                    self.results.append(source)
                        except:
                            #log_utils.log('sources', 1)
                            pass
                    else:
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



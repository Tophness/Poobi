# -*- coding: utf-8 -*-

from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources
#from resources.lib.modules import log_utils


class source:
    def __init__(self):
        self.results = []
        self.domains = ['filmxy.online', 'filmxy.pw', 'filmxy.me', 'filmxy.one', 'filmxy.tv', 'filmxy.live', 'filmxy.cc']
        self.base_link = 'https://www.filmxy.online'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'year': year}
        url = urlencode(url)
        return url


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            title = cleantitle.geturl(data['title'])
            year = data['year']
            search_url = self.base_link + '/%s-%s' % (title, year)
            html = client.scrapePage(search_url).text
            page_results = client_utils.parseDOM(html, 'div', attrs={'class': 'video-section'})[0]
            try:
                page_urls = client_utils.parseDOM(page_results, 'a', ret='data-player')
                for url in page_urls:
                    try:
                        url = client_utils.replaceHTMLCodes(url)
                        url = client_utils.parseDOM(url, 'iframe', ret='src')[0]
                        for source in scrape_sources.process(hostDict, url):
                            if scrape_sources.check_host_limit(source['source'], self.results):
                                continue
                            self.results.append(source)
                    except:
                        #log_utils.log('sources', 1)
                        pass
            except:
                #log_utils.log('sources', 1)
                pass
            try:
                page_links = client_utils.parseDOM(page_results, 'a', attrs={'target': '_blank'}, ret='href')
                for link in page_links:
                    try:
                        if any(i in link for i in ['vip-membership']):
                            continue
                        for source in scrape_sources.process(hostDict, link):
                            if scrape_sources.check_host_limit(source['source'], self.results):
                                continue
                            self.results.append(source)
                    except:
                        #log_utils.log('sources', 1)
                        pass
            except:
                #log_utils.log('sources', 1)
                pass
            return self.results
        except:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url



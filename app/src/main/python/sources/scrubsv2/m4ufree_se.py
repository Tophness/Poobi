# -*- coding: utf-8 -*-

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources
#from resources.lib.modules import log_utils


class source:
    def __init__(self):
        try:
            self.results = []
            self.domains = ['m4ufree.se']
            self.base_link = 'https://m4ufree.se'
            self.search_link = '/search/%s.html'
            self.ajax_link = '/ajax'
            self.cookie = client.request(self.base_link, output='cookie', timeout='5')
            self.notes = 'Has TV Shows but didnt code it in yet. also has atleast one hidden source thats local/direct type and needs resolved here.'
        except:
            #log_utils.log('__init__', 1)
            return


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        try:
            movie_title = cleantitle.geturl(title)
            check_term = '%s (%s)' % (title, year)
            check_title = cleantitle.get_plus(check_term)
            search_url = self.base_link + self.search_link % movie_title
            html = client.request(search_url, cookie=self.cookie)
            r = client_utils.parseDOM(html, 'div', attrs={'class': 'item'})
            r = [(client_utils.parseDOM(i, 'a', ret='href'), client_utils.parseDOM(i, 'a', ret='title')) for i in r]
            r = [(i[0][0], i[1][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0]
            url = [i[0] for i in r if check_title == cleantitle.get_plus(i[1])][0]
            return url
        except:
            #log_utils.log('movie', 1)
            return


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            url = self.base_link + "/" + url
            html = client.request(url, cookie=self.cookie)
            post_link = self.base_link + self.ajax_link
            token = client_utils.parseDOM(html, 'meta', attrs={'name': 'csrf-token'}, ret='content')[0]
            results = client_utils.parseDOM(html, 'span', ret='data')
            for result in results:
                try:
                    payload = {'url': post_link, '_token': token, 'm4u': result}
                    r = client.request(post_link, post=payload, cookie=self.cookie)
                    link = client_utils.parseDOM(r, 'iframe', ret='src')[0]
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



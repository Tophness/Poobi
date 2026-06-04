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
        self.domains = ['hdbest.net']
        self.base_link = 'https://hdbest.net'
        self.search_link = '/?s=%s'
        self.notes = 'sources dont show for me since its a vidsrc link but all seems well.'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def sources(self, url, hostDict):
        try:
            if url == None:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            aliases = eval(data['aliases'])
            title = data['title']
            year = data['year']
            imdb = data['imdb']
            search_url = self.base_link + self.search_link % cleantitle.get_plus(title)
            self.cookie = client.request(self.base_link, output='cookie', timeout='5')
            html = client.request(search_url, cookie=self.cookie)
            r = client_utils.parseDOM(html, 'article')
            r = zip(client_utils.parseDOM(r, 'a', ret='href'), client_utils.parseDOM(r, 'a', ret='title'))
            try:
                r = [(i[0], re.findall(r'(.+?) \((\d{4})', i[1])) for i in r]
                r = [(i[0], i[1][0]) for i in r if len(i[1]) > 0]
                result_url = [i[0] for i in r if cleantitle.match_alias(i[1][0], aliases) and cleantitle.match_year(i[1][1], year)][0]
            except:
                result_url = [i[0] for i in r if cleantitle.match_alias(i[1], aliases)][0]
            result_html = client.request(result_url, cookie=self.cookie)
            result_links = client_utils.parseDOM(result_html, 'iframe', ret='src')
            for link in result_links:
                for source in scrape_sources.process(hostDict, link):
                    self.results.append(source)
            return self.results
        except:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url



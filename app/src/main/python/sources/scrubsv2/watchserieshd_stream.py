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
        self.domains = ['watchserieshd.stream']
        self.base_link = 'https://watchserieshd.stream'
        self.search_link = '/?s=%s'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        url = {'imdb': imdb, 'tvshowtitle': tvshowtitle, 'aliases': aliases, 'year': year}
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
            aliases = eval(data['aliases'])
            title = data['tvshowtitle'] if 'tvshowtitle' in data else data['title']
            season, episode = (data['season'], data['episode']) if 'tvshowtitle' in data else ('0', '0')
            year = data['premiered'].split('-')[0] if 'tvshowtitle' in data else data['year']
            search_url = self.base_link + self.search_link % cleantitle.get_plus(title)
            search_request = client.request(search_url, output='extended')
            html = search_request[0]
            headers = search_request[1]
            r = client_utils.parseDOM(html, 'div', attrs={'class': 'item'})
            r = [(client_utils.parseDOM(i, 'a', ret='href'), client_utils.parseDOM(i, 'a', ret='title')) for i in r]
            r = [(i[0][0], i[1][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0]
            r = [(i[0], re.findall(r'(.+?)(?:\((\d{4}))', i[1])) for i in r]
            r = [(i[0], i[1][0]) for i in r if len(i[1]) > 0]
            result_url = [i[0] for i in r if cleantitle.match_alias(i[1][0], aliases) and cleantitle.match_year(i[1][1], year, data['year'])][0]
            if 'tvshowtitle' in data:
                check_url = '-season-%s-episode-%s/' % (season, episode)
                show_html = client.request(result_url, headers=headers)
                results = client_utils.parseDOM(show_html, 'a', ret='href')
                result_url = [i for i in results if check_url in i][0]
            result_html = client.request(result_url, headers=headers)
            try:
                qual = client_utils.parseDOM(result_html, 'span', attrs={'class': 'quality'})[0]
            except:
                qual = ''
            if 'tvshowtitle' in data:
                links = client_utils.parseDOM(result_html, 'li', ret='data-vs')
            else:
                links = client_utils.parseDOM(result_html, 'div', ret='data-vs')
            for link in links:
                try:
                    link = client.request(link, headers=headers, output='geturl')
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



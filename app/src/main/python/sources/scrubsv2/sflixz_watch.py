# -*- coding: utf-8 -*-

import re
from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources
#from resources.lib.modules import log_utils

DOM = client_utils.parseDOM


class source:
    def __init__(self):
        self.results = []
        self.domains = ['sflixz.watch']
        self.base_link = 'https://sflixz.watch'
        self.search_link = '/search?keyword=%s'


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
            search_html = client.scrapePage(search_url).text
            r = DOM(search_html, 'div', attrs={'class': 'inner'})
            r = [(DOM(i, 'a', attrs={'class': 'title'}, ret='href'), DOM(i, 'a', attrs={'class': 'title'}), DOM(i, 'div', attrs={'class': 'metadata'})) for i in r]
            r = [(i[0][0], i[1][0], i[2][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0 and len(i[2]) > 0]
            if 'tvshowtitle' in data:
                result_link = [i[0] for i in r if cleantitle.match_alias(i[1], aliases) and '/series/' in i[0]][0]
                if result_link:
                    result_url = result_link + '/%s-%s/' % (season, episode)
            else:
                r = [(i[0], i[1], re.findall(r'>(\d{4})<', client_utils.clean_html(i[2]))) for i in r]
                r = [(i[0], i[1], i[2][0]) for i in r if len(i[2]) > 0]
                result_url = [i[0] for i in r if cleantitle.match_alias(i[1], aliases) and cleantitle.match_year(i[2], year) and '/movie/' in i[0]][0]
            result_html = client.scrapePage(result_url).text
            try:
                check_year = re.findall(r'/year/(\d{4})/', result_html)[0]
                check_year = cleantitle.match_year(check_year, year, data['year'])
            except:
                check_year = 'Failed to find year info.' # Used to fake out the year check code.
            if not check_year:
                return self.results
            servers_url = re.compile(r'const pl_url = \'(.+?)\';').findall(result_html)[0]
            servers_html = client.scrapePage(servers_url).text
            # custom_hoster_domains is a lazy way to do things since i didnt see what all the site returns.
            custom_hoster_domains = ['//cdnvid.art/', '//videofast.art/']
            # this is just from one movie and one tv show test.
            server_urls = DOM(servers_html, 'div', ret='data-id')
            for server_url in server_urls:
                #log_utils.log('server_url: '+repr(server_url))
                try:
                    if any(i in server_url for i in custom_hoster_domains):
                        server_html = client.scrapePage(server_url).text
                        server_link = DOM(server_html, 'iframe', ret='src')[0]
                        #log_utils.log('server_link: '+repr(server_link))
                        for source in scrape_sources.process(hostDict, server_link):
                            if scrape_sources.check_host_limit(source['source'], self.results):
                                continue
                            self.results.append(source)
                    else:
                        for source in scrape_sources.process(hostDict, server_url):
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



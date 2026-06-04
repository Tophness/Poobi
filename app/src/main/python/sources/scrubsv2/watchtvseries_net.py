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
        self.domains = ['watch-tvseries.net', 'watch-tvseries.me']
        self.base_link = 'https://watch-tvseries.net'
        self.search_link = '/search?q=%s'
        self.notes = 'Dupe of tvids_net. Sources seem to be all holders like 2embed which fail to work on my end. Likely needs more testing and more work done.'


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
            type_check = '/watch' if 'tvshowtitle' in data else '/movies'
            html = client.request(search_url)
            r = DOM(html, 'div', attrs={'class': r'relative text.+?'})
            if 'tvshowtitle' in data:
                r = [(DOM(i, 'a', ret='href'), DOM(i, 'img', ret='alt')) for i in r]
                r = [(i[0][0], i[1][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0]
                result_url = [i[0] for i in r if cleantitle.match_alias(i[1], aliases) and type_check in i[0]][0]
                sepi = '/season-%02d-episode-%02d-%s' % (int(season), int(episode), cleantitle.geturl(data['title']))
                result_url = result_url + sepi
            else:
                r = [(DOM(i, 'a', ret='href'), DOM(i, 'img', ret='alt'), re.findall(r'/years/(\d{4})', i)) for i in r]
                r = [(i[0][0], i[1][0], i[2][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0 and len(i[2]) > 0]
                result_url = [i[0] for i in r if cleantitle.match_alias(i[1], aliases) and cleantitle.match_year(i[2], year) and type_check in i[0]][0]
            result_url = self.base_link + result_url if not any(x in result_url for x in self.domains) else result_url
            html = client.request(result_url)
            links = DOM(html, 'a', ret='data-url')
            for link in links:
                try:
                    if '//player.wplay.me/' in link:
                        try:
                            redirect = client.request(link, output='geturl')
                            if redirect:
                                link = redirect
                        except:
                            #log_utils.log('sources', 1)
                            pass
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



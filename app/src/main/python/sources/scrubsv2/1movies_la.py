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
        self.domains = ['1movies.la']
        self.base_link = 'https://1movies.la'
        self.search_link = '/search/%s'


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
            check_term = '%s - Season %s' % (title, season) if 'tvshowtitle' in data else title
            check_title = cleantitle.get(check_term)
            search_url = self.base_link + self.search_link % cleantitle.get_utf8(title)
            r = client.scrapePage(search_url).text
            r = DOM(r, 'div', attrs={'class': 'ml-item'})
            r = [(DOM(i, 'a', ret='href'), DOM(i, 'a', ret='title'), re.findall(r'<h2>[(](\d{4})[)]</h2>', i)) for i in r]
            r = [(i[0][0], i[1][0], i[2][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0 and len(i[2]) > 0]
            if 'tvshowtitle' in data:
                check_season = 'season %s' % season
                result_url = [i[0] for i in r if check_title == cleantitle.get(i[1]) and i[0].startswith('/tv/')][0]
                result_link = self.base_link + result_url
            else:
                result_url = [i[0] for i in r if cleantitle.match_alias(i[1], aliases) and cleantitle.match_year(i[2], year) and i[0].startswith('/movie/')][0]
                result_link = self.base_link + result_url
            r = client.scrapePage(result_link).text
            if 'tvshowtitle' in data:
                check_episode = '-season-%s-episode-%s.html' % (season, episode)
                check_episode2 = '-season-%s-episode-%02d.html' % (season, int(episode))
                r = DOM(r, 'div', attrs={'class': 'les-content'})[0]
                r = DOM(r, 'a', ret='href')
                episode_url = [i for i in r if i.endswith(check_episode) or i.endswith(check_episode2)][0]
                episode_link = self.base_link + episode_url
                r = client.scrapePage(episode_link).text
            result_links = DOM(r, 'a', ret='data-file')
            for link in result_links:
                for source in scrape_sources.process(hostDict, link):
                    self.results.append(source)
            return self.results
        except:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url



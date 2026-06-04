# -*- coding: utf-8 -*-

import re

from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources
from resources.lib.modules import search_engines
#from resources.lib.modules import log_utils


class source:
    def __init__(self):
        self.results = []
        self.domains = ['tubitv.com']
        self.base_link = 'https://tubitv.com'
        self.notes = 'Not very good due to the issues with using the search_engines module lol.(Bing and whatnot suck lmao.)'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        if tvshowtitle == 'House':
            tvshowtitle = 'House M.D.'
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
            if 'tvshowtitle' in data:
                search_query = search_engines.make_search_query(self.domains[0], title, season=season, domainprefix='')
                check_title = '%s Season %s - Free TV Shows | Tubi' % (title, season)
            else:
                search_query = search_engines.make_search_query(self.domains[0], title, year=year, domainprefix='')
                check_title = '%s (%s) - Free Movies | Tubi' % (title, year)
            check_title = cleantitle.get_plus(check_title)
            headers = {'User-Agent': client.UserAgent, 'Referer': self.base_link}
            results = search_engines.bing(search_query, parse=True)
            result_url = [i[0] for i in results if check_title in cleantitle.get_plus(i[1]) and self.domains[0] in i[0]][0]
            if 'tvshowtitle' in data:
                sepi = '/s%02d-e%02d-' % (int(season), int(episode))
                result_html = client.scrapePage(result_url, headers=headers).text
                results = client_utils.parseDOM(result_html, 'a', attrs={'class': 'web-content-tile__title'}, ret='href')
                result_url = [i for i in results if sepi in i.lower()][0]
                result_url = self.base_link + result_url
                result_url = result_url.replace('/tv-shows/', '/embed/')
            else:
                result_url = result_url.replace('/movies/', '/embed/')
            result_html = client.scrapePage(result_url, headers=headers).text
            video_resources = re.compile(r'"video_resources":\[(.+?)\],', re.DOTALL).findall(result_html)[0]
            video_links = re.compile(r'{"manifest":{"url":"(.+?)","duration".+?"codec":"(.+?)","resolution":"(.+?)"}', re.DOTALL).findall(video_resources)
            for link, codec, res in video_links:
                try:
                    link = link.replace('\\u002F', '/')
                    qual = '%s %s .m3u8' % (codec, res)
                    item = scrape_sources.make_direct_item(hostDict, link, host=None, info=qual, referer=result_url, prep=True)
                    if item:
                        if not scrape_sources.check_host_limit(item['source'], self.results):
                            self.results.append(item)
                except:
                    #log_utils.log('sources', 1)
                    pass
            return self.results
        except:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url



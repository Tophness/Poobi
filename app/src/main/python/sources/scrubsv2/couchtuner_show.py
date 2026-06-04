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
        self.domains = ['couchtuner.show']
        self.base_link = 'https://www.couchtuner.show'
        self.search_link = '/?s=%s'
        self.notes = 'Some stuff is "linked" aka supposed to be stored on their other site and shared but those sites all seem dead so the scraper kinda sucks now.'


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
            title = data['tvshowtitle']
            season = data['season']
            episode = data['episode']
            ep_title = data['title']
            title_cleaned = cleantitle.geturl(title)
            ep_title_cleaned = cleantitle.geturl(ep_title)
            ep_url1 = '/%s-season-%s-episode-%s/' % (title_cleaned, season, episode)
            ep_url2 = '/%s-season-%s-episode-%s-%s/' % (title_cleaned, season, episode, ep_title_cleaned)
            search = cleantitle.get_plus(title)
            search_url = self.base_link + self.search_link % search
            self.cookie = client.request(self.base_link, output='cookie', timeout='5')
            r = client.request(search_url, cookie=self.cookie)
            r = client_utils.parseDOM(r, 'div', attrs={'class': 'movie-content'})
            r = [(client_utils.parseDOM(i, 'a', ret='href'), client_utils.parseDOM(i, 'a')) for i in r]
            r = [(i[0][0], i[1][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0]
            result_url = [i[0] for i in r if cleantitle.match_alias(i[1], aliases)][0]
            r = client.request(result_url, cookie=self.cookie)
            r = r.replace('\r', '').replace('\n', '').replace('\t', '').replace('  ', '').replace('<i class="fas fa-play"></i>', '')
            r = client_utils.parseDOM(r, 'div', attrs={'class': 'episode-watch-wrap'})
            r = client_utils.parseDOM(r, 'li')
            r = [(client_utils.parseDOM(i, 'a', ret='href'), client_utils.parseDOM(i, 'a')) for i in r]
            r = [(i[0][0], i[1][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0]
            final_url = [i[0] for i in r if ep_url1 in i[0] or ep_url2 in i[0]][0]
            r = client.request(final_url, cookie=self.cookie)
            try:
                linked = client_utils.parseDOM(r, 'a', attrs={'rel': 'bookmark'}, ret='href')[0]
                if linked:
                    r += client.request(linked, cookie=self.cookie)
            except:
                pass
            links = client_utils.parseDOM(r, 'iframe', ret='src')
            for link in links:
                try:
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



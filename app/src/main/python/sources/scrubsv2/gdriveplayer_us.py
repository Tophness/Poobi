# -*- coding: utf-8 -*-

from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources
from resources.lib.modules import trakt
#from resources.lib.modules import log_utils


class source:
    def __init__(self):
        self.results = []
        self.domains = ['api.gdriveplayer.us']
        self.base_link = 'https://api.gdriveplayer.us'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'localtitle': localtitle, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        url = {'imdb': imdb, 'tvshowtitle': tvshowtitle, 'localtvshowtitle': localtvshowtitle, 'aliases': aliases, 'year': year}
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
            imdb = data['imdb']
            aliases = eval(data['aliases'])
            if 'tvshowtitle' in data:
                title = data['tvshowtitle']
                localtitle = data['localtvshowtitle'] if 'localtvshowtitle' in data else data['tvshowtitle']
            else:
                title = data['title']
                localtitle = data['localtitle'] if 'localtitle' in data else data['title']
            year = data['premiered'].split('-')[0] if 'tvshowtitle' in data else data['year']
            season, episode = (data['season'], data['episode']) if 'tvshowtitle' in data else ('0', '1')
            genres = trakt.getGenre('show', 'imdb', imdb) if 'tvshowtitle' in data else trakt.getGenre('movie', 'imdb', imdb)
            try:
                if 'tvshowtitle' in data:
                    search_url = self.base_link + '/v2/series/imdb/%s/season%s' % (imdb, season)
                else:
                    search_url = self.base_link + '/v1/imdb/%s' % imdb
                result = client.scrapePage(search_url).json()
                if 'tvshowtitle' in data:
                    episodes = result[0].get('list_episode')
                    episodes = [(i.get('episode'), i.get('player_url')) for i in episodes]
                    player_url = [i[1] for i in episodes if i[0] == episode][0]
                else:
                    player_url = result.get('player_url')
            except:
                player_url = ''
            if not player_url and any(x in ['animation', 'anime'] for x in genres):
                try:
                    search_url = self.base_link + '/v1/animes/search?title=%s&limit=100' % cleantitle.get_plus(title)
                    results = client.scrapePage(search_url).json()
                    results = [(i.get('title').lower().replace('(dub)', '').replace('(sub)', ''), i.get('player_url')) for i in results]
                    player_url = [i[1].split('episode=')[0]+'episode='+episode for i in results if cleantitle.match_alias(i[0], aliases)]
                except:
                    search_url = self.base_link + '/v1/animes/search?title=%s&limit=100' % cleantitle.get_plus(localtitle)
                    results = client.scrapePage(search_url).json()
                    results = [(i.get('title').lower().replace('(dub)', '').replace('(sub)', ''), i.get('player_url')) for i in results]
                    player_url = [i[1].split('episode=')[0]+'episode='+episode for i in results if cleantitle.match_alias(i[0], aliases)]
            if not player_url and any(x == 'drama' for x in genres):
                try:
                    search_url = self.base_link + '/v1/drama/search?title=%s&limit=100' % cleantitle.get_plus(title)
                    results = client.scrapePage(search_url).json()
                    results = [(i.get('title'), i.get('player_url')) for i in results]
                    player_url = [i[1].split('episode=')[0]+'episode='+episode for i in results if cleantitle.match_alias(i[0], aliases)]
                except:
                    search_url = self.base_link + '/v1/drama/search?title=%s&limit=100' % cleantitle.get_plus(localtitle)
                    results = client.scrapePage(search_url).json()
                    results = [(i.get('title'), i.get('player_url')) for i in results]
                    player_url = [i[1].split('episode=')[0]+'episode='+episode for i in results if cleantitle.match_alias(i[0], aliases)]
            if not player_url:
                return self.results
            if not isinstance(player_url, (list, tuple)):
                player_url = [player_url]
            for url in list(player_url):
                try:
                    url = scrape_sources.prepare_link(url)
                    if not url:
                        continue
                    html = client.scrapePage(url).text
                    servers = client_utils.parseDOM(html, 'ul', attrs={'class': 'list-server-items'})[0]
                    links = client_utils.parseDOM(servers, 'a', ret='href')
                    for link in links:
                        try:
                            if not link or link.startswith('/player.php'):
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



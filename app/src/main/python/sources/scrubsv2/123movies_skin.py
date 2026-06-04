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
        self.domains = ['123-movies.golf', '123-movies.icu', '123-movies.one', '123-movies.skin']
        self.base_link = 'https://123-movies.golf'
        self.search_link = '/?s=%s'
        self.notes = 'dupe site of soap2day_fan.'


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
            search_term = '%s Season %s Episode %s' % (title, season, episode) if 'tvshowtitle' in data else title
            search_title = cleantitle.get_plus(search_term)
            check_title = cleantitle.get(search_term)
            search_link = self.base_link + self.search_link % search_title
            r = client.scrapePage(search_link).text
            r = client_utils.parseDOM(r, 'div', attrs={'class': 'ml-item'})
            r = [(client_utils.parseDOM(i, 'a', ret='href'), client_utils.parseDOM(i, 'a', ret='oldtitle'), re.findall(r'(\d{4})', i)) for i in r]
            r = [(i[0][0], i[1][0], i[2][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0 and len(i[2]) > 0]
            if 'tvshowtitle' in data:
                try:
                    url = [i[0] for i in r if check_title == cleantitle.get(i[1]) and year == i[2]][0]
                except:
                    url = self.base_link + '/episode/%s-season-%s-episode-%s/' % (cleantitle.geturl(title), season, episode)
            else:
                try:
                    url = [i[0] for i in r if check_title == cleantitle.get(i[1]) and year == i[2]][0]
                except:
                    url = self.base_link + '/%s/%s/' % (cleantitle.geturl(title), year)
            html = client.scrapePage(url).text
            links = client_utils.parseDOM(html, 'iframe', ret='src')
            for link in links:
                if '/theneedful.html' in link:
                    continue
                if '1movietv' in link:
                    try:
                        html = client.scrapePage(link).text
                        vurls = []
                        vurls += client_utils.parseDOM(html, 'iframe', ret='src')
                        vurls += client_utils.parseDOM(html, 'iframe', ret='class src')
                        for vurl in vurls:
                            if '1movietv' in vurl:
                                continue
                            for source in scrape_sources.process(hostDict, vurl):
                                self.results.append(source)
                    except:
                        #log_utils.log('sources', 1)
                        pass
                else:
                    for source in scrape_sources.process(hostDict, link):
                        self.results.append(source)
            return self.results
        except:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url



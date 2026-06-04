# -*- coding: utf-8 -*-

import re
import base64

from six import ensure_text
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
        self.domains = ['vumoo.mx']
        self.base_link = 'https://vumoo.mx'
        self.search_link = '/search-movies/%s.html'
        self.notes = 'sim/dupe site of flixwave_me, okmov_to, and upmovies_net.'


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


    def getlinks(self, r):
        newlinks = []
        try:
            r = DOM(r, 'div', {'class': 'server_line'})
            links = [(DOM(i, 'a', ret='href')[0], DOM(i, 'p', attrs={'class': 'server_servername'})[0]) for i in r]
            for link in links:
                try:
                    host = re.findall(r'<a.*?>(.*?)</a>', link[1])
                    host = host[0].lower()
                    host = re.sub(r'server|link\s+\d+', '', host)
                    host = client_utils.replaceHTMLCodes(host)
                    if not host or 'other' in host:
                        continue
                    if 'vip' in host:
                        host = 'eplayvid'
                    if 'voesx' in host:
                        host = 'voe'
                    if 'aparat' in host:
                        host = 'wolfstream'
                    newlinks.append([link[0], host])
                except:
                    #log_utils.log('getlinks', 1)
                    pass
            return newlinks
        except:
            #log_utils.log('getlinks', 1)
            pass


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
            search_term = '%s Season %s' % (title, season) if 'tvshowtitle' in data else title
            search_url = self.base_link + self.search_link % cleantitle.get_plus(search_term)
            html = client.scrapePage(search_url).text
            r = DOM(html, 'div', attrs={'class': 'itemInfo'})
            if not r and 'tvshowtitle' in data:
                search_url = self.base_link + self.search_link % cleantitle.get_plus(title)
                html = client.scrapePage(search_url).text
                r = DOM(html, 'div', attrs={'class': 'itemInfo'})
            r = [(DOM(i, 'a', ret='href'), re.findall(r'Year:\s+(\d{4})', i), re.findall(r'<a.*?>(.*?)</a>', i)) for i in r]
            r = [(i[0][0], i[1][0], i[2][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0 and len(i[2]) > 0]
            r = [(url, year, title.replace(',', ' ')) for url, year, title in r]
            url = str()
            if 'tvshowtitle' in data:  # tv shows and cartoons.
                r = [(i[0], i[1], re.findall(r'(.+?) (?:SEASON|PART) (\d+)$', (i[2]), re.IGNORECASE)) for i in r]
                r = [(i[0], i[1], i[2][0]) for i in r if len(i[2]) > 0]
                url = [i[0] for i in r if cleantitle.match_alias(i[2][0], aliases) and cleantitle.match_year(i[1], year, data['year']) and i[2][1] == season][0]
                r = client.scrapePage(url).text
                r = DOM(r, 'div', attrs={'id': 'details'})[0]
                episode_url_list = DOM(r, 'a', ret='href')
                sepi = r'season-%1d/episode-%1d' % (int(season), int(episode))
                sepipart = r'part-%1d/episode-%1d' % (int(season), int(episode))
                patterns = [sepi, sepipart]
                url = [url for url in episode_url_list if any(re.search(pattern, url) for pattern in patterns)][0]
                if not url:  # anime and some odd shows.
                    url = [i[0] for i in r if cleantitle.geturl(title) in i[0] and cleantitle.match_year(i[1], year, data['year'])][0]
                    sepi = '/episode-%1d.html' % int(episode)
                    r = client.scrapePage(url).text
                    r = DOM(r, 'div', attrs={'id': 'details'})[0]
                    r = DOM(r, 'a', ret='href')
                    url = [i for i in r if sepi in i and not 'season-' in i][0]
                if not url:
                    return self.results
            else:  # movies.
                r = [(i[0], i[1], re.findall(r'(.+?)(?:\(\d+\)|$)', client_utils.replaceHTMLCodes(i[2]))) for i in r]
                r = [(i[0], i[1], i[2][0]) for i in r if len(i[2]) > 0]
                url = [i[0] for i in r if cleantitle.match_alias(i[2], aliases) and cleantitle.match_year(i[1], year)][0]
            r = client.scrapePage(url).text
            links = self.getlinks(r)
            for link in links:
                try:
                    host = link[1]
                    item = scrape_sources.make_item(hostDict, link[0], host=host, info=None, prep=True)
                    if item:
                        if scrape_sources.check_host_limit(item['source'], self.results):
                            continue
                        self.results.append(item)
                except:
                    #log_utils.log('sources', 1)
                    pass
            return self.results
        except:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        if any(x in url for x in self.domains):
            try:
                r = client.scrapePage(url).text
                try:
                    v = re.findall(r'document.write\(Base64.decode\("(.+?)"\)', r)[0]
                    b64 = base64.b64decode(v)
                    b64 = ensure_text(b64, errors='ignore')
                    try:
                        url = DOM(b64, 'iframe', ret='src')[0]
                    except:
                        url = DOM(b64, 'a', ret='href')[0]
                except:
                    u = DOM(r, 'div', attrs={'class': 'player'})
                    url = DOM(u, 'a', ret='href')[0]
                url = scrape_sources.prepare_link(url)
                return url
            except:
                #log_utils.log('resolve', 1)
                pass
        else:
            return url


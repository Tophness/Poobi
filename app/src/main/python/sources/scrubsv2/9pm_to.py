# -*- coding: utf-8 -*-

import re
from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import source_utils
#from resources.lib.modules import log_utils

DOM = client_utils.parseDOM


class source:
    def __init__(self):
        self.results = []
        self.domains = ['9pm.to']
        self.base_link = 'https://9pm.to'
        self.search_link = '/search?t=%s&q=%s'


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
            hdlr = 'S%02dE%02d' % (int(season), int(episode)) if 'tvshowtitle' in data else year
            hdlr = hdlr.lower()
            headers = {
                'User-Agent': 'Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:85.0) Gecko/20100101 Firefox/85.0',
                'Accept': 'accept: text/plain, */*; q=0.01',
                'Accept-Language': 'en-US,en;q=0.9',
                'Referer': self.base_link,
                'Connection': 'keep-alive',
                'sec-ch-ua-mobile': '?0',
                'sec-fetch-dest': 'empty',
                'sec-fetch-mode': 'cors',
                'sec-fetch-site': 'same-origin',
                'x-requested-with': 'XMLHttpRequest',
            }
            # find t= param as it may change in the future
            get_url = self.base_link + '/javascripts/9pm-v1.0.1.min.js'
            r = client.request(get_url, headers=headers)
            pattern = r'/search\?t=([^"]*)'
            tt = re.search(pattern, r).group(1)
            search_url = f"{self.base_link}{self.search_link % (tt, cleantitle.geturl(title))}"
            r = client.scrapePage(search_url, headers=headers)
            r = r.json()
            suggestions = r.get('suggestions', [])
            if 'tvshowtitle' in data:
                r = [i for i in suggestions if i['data']['type'] == 'tv-series']
                sea = f"season-{season}"
                result_url = next((i['data']['href'] for i in r if title in i['value'] and sea in i['data']['href']), None)
            else:
                r = [i for i in suggestions if i['data']['type'] == 'movies']
                # movies include the year so let search for that
                result_url = next((i['data']['href'] for i in r if title == re.sub(r'\s*\(.*?\)', '', i['value']) and year in i['value']), None)
            if not result_url:
                return
            result_url = f"{self.base_link}{result_url}"
            html = client.request(result_url, headers=headers)
            r = DOM(html, 'ul', attrs={'class': 'episodes'})[0]
            r = DOM(r, 'li')
            r = [(DOM(i, 'a', attrs={'class': 'play'}, ret='embedUrl'), DOM(i, 'a', attrs={'class': 'play'})) for i in r]
            results = [(i[0][0], i[1][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0]
            for item in results:
                try:
                    if 'tvshowtitle' in data:
                        if not hdlr == item[1].lower():
                            continue
                        qual = 'SD'
                    else:
                        qual = item[1]
                    link = item[0]
                    if not link:
                        continue
                    headers.update({'Referer': result_url})
                    r = client.request(link, headers=headers)
                    pattern = r'//[^"]+\.m3u8'
                    link = re.search(pattern, r).group(0)
                    link = 'https:' + link if link.startswith('//') else link
                    host = source_utils.get_host(link)
                    host = '.'.join(host.split('.')[-2:]) if len(host.split('.')) > 1 else host
                    source = {'source': host, 'quality': qual, 'info': '', 'url': link, 'direct': True}
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



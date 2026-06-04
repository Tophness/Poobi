# -*- coding: utf-8 -*-

import requests
from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import control
from resources.lib.modules import source_utils
#from resources.lib.modules import log_utils


class source:
    def __init__(self):
        self.results = []
        self.api_key = control.setting('filepursuit.api') or ''
        self.base_link = 'https://filepursuit.p.rapidapi.com'
        self.notes = 'API info found at https://rapidapi.com/azharxes/api/filepursuit'


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
            if not self.api_key:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            aliases = eval(data['aliases'])
            title = data['tvshowtitle'] if 'tvshowtitle' in data else data['title']
            season, episode = (data['season'], data['episode']) if 'tvshowtitle' in data else ('0', '0')
            year = data['premiered'].split('-')[0] if 'tvshowtitle' in data else data['year']
            hdlr = 'S%02dE%02d' % (int(season), int(episode)) if 'tvshowtitle' in data else year
            search_term = '%s %s' % (title, hdlr)
            search_query = cleantitle.get_plus(search_term)
            search_params = {"q": search_query, "type": "video"}
            headers = {'X-RapidAPI-Key': self.api_key, 'X-RapidAPI-Host': 'filepursuit.p.rapidapi.com'}
            r = requests.request("GET", self.base_link, headers=headers, params=search_params).json()
            if 'not_found' in r['status']:
                return self.results
            results = r['files_found']
            for item in results:
                try:
                    name = item.get('file_name', '')
                    if not name:
                        name = item.get('file_link', '').split('/')[-1]
                    cleaned_name = cleantitle.get(name)
                    if not hdlr.lower() in cleaned_name:
                        continue
                    for alias in aliases:
                        if not cleantitle.get(alias['title']) in cleaned_name:
                            continue
                    if any(x in name.lower() for x in ['trailer', 'promo']):
                        continue
                    link = item.get('file_link', '')
                    if not link:
                        continue
                    host = item.get('referrer_host', '')
                    if not host:
                        valid, host = source_utils.is_host_valid(link, hostDict)
                    referrer = item.get('referrer_link', '')
                    if not referrer:
                        referrer = link
                    try:
                        size = float(item['file_size_bytes']) / 1073741824
                        size = '%.2f GB' % size
                    except:
                        size = ''
                    if not size:
                        size = item.get('file_size', '')
                    quality, info = source_utils.get_release_quality(link, name)
                    if not size:
                        info = info + ' | ' + name
                    else:
                        info = info + ' | ' + size + ' | ' + name
                    link += source_utils.append_headers({'Referer': referrer})
                    self.results.append({'source': host, 'quality': quality, 'info': info, 'url': link, 'direct': True})
                except:
                    #log_utils.log('sources', 1)
                    pass
            return self.results
        except:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url



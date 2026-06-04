# -*- coding: UTF-8 -*-



import re
from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import source_utils
from resources.lib.modules import log_utils


try: from urlparse import parse_qs, urljoin
except ImportError: from urllib.parse import parse_qs, urljoin
try: from urllib import urlencode, quote_plus
except ImportError: from urllib.parse import urlencode, quote_plus




class source:
    def __init__(self):
        self.priority = 1
        self.language = ['en']
        self.domains = ['cmovies.online']
        self.base_link = 'http://cmovies.vc/'
        self.movie_link = '/film/%s/watching.html?ep=0'
        self.tv_link = '/film/%s-season-%s/watching.html?ep=%s'


    def movie(self, imdb, title, localtitle, aliases, year):
        try:
            url = {'imdb': imdb, 'title': title, 'year': year}
            url = urlencode(url)
            return url
        except:
            return


    def tvshow(self, imdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        try:
            url = {'imdb': imdb, 'tvdb': tvdb, 'tvshowtitle': tvshowtitle, 'year': year}
            url = urlencode(url)
            return url
        except:
            return


    def episode(self, url, imdb, tvdb, title, premiered, season, episode):
        try:
            if url is None: return

            url = parse_qs(url)
            url = dict([(i, url[i][0]) if url[i] else (i, '') for i in url])
            url['title'], url['premiered'], url['season'], url['episode'] = title, premiered, season, episode
            url = urlencode(url)
            return url
        except:
            return


    def sources(self, url, hostDict, hostprDict):
        sources = []
        try:
            if url == None:
                return sources

            hostDict = hostprDict + hostDict

            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])

            title = data['tvshowtitle'] if 'tvshowtitle' in data else data['title']
            year = data['year']

            c_title = cleantitle.geturl(title).replace('--', '-')
            query = self.movie_link % c_title if not 'tvshowtitle' in data else self.tv_link % (c_title, data['season'], data['episode'])
            link = urljoin(self.base_link, query)
            #log_utils.log('cmovies link: ' + link)

            r = client.request(link)
            qual = re.compile('class="quality">(.+?)</span>').findall(r)[0]
            u = re.compile('data-video="(.+?)"').findall(r)
            for url in u:
                quality, _ = source_utils.get_release_quality(qual, url)
                if not url.startswith('http'):
                    url =  "https:" + url
                valid, host = source_utils.is_host_valid(url, hostDict)
                if valid:
                    sources.append({'source': host, 'quality': quality, 'language': 'en', 'url': url, 'direct': False, 'debridonly': False})
            return sources
        except:
            log_utils.log('CMOVIES - Exception', 1)
            return sources


    def resolve(self, url):
        return url



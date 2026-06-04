# -*- coding: utf-8 -*-

import re
from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import decryption
from resources.lib.modules import scrape_sources
#from resources.lib.modules import log_utils


class source:
    def __init__(self):
        self.results = [] # Might be able to use bstsrs.cc too but would need to look at it.
        self.domains = ['bstsrs.in', 'bstsrs.one']
        self.base_link = 'https://bstsrs.in'


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
            imdb, title, year = (data['imdb'], data['tvshowtitle'], data['year'])
            season, episode = (data['season'], data['episode'])
            url_title1 = '%s %s' % (title, year)
            url_title1 = cleantitle.geturl(url_title1)
            url_title2 = cleantitle.geturl(title)
            url_sepi = 's%02de%02d' % (int(season), int(episode))
            headers = {'User-Agent': client.UserAgent, 'Referer': self.base_link}
            search_url = self.base_link + '/show/%s-%s/season/%s/episode/%s' % (url_title1, url_sepi, int(season), int(episode))
            html = client.scrapePage(search_url, headers=headers).text
            if not 'imdb.com/title/%s/' % imdb in html:
                search_url = self.base_link + '/show/%s-%s/season/%s/episode/%s' % (url_title2, url_sepi, int(season), int(episode))
                html = client.scrapePage(search_url, headers=headers).text
            if not 'imdb.com/title/%s/' % imdb in html:
                return self.results
            links = re.compile(r"window\.open\(dbneg\('(.+?)'\)", re.DOTALL).findall(html)
            for link in links:
                try:
                    link = decryption.decode(link)
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



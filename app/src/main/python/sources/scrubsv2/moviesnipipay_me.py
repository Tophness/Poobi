# -*- coding: utf-8 -*-

import re
import base64

from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources
#from resources.lib.modules import log_utils


class source:
    def __init__(self):
        self.results = []
        self.domains = ['moviesnipipay.me']
        self.base_link = 'https://moviesnipipay.me'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'year': year}
        url = urlencode(url)
        return url


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        url = {'imdb': imdb, 'tvshowtitle': tvshowtitle, 'year': year}
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
            title = data['tvshowtitle'] if 'tvshowtitle' in data else data['title']
            hdlr = 's%02de%02d' % (int(data['season']), int(data['episode'])) if 'tvshowtitle' in data else data['year']
            search_term = '%s %s' % (title, hdlr)
            search_title = cleantitle.geturl(search_term)
            search_link = self.base_link + '/%s/' % search_title
            html = client.scrapePage(search_link).text
            #try: all seem to be shrtlnkz.com trash
                #downloads = client_utils.parseDOM(html, 'div', attrs={'class': 'dl-item'})[0]
                #downloads = re.compile(r'<a href="(.+?)".+?domain=(.+?)">').findall(downloads)
                #for dl_link, dl_host in downloads:
                    #if 'subscene.com' in dl_host:
                        #continue
                    #self.results.append({'source': dl_host, 'quality': 'SD', 'url': dl_link, 'direct': False})
            #except:
                #log_utils.log('sources', 1)
                #pass
            try:
                results = client_utils.parseDOM(html, 'a', ret='data-em')
                for result in results:
                    b64 = base64.b64decode(result)
                    link = client_utils.parseDOM(b64, 'iframe', ret='src')[0]
                    if any(i in link for i in ['youtube.com', 'short.ink']):
                        continue
                    if 'sharer.pw' in link:
                        try:
                            result_html = client.scrapePage(link).text
                            src = re.findall(r"Player\.src\({src: '(.+?)',", result_html)[0]
                            item = scrape_sources.make_direct_item(hostDict, src, host='sharer.pw', info=None, referer=link, prep=True)
                            if item:
                                if not scrape_sources.check_host_limit(item['source'], self.results):
                                    self.results.append(item)
                        except:
                            #log_utils.log('sources', 1)
                            pass
                    else:
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
        if any(x in url for x in self.domains):
            try:
                link = client.request(url, timeout='10', output='geturl')
                return link
            except:
                pass
        else:
            return url



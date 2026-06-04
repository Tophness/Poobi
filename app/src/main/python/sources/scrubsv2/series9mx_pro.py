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
        self.domains = ['series9mx.pro']
        self.base_link = 'https://series9mx.pro'
        self.search_link = '/?s=%s'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            aliases = eval(data['aliases'])
            title = data['title']
            year = data['year']
            search_title = cleantitle.get_plus(title)
            search_link = self.base_link + self.search_link % search_title
            r = client.request(search_link)
            r = client_utils.parseDOM(r, 'div', attrs={'class': 'ml-item'})
            r = [(client_utils.parseDOM(i, 'a', ret='href'), client_utils.parseDOM(i, 'a', ret='oldtitle')) for i in r]
            r = [(i[0][0], i[1][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0]
            r = [(i[0], re.findall(r'(.+?) [(](\d{4})[)]', i[1])) for i in r]
            r = [(i[0], i[1][0]) for i in r if len(i[1]) > 0]
            r_link = [i[0] for i in r if cleantitle.match_alias(i[1][0], aliases) and cleantitle.match_year(i[1][1], year)][0]
            r_html = client.request(r_link)
            results = re.compile(r'''onclick=\"getmovie\(['"](.+?)['"], ['"](.+?)['"], ['"](.+?)['"]\)\;\">''', re.DOTALL).findall(r_html)
            for r_id, r_slink, r_type in results:
                try:
                    v_url = self.base_link + '/get-link.php?id=%s&type=%s&link=%s' % (r_id, r_type, r_slink)
                    v_html = client.request(v_url).replace('\\', '')
                    v_link = client_utils.parseDOM(v_html, 'iframe', ret='src')[0]
                    for source in scrape_sources.process(hostDict, v_link):
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



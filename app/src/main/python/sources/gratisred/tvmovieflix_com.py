# -*- coding: utf-8 -*-

import base64
import json
import re

from six import ensure_text
from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources
#from resources.lib.modules import log_utils

# Named embed keys in the current fmovie-core `Servers` JSON object.
_SERVER_EMBED_KEYS = (
    'mp4', 'upcloud', 'premium', 'embedru', 'superembed', 'svetacdn', 'vidsrc', 'openvids',
)


class source:
    def __init__(self):
        self.results = []
        self.domains = ['tvmovieflix.com']
        self.base_link = 'https://tvmovieflix.com'
        self.search_link = '/?s=%s'
        self.headers = {
            'User-Agent': client.UserAgent,
            'Referer': self.base_link,
        }


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def _collect_links(self, page):
        links = []
        try:
            match = re.search(r'var\s+Servers\s*=\s*(\{.*?\});', page, re.I | re.S)
            if match:
                data = json.loads(match.group(1))
                for key in _SERVER_EMBED_KEYS:
                    val = data.get(key)
                    if isinstance(val, str) and (val.startswith('http') or val.startswith('//')):
                        links.append(val)
        except Exception:
            #log_utils.log('_collect_links: servers json', 1)
            pass
        try:
            for onclick in client_utils.parseDOM(page, 'div', attrs={'id': 'manual'}, ret='onclick'):
                links += re.findall(r'''loadEmbed\(['"]([^'"]+)['"]\)''', onclick, re.I)
        except Exception:
            pass
        return links


    def _append_link(self, hostDict, link, referer):
        try:
            link = client_utils.replaceHTMLCodes(link)
            if link.startswith('//'):
                link = 'https:' + link
            if not link.startswith('http'):
                return
            if any(x in link for x in self.domains):
                if '/embed.php?' in link:
                    page = client.scrapePage(link, referer=referer, headers=self.headers, timeout='15')
                    vhtml = (getattr(page, 'text', '') or '') if page is not None else ''
                    found = re.compile(client_utils.regex_pattern6).findall(vhtml)
                    if not found:
                        return
                    vlink = base64.b64decode(found[0].replace('\\/', '/'))
                    vlink = ensure_text(vlink, errors='ignore')
                    item = scrape_sources.make_direct_item(hostDict, vlink, host='Direct', info=None, referer=referer, prep=True)
                    if item and not scrape_sources.check_host_limit(item['source'], self.results):
                        self.results.append(item)
                    return
                if '/player.php?' in link or '/flix.php?' in link:
                    link = client.request(link, headers=self.headers, referer=referer, timeout='15', output='geturl')
                    if not link or any(x in link for x in self.domains):
                        return
            for src in scrape_sources.process(hostDict, link):
                if scrape_sources.check_host_limit(src['source'], self.results):
                    continue
                self.results.append(src)
        except Exception:
            #log_utils.log('_append_link', 1)
            pass


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            aliases = eval(data['aliases'])
            title = data['title']
            year = data['year']
            search_url = self.base_link + self.search_link % cleantitle.get_plus(title)
            search_page = client.scrapePage(search_url, headers=self.headers, timeout='15')
            html = (getattr(search_page, 'text', '') or '') if search_page is not None else ''
            if not html:
                return self.results
            r = client_utils.parseDOM(html, 'div', attrs={'id': r'post-.*?'})
            r = [(client_utils.parseDOM(i, 'a', attrs={'class': 'title'}, ret='href'), client_utils.parseDOM(i, 'a', attrs={'class': 'title'}), re.findall(r'<span>(\d{4})</span>', i)) for i in r]
            r = [(i[0][0], i[1][0], i[2][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0 and len(i[2]) > 0]
            matches = [i[0] for i in r if cleantitle.match_alias(i[1], aliases) and cleantitle.match_year(i[2], year)]
            if not matches:
                return self.results
            movie_url = matches[0]
            page_obj = client.scrapePage(movie_url, headers=self.headers, timeout='15')
            page = (getattr(page_obj, 'text', '') or '') if page_obj is not None else ''
            if not page:
                return self.results
            for link in self._collect_links(page):
                self._append_link(hostDict, link, referer=movie_url)
            return self.results
        except Exception:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url

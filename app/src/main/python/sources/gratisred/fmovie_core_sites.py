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

_HOSTS = (
    ('MoviesFree', 'https://moviesfree.cv'),
    ('Ghostplayer', 'https://ghostplayer.store'),
)

_SKIP_SLUGS = {'category', 'tag', 'page', 'about', 'contact', 'feed', 'dmca',
               'disclaimer', 'privacy-policy', 'top-imdb'}

_SERVER_KEYS = (
    'superembed', 'filemoon', 'voe', 'mixdrop', 'streamtape', 'streamwish',
    'doodstream', 'mixdrp', 'upstream', 'mp4', 'upcloud', 'openvids', 'svetacdn',
)

_SERVERS_B64 = re.compile(
    r"""(?:window\.)?Servers\s*=\s*JSON\.parse\(\s*atob\(\s*['"]([A-Za-z0-9+/=]+)['"]"""
)
_SERVERS_LITERAL = re.compile(r"""(?:window\.)?Servers\s*=\s*(\{[^;]+?\});""")
_SERVERS_DATAURL = re.compile(
    r'id=["\']servers-js[^"\']*["\']\s+src=["\']data:text/javascript;base64,'
    r'([A-Za-z0-9+/=]+)["\']'
)


class source:
    def __init__(self):
        self.results = []
        self.base_link = 'https://moviesfree.cv'
        self.domains = ['moviesfree.cv', 'ghostplayer.store']
        self.headers = {'User-Agent': client.UserAgent}


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'title': title}
        return urlencode(url)


    def _find_slug(self, base, title):
        try:
            search_url = '%s/?s=%s' % (base, cleantitle.get_plus(title))
            page = client.scrapePage(search_url, headers=dict(self.headers, Referer=base + '/'), timeout='15')
            html = (getattr(page, 'text', '') or '') if page is not None else ''
            if not html:
                return None
            host = base.split('://', 1)[-1].rstrip('/')
            pat = re.compile(r'href="(https?://' + re.escape(host) + r'/[a-z0-9\-]+)/?"')
            matches = pat.findall(html)
            needle = title.lower().split()[0] if title else ''
            for slug_url in matches:
                slug = slug_url.rsplit('/', 1)[-1]
                if any(x in slug.lower() for x in _SKIP_SLUGS):
                    continue
                if needle and needle in slug.lower():
                    return slug_url
            for slug_url in matches:
                slug = slug_url.rsplit('/', 1)[-1]
                if not any(x in slug.lower() for x in _SKIP_SLUGS):
                    return slug_url
        except Exception:
            #log_utils.log('_find_slug', 1)
            pass
        return None


    def _extract_servers(self, html):
        try:
            m = _SERVERS_DATAURL.search(html)
            if m:
                js = base64.b64decode(m.group(1)).decode('utf-8', 'replace')
                obj_m = re.search(r'Servers\s*=\s*(\{.+?\})\s*;?\s*$', js, re.S)
                if obj_m:
                    return json.loads(obj_m.group(1))
            m = _SERVERS_B64.search(html)
            if m:
                raw = base64.b64decode(m.group(1)).decode('utf-8', 'replace')
                return json.loads(raw)
            m = _SERVERS_LITERAL.search(html)
            if m:
                return json.loads(m.group(1))
        except Exception:
            #log_utils.log('_extract_servers', 1)
            pass
        return None


    def _collect_links(self, servers):
        links = []
        if not isinstance(servers, dict):
            return links
        for key in _SERVER_KEYS:
            val = servers.get(key)
            if isinstance(val, str) and (val.startswith('http') or val.startswith('//')):
                links.append(val)
        return links


    def _append_link(self, hostDict, link, referer, label):
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
                    info = '%s | Direct' % label
                    item = scrape_sources.make_direct_item(hostDict, vlink, host='Direct', info=info, referer=referer, prep=True)
                    if item.get('url') and not scrape_sources.check_host_limit(item['source'], self.results):
                        self.results.append(item)
                    return
                if '/player.php?' in link or '/flix.php?' in link:
                    link = client.request(link, headers=self.headers, referer=referer, timeout='15', output='geturl')
                    if not link or any(x in link for x in self.domains):
                        return
            for src in scrape_sources.process(hostDict, link):
                if scrape_sources.check_host_limit(src['source'], self.results):
                    continue
                if label and src.get('info'):
                    src['info'] = '%s | %s' % (label, src['info'])
                elif label:
                    src['info'] = label
                self.results.append(src)
        except Exception:
            #log_utils.log('_append_link', 1)
            pass


    def _scrape_host(self, hostDict, pretty, base, title):
        slug_url = self._find_slug(base, title)
        if not slug_url:
            return
        page_obj = client.scrapePage(slug_url + '/', headers=dict(self.headers, Referer=base + '/'), timeout='15')
        page = (getattr(page_obj, 'text', '') or '') if page_obj is not None else ''
        if not page:
            return
        servers = self._extract_servers(page)
        if not servers:
            return
        for link in self._collect_links(servers):
            self._append_link(hostDict, link, referer=slug_url, label=pretty)


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            title = data.get('title')
            if not title:
                return self.results
            for pretty, base in _HOSTS:
                self._scrape_host(hostDict, pretty, base, title)
            return self.results
        except Exception:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url

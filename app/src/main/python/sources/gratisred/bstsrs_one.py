# -*- coding: utf-8 -*-

import json
import re

from six.moves.urllib_parse import parse_qs, quote_plus, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import decryption
from resources.lib.modules import scrape_sources


class source:
    def __init__(self):
        self.results = []
        self.domains = ['bstsrs.in']
        self.base_link = 'https://bstsrs.in'
        self.search_link = '/ajax/search.php?q=%s'
        self.episode_link = '/show/%s-s%02de%02d/season/%s/episode/%s'
        self.headers = {
            'User-Agent': client.UserAgent,
            'Referer': self.base_link,
            'Accept': 'application/json, text/javascript, */*; q=0.01',
            'Accept-Language': 'it-IT,it;q=0.9,en;q=0.8',
            'X-Requested-With': 'XMLHttpRequest'
        }


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        if tvshowtitle == 'House':
            tvshowtitle = 'House M.D.'
        url = {
            'imdb': imdb,
            'tvshowtitle': tvshowtitle,
            'localtvshowtitle': localtvshowtitle or '',
            'aliases': aliases,
            'year': year,
        }
        return urlencode(url)


    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url:
            return
        url = parse_qs(url)
        url = dict([(i, url[i][0]) if url[i] else (i, '') for i in url])
        url['title'], url['premiered'], url['season'], url['episode'] = title, premiered, season, episode
        return urlencode(url)


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            title = data.get('tvshowtitle', '')
            local = data.get('localtvshowtitle', '')
            year = data.get('year', '')
            season = data.get('season', '')
            episode = data.get('episode', '')

            if not (title and season and episode):
                return self.results

            urls = self._candidate_urls(title, local, year, season, episode)
            html = ''
            for ep_url in urls:
                try:
                    resp = client.scrapePage(ep_url, headers=self.headers, timeout='10')
                    html = resp.text if resp else ''
                except Exception as e:
                    print(f"[BSTSRS_DEBUG] Exception scraping {ep_url}: {e}", flush=True)
                    html = ''

                if html and ('embed-selector' in html or 'dbneg(' in html):
                    break
                
                if html and 'Just a moment' in html[:1000]:
                    print("[BSTSRS_DEBUG] Cloudflare challenge hit ('Just a moment...'). Aborting candidate loop.", flush=True)
                    return self.results

            if not html:
                return self.results

            blocks = re.findall(
                r"(?:class=['\"]embed-selector['\"][^>]*>.*?)?dbneg\(['\"]([^'\"]+)['\"]\).*?domain=([^'\"&<> ]+)",
                html,
                re.DOTALL,
            )
            if not blocks:
                blocks = [(c, '') for c in re.findall(r"dbneg\(['\"]([^'\"]+)['\"]\)", html)]

            for coded, host in blocks:
                try:
                    link = self._dbneg(coded)
                    if not link or not link.startswith('http'):
                        continue
                    items = scrape_sources.process(hostDict, link, host=host or None)
                    if items:
                        for item in items:
                            if scrape_sources.check_host_limit(item['source'], self.results):
                                continue
                            self.results.append(item)
                    else:
                        item = scrape_sources.make_item(hostDict, link, host=host or None, prep=True)
                        if item and not scrape_sources.check_host_limit(item['source'], self.results):
                            self.results.append(item)
                except Exception:
                    continue
            return self.results
        except Exception:
            return self.results


    def _candidate_urls(self, title, local, year, season, episode):
        urls = []
        try:
            sint, eint = int(season), int(episode)
            slugs = []
            
            clean_dot_title = title.replace('.', '').replace('M.D', 'md').replace('m.d', 'md')
            if clean_dot_title != title:
                slugs.append(cleantitle.geturl(clean_dot_title))

            if local:
                slugs.append(cleantitle.geturl(local))

            slugs.append(cleantitle.geturl(title))
            slugs.append(re.sub(r'[^a-z0-9]+', '-', title.lower()).strip('-'))

            if year:
                slugs.append(cleantitle.geturl('%s %s' % (title, year)))

            for slug in slugs:
                if not slug:
                    continue
                urls.append(self.base_link + self.episode_link % (slug, sint, eint, sint, eint))

            for q in (local, clean_dot_title, title):
                if not q:
                    continue
                try:
                    search_url = self.base_link + self.search_link % quote_plus(q)
                    resp = client.scrapePage(
                        search_url,
                        headers=self.headers,
                        timeout='10',
                    )
                    resp_text = resp.text if resp and hasattr(resp, 'text') else (resp.content if resp and hasattr(resp, 'content') else '')
                    items = self._parse_search_json(resp_text)
                    if isinstance(items, list):
                        for it in items:
                            permalink = (it or {}).get('permalink', '')
                            if not permalink:
                                continue
                            urls.append(permalink.rstrip('/') + '/season/%d/episode/%d' % (sint, eint))
                except Exception:
                    continue
        except Exception:
            pass

        seen = set()
        out = []
        for u in urls:
            if u not in seen:
                seen.add(u)
                out.append(u)
        return out


    def _parse_search_json(self, resp):
        if not resp:
            return []
        if isinstance(resp, (list, dict)):
            return resp
        try:
            return json.loads(resp)
        except Exception:
            pass
        try:
            m = re.search(r'<pre[^>]*>(.+?)</pre>', resp, re.DOTALL)
            if m:
                return json.loads(m.group(1))
        except Exception:
            pass
        try:
            m = re.search(r'(\[.*\]|\{.*\})', resp, re.DOTALL)
            if m:
                return json.loads(m.group(1))
        except Exception:
            pass
        return []


    def _dbneg(self, coded):
        try:
            decoded = decryption.decode(coded)
            if decoded.startswith('http'):
                return decoded
        except Exception:
            pass
        try:
            ret = ''
            offset = None
            for sp in coded.split('-'):
                if not sp:
                    continue
                code = int(sp, 16)
                if offset is None:
                    offset = code - ord('h')
                ret += chr(code - offset)
            if ret.startswith('http'):
                return ret
        except Exception:
            pass
        return ''


    def resolve(self, url):
        return url
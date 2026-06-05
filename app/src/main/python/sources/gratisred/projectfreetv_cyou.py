# -*- coding: utf-8 -*-

# projectfreetv.cyou / freeprojecttv.cyou scraper.
#
# Sister/dupe of projectfreetv.lol - identical 3-step page flow:
#   1. /tv-series/<slug>-season-X-episode-Y/  ->  <iframe src="/vembed/{id}/">
#   2. /vembed/{id}/                           ->  <select id="sourceSelect">
#                                                  with <option value="..."> entries
#   3. /external/asset/{source_id}/            ->  <iframe src="..."> for resolveurl
#
# Cloudflare-protected. `client.scrapePage` now uses cfscrape to solve the
# challenge and cache the cf_clearance cookie per host.

import re

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
        self.domains = ['freeprojecttv.cyou', 'projectfreetv.cyou']
        self.base_link = 'https://freeprojecttv.cyou'
        self.movie_link = '/movies/%s-%s/'
        self.tvshow_link = '/tv-series/%s-season-%s-episode-%s/'
        self.notes = 'sister site of projectfreetv_lol and watchseries_cyou.'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'year': year}
        return urlencode(url)


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        url = {'imdb': imdb, 'tvdb': tvdb, 'tvshowtitle': tvshowtitle, 'year': year}
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
            is_show = 'tvshowtitle' in data
            title = data['tvshowtitle'] if is_show else data.get('title', '')
            year = data.get('premiered', '') if is_show else data.get('year', '')
            season = data.get('season', '0')
            episode = data.get('episode', '0')
            slug = cleantitle.geturl(title)
            if is_show:
                result_url = self.base_link + self.tvshow_link % (slug, season, episode)
            else:
                result_url = self.base_link + self.movie_link % (slug, year)

            self.headers = {
                'User-Agent': client.UserAgent,
                'Referer': self.base_link,
            }

            def _fetch_text(target_url, referer=None):
                hdrs = dict(self.headers)
                if referer:
                    hdrs['Referer'] = referer
                p = client.scrapePage(target_url, headers=hdrs, timeout='15')
                try:
                    txt = p.text if p is not None and hasattr(p, 'text') else ''
                except Exception:
                    txt = ''
                if not isinstance(txt, str):
                    try:
                        txt = str(txt or '')
                    except Exception:
                        txt = ''
                return txt

            # Step 1: episode page -> locate the vembed URL
            ep_html = _fetch_text(result_url, referer=self.base_link)
            if not ep_html:
                return self.results

            vembed_urls = []
            try:
                for src in DOM(ep_html, 'iframe', ret='src'):
                    if src:
                        vembed_urls.append(self.base_link + src if not src.startswith('http') else src)
            except Exception:
                pass
            if not vembed_urls:
                try:
                    for src in re.findall(r'[\"\'](https?://[^\"\'<>]*?/vembed/[^\"\'<>]+)[\"\'<>]', ep_html, re.I):
                        vembed_urls.append(src)
                except Exception:
                    pass
            if not vembed_urls:
                return self.results

            # Step 2: vembed page -> pull source IDs from <select id="sourceSelect">
            all_source_ids = []
            for vurl in vembed_urls:
                vhtml = _fetch_text(vurl, referer=result_url)
                if not vhtml:
                    continue
                sel = DOM(vhtml, 'select', attrs={'id': 'sourceSelect'})
                ids = []
                if sel:
                    ids = DOM(sel[0], 'option', ret='value')
                else:
                    ids = re.findall(r'<option[^>]*value=[\"\']([^\"\'<>]+)[\"\'<>]', vhtml, re.I)
                for vid in ids:
                    vid = (vid or '').strip()
                    if vid:
                        all_source_ids.append(vid)

            seen = set()
            source_ids = [s for s in all_source_ids if not (s in seen or seen.add(s))]
            if not source_ids:
                return self.results

            # Step 3: for each source_id, fetch /external/asset/<id>/ and
            # pull the iframe src -> hand to resolveurl via scrape_sources.process
            for source_id in source_ids:
                try:
                    asset_url = self.base_link + '/external/asset/%s/' % source_id
                    asset_html = _fetch_text(asset_url, referer=vembed_urls[0] if vembed_urls else self.base_link)
                    if not asset_html:
                        continue
                    for link in DOM(asset_html, 'iframe', ret='src'):
                        try:
                            if not link:
                                continue
                            link = self.base_link + link if not link.startswith('http') else link
                            for src in scrape_sources.process(hostDict, link):
                                if scrape_sources.check_host_limit(src['source'], self.results):
                                    continue
                                self.results.append(src)
                        except Exception:
                            continue
                except Exception:
                    continue

            return self.results
        except Exception:
            return self.results


    def resolve(self, url):
        if any(d in url for d in self.domains):
            try:
                hdrs = {'User-Agent': client.UserAgent, 'Referer': self.base_link}
                page = client.scrapePage(url, headers=hdrs, timeout='15')
                html = (getattr(page, 'text', '') or '') if page is not None else ''
                try:
                    iframe = DOM(html, 'iframe', ret='src')
                    if iframe:
                        return iframe[0]
                except Exception:
                    pass
                try:
                    m = re.search(r'"(/open/site/[^"]+)"', html, re.I | re.S)
                    if m:
                        target = self.base_link + m.group(1)
                        page2 = client.scrapePage(target, headers=hdrs, timeout='15')
                        return getattr(page2, 'url', target) or target
                except Exception:
                    pass
            except Exception:
                #log_utils.log('resolve', 1)
                pass
        return url

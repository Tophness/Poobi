# -*- coding: utf-8 -*-

# projectfreetv.lol scraper.
#
# Sister/dupe of freeprojecttv.cyou and watchseries.cyou - identical
# page template (same /tv-series/<slug>-season-<n>-episode-<m>/ pattern,
# same `<tr class="ext_link_HOST">` / `/open/link/<id>/` markup).
# Cloudflare-protected; requires FlareSolverr URL in addon settings.
# `client.scrapePage` retries CF challenges through FlareSolverr and
# caches the cf_clearance cookie per-host so subsequent requests on
# the same domain bypass CF with plain `requests`.

import re

from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources
#from resources.lib.modules import log_utils

DOM = client_utils.parseDOM

LOG_PATH = "E:\\Downloads\\streamscraper\\debug.log"

def log(msg):
    try:
        with open(LOG_PATH, 'a') as f:
            f.write(str(msg) + '\n')
    except Exception:
        pass

class source:
    def __init__(self):
        self.results = []
        self.domains = ['projectfreetv.lol']
        self.base_link = 'https://projectfreetv.lol'
        self.movie_link = '/movies/%s-%s/'
        self.tvshow_link = '/tv-series/%s-season-%s-episode-%s/'
        self.notes = 'sister site of projectfreetv_cyou and watchseries_cyou.'
        self.headers = {
            'User-Agent': client.UserAgent,
            'Referer': self.base_link,
        }


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
            log(url)
            if not url:
                return self.results
            data = parse_qs(url)
            log(data)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            log(data)
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

            # Step 1: episode page -> locate the vembed URL (the only iframe
            # on the episode page is the hdplayer that points to /vembed/<id>/)
            ep_html = _fetch_text(result_url, referer=self.base_link)
            log(('ep_html_len', len(ep_html)))
            if not ep_html:
                return self.results

            vembed_urls = []
            try:
                for src in DOM(ep_html, 'iframe', ret='src'):
                    if src:
                        vembed_urls.append(self.base_link + src if not src.startswith('http') else src)
            except Exception:
                pass
            log(('vembed_urls', vembed_urls))
            if not vembed_urls:
                try:
                    for src in re.findall(r'[\"\'](https?://[^\"\'<>]*?/vembed/[^\"\'<>]+)[\"\'<>]', ep_html, re.I):
                        vembed_urls.append(src)
                except Exception:
                    pass
                log(('vembed_urls_fallback', vembed_urls))

            if not vembed_urls:
                return self.results

            # Step 2: vembed page -> pull source IDs from <select id="sourceSelect">
            all_source_ids = []
            for vurl in vembed_urls:
                vhtml = _fetch_text(vurl, referer=result_url)
                log(('vembed_html_len', len(vhtml), vurl))
                if not vhtml:
                    continue
                sel = DOM(vhtml, 'select', attrs={'id': 'sourceSelect'})
                log(('sourceSelect_found', bool(sel)))
                ids = []
                if sel:
                    ids = DOM(sel[0], 'option', ret='value')
                else:
                    ids = re.findall(r'<option[^>]*value=[\"\']([^\"\'<>]+)[\"\'<>]', vhtml, re.I)
                log(('option_values', ids))
                for vid in ids:
                    vid = (vid or '').strip()
                    if vid:
                        all_source_ids.append(vid)

            seen = set()
            source_ids = [s for s in all_source_ids if not (s in seen or seen.add(s))]
            log(('source_ids_final', source_ids))

            # Step 3: for each source_id, fetch /external/asset/<id>/ and
            # pull the iframe src -> hand to resolveurl via scrape_sources.process
            for source_id in source_ids:
                try:
                    asset_url = self.base_link + '/external/asset/%s/' % source_id
                    asset_html = _fetch_text(asset_url, referer=vembed_urls[0] if vembed_urls else self.base_link)
                    log(('asset', source_id, 'len', len(asset_html)))
                    if not asset_html:
                        continue
                    iframes = DOM(asset_html, 'iframe', ret='src')
                    log(('asset_iframes', source_id, iframes))
                    for link in iframes:
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

            log(('results_count', len(self.results)))
            return self.results
        except Exception:
            return self.results


    def resolve(self, url):
        if any(d in url for d in self.domains):
            try:
                page = client.scrapePage(url, headers=self.headers, timeout='15')
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
                        page2 = client.scrapePage(target, headers=self.headers, timeout='15')
                        return getattr(page2, 'url', target) or target
                except Exception:
                    pass
            except Exception:
                #log_utils.log('resolve', 1)
                pass
        return url

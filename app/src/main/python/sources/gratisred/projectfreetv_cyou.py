# -*- coding: utf-8 -*-

# freeprojecttv.cyou scraper.
#
# Sister/dupe of watchseries.cyou and projectfreetv.lol - identical page
# template, same /tv-series/<slug>-season-<n>-episode-<m>/ URL pattern,
# same `<tr class="ext_link_HOST">` / `/open/link/<id>/` markup.
# Cloudflare-protected; requires FlareSolverr URL in addon settings.
# `client.scrapePage` retries CF challenges through FlareSolverr and
# caches the cf_clearance cookie per-host so subsequent requests on
# the same domain bypass CF with plain `requests`.

import re

from six.moves.urllib_parse import parse_qs, urlencode, urljoin

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources
#from resources.lib.modules import log_utils

DOM = client_utils.parseDOM


class source:
    def __init__(self):
        self.results = []
        self.domains = ['freeprojecttv.cyou']
        self.base_link = 'https://freeprojecttv.cyou'
        self.movie_link = '/movies/%s-%s/'
        self.tvshow_link = '/tv-series/%s-season-%s-episode-%s/'
        self.notes = 'sister site of watchseries_cyou and projectfreetv_lol.'
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
            page = client.scrapePage(result_url, headers=self.headers, timeout='15')
            html = (getattr(page, 'text', '') or '') if page is not None else ''
            if not html:
                return self.results

            try:
                for link in DOM(html, 'iframe', ret='src'):
                    try:
                        if not link or any(bad in link.lower() for bad in ['sharethis', 'about:blank', 'googletag', 'facebook']):
                            continue
                        link = self.base_link + link if not link.startswith('http') else link
                        for src in scrape_sources.process(hostDict, link):
                            if scrape_sources.check_host_limit(src['source'], self.results):
                                continue
                            self.results.append(src)
                    except Exception:
                        continue
            except Exception:
                pass

            try:
                ext_rows = DOM(html, 'tr', attrs={'class': r'ext_link.+?'})
                parsed_candidates = []

                for row in ext_rows:
                    try:
                        hrefs = DOM(row, 'a', ret='href')
                        titles = DOM(row, 'a', ret='title')
                        if not hrefs:
                            continue
                        link = hrefs[0]
                        host = titles[0] if titles else "Unknown"

                        td_cells = [re.sub(r'<[^>]+>', '', td).strip() for td in DOM(row, 'td')]
                        quality = td_cells[1] if len(td_cells) > 1 else "HD"
                        uploader = td_cells[2] if len(td_cells) > 2 else ""
                        age = td_cells[3] if len(td_cells) > 3 else ""

                        m_id = re.search(r'/open/link/(\d+)', link)
                        link_id = int(m_id.group(1)) if m_id else 0

                        display_title = f"{host} ({uploader} • {age})" if uploader and age else host

                        parsed_candidates.append({
                            'link': self.base_link + link if not link.startswith('http') else link,
                            'host': host,
                            'link_id': link_id,
                            'quality': quality,
                            'display_title': display_title
                        })
                    except Exception:
                        continue

                parsed_candidates.sort(key=lambda x: x['link_id'], reverse=True)

                host_seen = {}
                for cand in parsed_candidates:
                    h_key = cand['host'].lower()
                    if host_seen.get(h_key, 0) >= 2:
                        continue
                    host_seen[h_key] = host_seen.get(h_key, 0) + 1

                    item = scrape_sources.make_item(hostDict, cand['link'], host=cand['host'], info=cand['display_title'], prep=True)
                    if item:
                        item['title'] = cand['display_title']
                        if cand['quality']:
                            item['quality'] = cand['quality']
                        self.results.append(item)
            except Exception:
                pass

            def _get_link_id(item):
                m = re.search(r'/open/link/(\d+)', item.get('url', ''))
                return int(m.group(1)) if m else 0

            self.results.sort(key=_get_link_id, reverse=True)
            return self.results
        except Exception:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        if not any(d in url for d in self.domains) and '/open/link/' not in url:
            return url
        try:
            page = client.scrapePage(url, headers=self.headers, timeout='15')
            html = (getattr(page, 'text', '') or '') if page is not None else ''
            if not html:
                return url

            try:
                for link in DOM(html, 'iframe', ret='src'):
                    if link and not any(bad in link.lower() for bad in ['sharethis', 'about:blank', 'googletag', 'facebook', 'beacon']):
                        return link if link.startswith('http') else urljoin(self.base_link, link)
            except Exception:
                pass

            meta_id = None
            m_id = re.search(r'data-metaid=["\'](\d+)["\']', html)
            if m_id:
                meta_id = m_id.group(1)
            else:
                m_url_id = re.search(r'/open/link/(\d+)', url)
                if m_url_id:
                    meta_id = m_url_id.group(1)

            token_match = re.search(r"['\"](/open/(?!link/)[\w-]+/?)['\"]", html)
            target_url = None

            if token_match:
                token = token_match.group(1).strip("'\"")
                if not token.startswith('/'):
                    token = '/' + token
                if not token.endswith('/'):
                    token += '/'

                if meta_id and meta_id not in token:
                    target_path = token + meta_id + '/'
                else:
                    target_path = token
                target_url = urljoin(self.base_link, target_path)
            elif meta_id:
                target_url = urljoin(self.base_link, f"/open/site/{meta_id}/")

            if target_url:
                step_headers = {
                    'User-Agent': client.UserAgent,
                    'Referer': url,
                    'Origin': self.base_link
                }
                page2 = client.scrapePage(target_url, headers=step_headers, timeout='15')
                if page2 is not None:
                    final_url = getattr(page2, 'url', None) or target_url
                    if final_url and not any(d in final_url for d in self.domains):
                        return final_url

                    html2 = getattr(page2, 'text', '') or ''
                    for ifr in DOM(html2, 'iframe', ret='src'):
                        if ifr and not any(bad in ifr.lower() for bad in ['sharethis', 'about:blank', 'googletag', 'facebook', 'beacon']):
                            return ifr if ifr.startswith('http') else urljoin(self.base_link, ifr)

                    m_loc = re.search(r'''(?:location(?:\.href)?|window\.open)\s*=\s*['"]([^'"]+)''', html2)
                    if m_loc and m_loc.group(1).startswith('http'):
                        return m_loc.group(1)

                    for href in DOM(html2, 'a', ret='href'):
                        if href.startswith('http') and not any(d in href for d in self.domains):
                            return href
        except Exception:
            #log_utils.log('resolve', 1)
            pass
        return url
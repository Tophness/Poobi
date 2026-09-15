# -*- coding: utf-8 -*-

import re
from six.moves.urllib_parse import parse_qs, urlencode, urljoin

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources

DOM = client_utils.parseDOM


class source:
    def __init__(self):
        self.results = []
        self.domains = [
            'cinespot.org', 'vidnest.fun', 'vidnest.io', 'vidsrc.mov', 'vidsrc.to',
            'vidlink.org', 'vidlink.pro', '2embed.cc', 'multiembed.mov',
            '111movies.com', 'vidsrc.fyi', 'vidrock.net', 'vidking.net', 'vidfast.pro',
            'vidup.to', 'videasy.net', 'peachify.top',
            'vidsrc.buzz', 'videm.xyz', 'streamsrcs.2embed.cc', 'movieuniverse.skin'
        ]
        self.base_link = 'https://cinespot.org'
        self.movie_link = '/watch/movie/%s'
        self.tv_link_pattern1 = '/watch/tv/%s/%s/%s'
        self.tv_link_pattern2 = '/watch/tv/%s?season=%s&episode=%s'
        self.headers = {
            'User-Agent': client.UserAgent,
            'Referer': self.base_link,
        }

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        print("[CINESPOT DEBUG] movie() called with tmdb: %s, imdb: %s, title: %s" % (tmdb, imdb, title))
        url = {'imdb': imdb, 'tmdb': tmdb, 'title': title, 'year': year}
        return urlencode(url)

    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        print("[CINESPOT DEBUG] tvshow() called with tmdb: %s, tvshowtitle: %s" % (tmdb, tvshowtitle))
        url = {'imdb': imdb, 'tmdb': tmdb, 'tvdb': tvdb, 'tvshowtitle': tvshowtitle, 'year': year}
        return urlencode(url)

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        print("[CINESPOT DEBUG] episode() called for season: %s, episode: %s" % (season, episode))
        if not url:
            return
        url = parse_qs(url)
        url = dict([(i, url[i][0]) if url[i] else (i, '') for i in url])
        url['title'], url['premiered'], url['season'], url['episode'] = title, premiered, season, episode
        return urlencode(url)

    def sources(self, url, hostDict):
        try:
            print("[CINESPOT DEBUG] sources() started.")
            if not url:
                print("[CINESPOT DEBUG] URL parameter is empty.")
                return self.results

            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            is_show = 'tvshowtitle' in data
            tmdb = data.get('tmdb', '')

            print("[CINESPOT DEBUG] Parsed data - is_show: %s, tmdb: %s" % (is_show, tmdb))

            if not tmdb or tmdb == '0':
                print("[CINESPOT DEBUG] Missing valid TMDb ID. Cannot scrape cinespot.to.")
                return self.results

            custom_host_dict = list(hostDict) if hostDict else []
            for d in self.domains:
                if d not in custom_host_dict:
                    custom_host_dict.append(d)
            candidate_urls = []
            if is_show:
                season = data.get('season', '1')
                episode = data.get('episode', '1')
                candidate_urls.append(self.base_link + self.tv_link_pattern1 % (tmdb, season, episode))
                candidate_urls.append(self.base_link + self.tv_link_pattern2 % (tmdb, season, episode))
            else:
                candidate_urls.append(self.base_link + self.movie_link % tmdb)

            html = ''
            result_url = ''
            for cand_url in candidate_urls:
                print("[CINESPOT DEBUG] Requesting URL: %s" % cand_url)
                try:
                    page = client.scrapePage(cand_url, headers=self.headers, timeout='10')
                    html = (getattr(page, 'text', '') or '') if page is not None else ''
                    print("[CINESPOT DEBUG] Response length for %s: %d bytes" % (cand_url, len(html)))
                    if 'Just a moment' in html[:1000] or 'cloudflare' in html[:1000].lower():
                        print("[CINESPOT DEBUG] WARNING: Cloudflare challenge detected on %s!" % cand_url)
                except Exception as e:
                    print("[CINESPOT DEBUG] Error requesting %s: %s" % (cand_url, str(e)))
                    html = ''
                if html and ('playerFrame' in html or 'server-btn' in html or 'watch-grid' in html):
                    result_url = cand_url
                    print("[CINESPOT DEBUG] Found valid player page at: %s" % result_url)
                    break

            if not html:
                print("[CINESPOT DEBUG] Failed to retrieve valid HTML from candidate URLs.")
                return self.results

            server_paths = re.findall(r'href="(\?server=[^"]+)"', html)
            if not server_paths:
                server_paths = DOM(html, 'a', attrs={'class': r'.*?server-btn.*?'}, ret='href')

            print("[CINESPOT DEBUG] Found server query paths: %s" % server_paths)

            target_urls = [result_url]
            for path in server_paths:
                full_path = urljoin(result_url, path)
                if full_path not in target_urls:
                    target_urls.append(full_path)

            print("[CINESPOT DEBUG] Total pages to check for iframes: %d" % len(target_urls))

            for target in target_urls:
                try:
                    if target == result_url:
                        server_html = html
                    else:
                        print("[CINESPOT DEBUG] Fetching server URL: %s" % target)
                        page = client.scrapePage(target, headers=self.headers, timeout='10')
                        server_html = (getattr(page, 'text', '') or '') if page is not None else ''

                    if not server_html:
                        continue

                    iframes = DOM(server_html, 'iframe', ret='src')
                    if not iframes:
                        iframes = re.findall(r'<iframe\s+[^>]*src="([^"]+)"', server_html, re.I)

                    print("[CINESPOT DEBUG] Extracted iframes on %s: %s" % (target, iframes))

                    for src in iframes:
                        if not src:
                            continue
                        if src.startswith('//'):
                            src = 'https:' + src
                        elif not src.startswith('http'):
                            src = urljoin(self.base_link, src)

                        print("[CINESPOT DEBUG] Sending iframe to scrape_sources.process: %s" % src)
                        items = scrape_sources.process(custom_host_dict, src)
                        print("[CINESPOT DEBUG] scrape_sources returned %d items for %s" % (len(items), src))
                        
                        if items:
                            for item in items:
                                if scrape_sources.check_host_limit(item['source'], self.results):
                                    continue
                                self.results.append(item)
                except Exception as e:
                    print("[CINESPOT DEBUG] Exception processing target %s: %s" % (target, str(e)))
                    continue

            print("[CINESPOT DEBUG] Finished! Total sources gathered: %d" % len(self.results))
            return self.results
        except Exception as e:
            print("[CINESPOT DEBUG] Critical error in sources(): %s" % str(e))
            return self.results

    def resolve(self, url):
        print("[CINESPOT DEBUG] resolve() called for: %s" % url)
        return url
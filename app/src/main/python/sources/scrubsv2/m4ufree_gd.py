# -*- coding: utf-8 -*-

import re
import json
from six.moves.urllib_parse import parse_qs, urlencode, quote_plus

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources

class source:
    def __init__(self):
        self.results = []
        self.domains = ['m4ufree.gd']
        self.base_link = 'https://m4ufree.gd'
        self.suggest_link = '/api/suggest?q=%s'

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'tmdb': tmdb, 'title': title, 'year': year}
        return urlencode(url)

    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        url = {'imdb': imdb, 'tmdb': tmdb, 'tvshowtitle': tvshowtitle, 'year': year}
        return urlencode(url)

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url: return
        data = parse_qs(url)
        data = {k: v[0] for k, v in data.items()}
        data.update({'title': title, 'season': season, 'episode': episode})
        return urlencode(data)

    def sources(self, url, hostDict):
        try:
            if not url: return self.results
            data = parse_qs(url)
            data = {k: v[0] for k, v in data.items()}

            title = data.get('tvshowtitle') or data.get('title')
            year = str(data.get('year', ''))
            season = data.get('season')
            episode = data.get('episode')
            is_tv = bool(data.get('tvshowtitle'))

            headers = {
                'User-Agent': client.UserAgent,
                'Referer': f"{self.base_link}/",
                'X-Requested-With': 'XMLHttpRequest',
                'Accept': 'application/json, text/javascript, */*; q=0.01'
            }

            query_url = self.base_link + (self.suggest_link % quote_plus(title))
            response = client.scrapePage(query_url, headers=headers)
            suggestions = []
            if response:
                try:
                    suggestions = response.json()
                except Exception:
                    suggestions = []

            if not suggestions and ':' in title:
                short_title = title.split(':', 1)[0].strip()
                retry_resp = client.scrapePage(self.base_link + (self.suggest_link % quote_plus(short_title)), headers=headers)
                if retry_resp:
                    try:
                        suggestions = retry_resp.json()
                    except Exception:
                        pass

            if not suggestions or not isinstance(suggestions, list):
                return self.results

            target_clean = cleantitle.get(title)
            matched_item = None

            for item in suggestions:
                item_title = item.get('title', '')
                item_year = str(item.get('year', ''))
                item_type = item.get('type', '')

                if is_tv and item_type and item_type not in ['tv', 'series']:
                    continue
                if not is_tv and item_type and item_type != 'movie':
                    continue

                item_clean = cleantitle.get(item_title)
                if (target_clean in item_clean or item_clean in target_clean):
                    matched_item = item
                    break

            if not matched_item:
                for item in suggestions:
                    item_type = item.get('type', '')
                    if is_tv and item_type in ['tv', 'series']:
                        matched_item = item
                        break
                    elif not is_tv and item_type == 'movie':
                        matched_item = item
                        break

            if not matched_item:
                return self.results

            watch_path = matched_item.get('url', '')
            if not watch_path:
                return self.results

            watch_url = watch_path if watch_path.startswith("http") else f"{self.base_link}{watch_path}"
            if is_tv and season and episode:
                sep = '&' if '?' in watch_url else '?'
                watch_url = f"{watch_url}{sep}s={season}&e={episode}"

            watch_resp = client.scrapePage(watch_url, headers={'User-Agent': client.UserAgent, 'Referer': f"{self.base_link}/"})
            if not watch_resp or not watch_resp.text:
                return self.results

            opt_data = re.findall(r'window\.__OPT\s*=\s*(\[.+?\]);', watch_resp.text)
            links = []

            if opt_data:
                try:
                    links = json.loads(opt_data[0])
                except Exception:
                    links = []

            if not links:
                links = re.findall(r'<iframe[^>]+src="([^"]+)"', watch_resp.text)

            for link in links:
                clean_link = link.replace('\\/', '/')
                if clean_link.startswith('//'):
                    clean_link = 'https:' + clean_link
                elif clean_link.startswith('/'):
                    clean_link = self.base_link + clean_link

                for source_item in scrape_sources.process(hostDict, clean_link):
                    if not scrape_sources.check_host_limit(source_item['source'], self.results):
                        self.results.append(source_item)

            return self.results
        except Exception:
            return self.results

    def resolve(self, url):
        return url
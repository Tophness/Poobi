# -*- coding: utf-8 -*-

import re
import json
from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources

class source:
    def __init__(self):
        self.results = []
        self.domains = ['m4ufree.gd']
        self.base_link = 'https://m4ufree.gd'
        self.search_link = '/browser?keyword=%s'

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
            year = data.get('year')

            search_url = self.base_link + self.search_link % cleantitle.get_plus(title)
            html = client.scrapePage(search_url).text

            # Use regex to find links and titles in the search results
            matches = re.findall(r'<a class="bf-card" href="(.+?)" title="(.+?)"', html)
            match_url = None

            # Search for the correct movie/show card
            for href, item_title in matches:
                # Basic check: title and year match
                if cleantitle.get(title) in cleantitle.get(item_title) and year in item_title:
                    match_url = self.base_link + href
                    break

            if not match_url:
                # If no direct match, try a looser match or just take the first one if it's a search for a specific title
                if matches:
                    match_url = self.base_link + matches[0][0]
                else:
                    return self.results

            # Get the watch page and extract links from the javascript variable window.__OPT
            watch_html = client.scrapePage(match_url).text
            opt_data = re.findall(r'window\.__OPT\s*=\s*(\[.+?\]);', watch_html)

            if opt_data:
                links = json.loads(opt_data[0])
                for link in links:
                    for source in scrape_sources.process(hostDict, link):
                        if not scrape_sources.check_host_limit(source['source'], self.results):
                            self.results.append(source)

            return self.results
        except Exception:
            return self.results

    def resolve(self, url):
        return url

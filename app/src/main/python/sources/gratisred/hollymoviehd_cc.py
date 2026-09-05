# -*- coding: utf-8 -*-

import re
import simplejson as json
from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources


class source:
    def __init__(self):
        self.results = []
        self.domains = ['hollymoviehd.cc', 'hollymoviehd-official.com']
        self.base_link = 'https://hollymoviehd.cc'
        self.search_link = '/?s=%s'
        self.ajax_link = '/wp-admin/admin-ajax.php'
        self.headers = client.dnt_headers

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return urlencode({'imdb': imdb, 'title': title, 'aliases': aliases, 'year': year})

    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        return urlencode({'imdb': imdb, 'tvshowtitle': tvshowtitle, 'aliases': aliases, 'year': year})

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url:
            return
        data = parse_qs(url)
        data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
        data['title'], data['premiered'], data['season'], data['episode'] = title, premiered, season, episode
        return urlencode(data)

    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            title = data.get('tvshowtitle') or data.get('title')
            season = data.get('season', '0')
            episode = data.get('episode', '0')
            year = data.get('premiered', '').split('-')[0] if 'tvshowtitle' in data else data.get('year')

            # 1. Determine page URL
            if 'tvshowtitle' in data:
                page_url = f"{self.base_link}/episode/{cleantitle.get_dash(title)}-season-{season}-episode-{episode}/"
            else:
                page_url = f"{self.base_link}/{cleantitle.get_dash(title)}-{year}/"

            self.headers.update({'Referer': self.base_link})
            response = client.scrapePage(page_url, headers=self.headers)
            if not response or response.status_code != 200:
                return self.results

            html = response.text

            # 2. Extract streamkey & wpnonce
            streamkey_match = re.search(r'data-streamkey=["\']([^"\']+)["\']', html)
            nonce_match = re.search(r'data-wpnonce=["\']([^"\']+)["\']', html) or re.search(r'data-nonce=["\']([^"\']+)["\']', html)

            if not streamkey_match or not nonce_match:
                return self.results

            streamkey = streamkey_match.group(1)
            wpnonce = nonce_match.group(1)

            # 3. Request admin-ajax.php for embed URLs
            post_link = self.base_link + self.ajax_link
            ajax_headers = {
                'User-Agent': client.UserAgent,
                'Referer': page_url,
                'Origin': self.base_link,
                'X-Requested-With': 'XMLHttpRequest'
            }
            payload = {
                'action': 'ajax_getlinkstream',
                'streamkey': streamkey,
                'nonce': wpnonce,
                'imdbid': data.get('imdb', '')
            }

            ajax_resp = client.scrapePage(post_link, post=payload, headers=ajax_headers)
            if not ajax_resp or ajax_resp.status_code != 200:
                return self.results

            ajax_data = json.loads(ajax_resp.text)
            servers_iframe = ajax_data.get('servers_iframe', {})

            embed_links = []
            for name, embed_url in servers_iframe.items():
                if embed_url:
                    embed_links.append(embed_url.replace('&amp;', '&'))

            # Also ensure host is in hostDict
            for link in embed_links:
                for src in scrape_sources.process(hostDict, link):
                    self.results.append(src)

            return self.results
        except Exception:
            return self.results

    def resolve(self, url):
        return url
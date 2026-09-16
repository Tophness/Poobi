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

            if 'tvshowtitle' in data:
                page_url = f"{self.base_link}/episode/{cleantitle.get_dash(title)}-season-{season}-episode-{episode}/"
            else:
                page_url = f"{self.base_link}/{cleantitle.get_dash(title)}-{year}/"

            self.headers.update({'Referer': self.base_link})
            response = client.scrapePage(page_url, headers=self.headers)
            if not response or response.status_code != 200:
                return self.results

            html = response.text

            streamkey_match = re.search(r'data-streamkey=["\']([^"\']+)["\']', html)
            nonce_match = re.search(r'data-wpnonce=["\']([^"\']+)["\']', html) or re.search(r'data-nonce=["\']([^"\']+)["\']', html)

            if not streamkey_match or not nonce_match:
                return self.results

            streamkey = streamkey_match.group(1)
            wpnonce = nonce_match.group(1)

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

            goodstream_variants = []
            other_embeds = []

            for s_name, embed_url in servers_iframe.items():
                if not embed_url:
                    continue
                clean_embed = embed_url.replace('&amp;', '&')

                if 'goodstream.cc' in clean_embed:
                    name_lower = s_name.lower()
                    if '1080' in name_lower:
                        qual = '1080p'
                        rank = 1
                    elif '720' in name_lower or 'streamsvr' in name_lower:
                        qual = '720p'
                        rank = 2
                    elif '4k' in name_lower or '2160' in name_lower:
                        qual = '4K'
                        rank = 0
                    else:
                        qual = '720p'
                        rank = 2

                    goodstream_variants.append({
                        'name': qual,
                        'url': clean_embed,
                        'rank': rank
                    })
                else:
                    other_embeds.append(clean_embed)

            if goodstream_variants:
                goodstream_variants.sort(key=lambda x: x['rank'])
                primary = goodstream_variants[0]
                alt_urls = [v['url'] for v in goodstream_variants]
                alt_names = [v['name'] for v in goodstream_variants]

                self.results.append({
                    'source': 'goodstream.cc',
                    'title': 'GoodStream',
                    'quality': primary['name'],
                    'url': primary['url'],
                    'direct': False,
                    'is_video': True,
                    'info': f"{primary['name']} | GoodStream",
                    'alternative_urls': alt_urls if len(alt_urls) > 1 else [],
                    'alternative_names': alt_names if len(alt_names) > 1 else []
                })

            for link in other_embeds:
                for src in scrape_sources.process(hostDict, link):
                    self.results.append(src)

            return self.results
        except Exception:
            return self.results

    def resolve(self, url):
        return url
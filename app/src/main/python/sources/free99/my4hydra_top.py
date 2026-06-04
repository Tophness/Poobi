# -*- coding: utf-8 -*-
import re
import random
import simplejson as json

from six.moves.urllib_parse import parse_qs, urlencode, urlparse
from six import ensure_str, ensure_text

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources
from resources.lib.modules import log_utils

DOM = client_utils.parseDOM

class source:
    def __init__(self):
        self.results = []
        self.domains = ['mp4hydra.top', 'mp4hydra.org', 'mp4hydra.info']
        self.base_link = 'https://mp4hydra.top'
        self.search_link = '/s?q={}'
        self.notes = 'direct links, still testing'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'tmdb': tmdb, 'title': title, 'localtitle': localtitle, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        url = {'imdb': imdb, 'tmdb': tmdb, 'tvshowtitle': tvshowtitle, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url:
            return
        url = parse_qs(url)
        url = dict([(i, url[i][0]) if url[i] else (i, '') for i in url])
        url['title'], url['premiered'], url['season'], url['episode'] = title, premiered, season, episode
        url = urlencode(url)
        return url


    def sources(self, url, hostDict):
        try:
            data = parse_qs(url)
            data = {k: v[0] if v else '' for k, v in data.items()}
            aliases = eval(data.get('aliases', '[]'))
            title = data.get('tvshowtitle', data.get('title', ''))
            season, episode = data.get('season', '0'), data.get('episode', '0')
            year = data.get('premiered', data.get('year', '')).split('-')[0] if data.get('premiered') else ''
            check_term = f"{title}" if 'tvshowtitle' in data else title
            check_title = cleantitle.get(check_term)
            clean_title = cleantitle.get_utf8(title)
            search_url = self.base_link + self.search_link.format(clean_title)
            results = client.request(search_url, output='json')

            if not results:
                return

            if 'success' in results:
                if results['success'] == False:
                    return

            r_list = []
            for item in results:  # push the (YYYY) into a new field
                list_title = item['title']
                list_type = item['type']
                list_link = item['link']

                match = re.search(r'\((\d{4})\)', list_title)  # (YYYY)
                list_year = match.group(1) if match else ''

                cleaned_title = re.sub(r'\s*\(\d{4}\)', '', list_title)

                r_list.append({
                    "title": cleaned_title,
                    "year": list_year,
                    "type": list_type.lower(),
                    "link": list_link,
                })

            if 'tvshowtitle' in data:  # TV shows
                result_url = [i for i in r_list if check_title == cleantitle.get(i['title']) and i['type'] == 'tv']
                if not result_url:
                    return
                result_url = result_url[0]['link'] if result_url else None
                check_episode = f's{int(season):02}e{int(episode):02}'
                result_url = f'{result_url}-{check_episode}'

            else:  # MOVIES
                result_url = [i for i in r_list if check_title == cleantitle.get(i['title']) and i['type'] == 'movie']
                if not result_url:
                    return

                result_url = result_url[0]['link'] if result_url else None

            if not result_url.startswith(('http', '//')):
                result_url = f'{self.base_link}{result_url}'

            results2 = client.request(result_url)
            results2 = client_utils.clean_html(results2)
            results3 = DOM(results2, 'script')   # GOTTA get the javascript dictionary
            results4 = [i for i in results3 if all(key in i for key in ["v':", "s':", "t':", "i':"])]
            
            if not results4:
                return

            results4 = results4[0] if results4 else None
            match = re.search(r'var opts = (.*?);', results4)
            
            if match:
                txt_match = match.group(1)
                txt_match = re.sub(r"'", '"', txt_match)  # repl the quotes
                txt_match = re.sub(r'\b(true|false)\b', r'"\1"', txt_match, flags=re.IGNORECASE)  # add quotes
                txt_match = re.sub(r',\s*}', '}', txt_match)  # remove trailing comma
                orig_dict = json.loads(txt_match)
            else:
                return

            post_link = orig_dict['i']

            if not post_link.startswith(('http')):
                post_link = f'{self.base_link}{post_link}'
            
            hex_chars = "0123456789abcdef"
            random_hex = "".join(random.choice(hex_chars) for _ in range(32))  # boundary and the JSON data.
            boundary = f'----geckoformboundary{random_hex}'
            data_list = json.dumps([orig_dict['v']])    # the data
            content_type = f'multipart/form-data; boundary={boundary}'
            headers = {            # simple headers, otherwise it fails
                'Referer': result_url,
                'Content-Type': content_type,
            }
            payload = (    # payload byte string
                f'--{boundary}\r\n'
                f'Content-Disposition: form-data; name="z"\r\n'
                f'\r\n'
                f'{data_list}\r\n'
                f'--{boundary}--\r\n'
            )
            payload = payload.encode('utf-8')
            results5 = client.request(post_link, post=payload, headers=headers, output='json')

            if 'tvshowtitle' in data:  # TV shows
                matches = [i for i in results5["playlist"] if i["title"].lower() == check_episode]  # title is the episode number
            else:  # Movies
                matches = [i for i in results5["playlist"] if
                           check_title in cleantitle.get(i["title"]) and
                           data['year'] in i["title"].lower()]

            if not matches:
                return

            link = matches[0]['src']
            links = []

            for key, host in results5['servers'].items():
                if 'auto' not in key.lower() or 'http' in host.lower():
                    host = host.rstrip('/') + '/'  # ensure 1 trailing slash
                    links.append(host + link)
        
            for link in links:
                parts = urlparse(link)
                search = set(hostDict)  # include domain in hostDict, so we can use make_item
                
                if parts.hostname not in search:
                    hostDict.append(parts.hostname)
                    search.add(parts.hostname)
                    
                if link:
                    item = scrape_sources.make_direct_item(hostDict, link, host='Direct', info=None, referer=None, prep=True)
                    if item:
                        self.results.append(item)

            return self.results
            
        except:
            return self.results



    def resolve(self, url):
        return url



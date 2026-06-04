# -*- coding: utf-8 -*-

import re

from six.moves.urllib_parse import parse_qs, urlencode, urljoin

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
#from resources.lib.modules import log_utils


class source:
    def __init__(self):
        self.results = []
        self.domains = ['streamlord.com']
        self.base_link = 'http://www.streamlord.com'
        self.login_link = '/login2.php' # Login Page before search.
        self.search_link = '/searchapi2.php' # Login Needed to search.
        self.username = 'search_scrubv2' #'search_scrub' # My username for us to use plus the old one.
        self.password = 'search_password' # My password for us to use.
        # To register a new account the email doesnt matter. email@blow.me would likely work.


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        url = {'imdb': imdb, 'tvshowtitle': tvshowtitle, 'aliases': aliases, 'year': year}
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
            if not url:
                return self.results
            if (self.username != '' and self.password != ''):
                login = urljoin(self.base_link, self.login_link)
                post = urlencode({'username': self.username, 'password': self.password, 'submit': 'Login'})
                cookie = client.request(login, post=post, output='cookie', close=False)
                r = client.request(login, post=post, cookie=cookie, output='extended')
                headers = r[1]
            else:
                headers = {}
            if not str(url).startswith('http'):
                data = parse_qs(url)
                data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
                aliases = eval(data['aliases'])
                title = data['tvshowtitle'] if 'tvshowtitle' in data else data['title']
                year = data['year']
                query = urljoin(self.base_link, self.search_link)
                post = urlencode({'searchapi2': title})
                r = client.request(query, post=post, headers=headers)
                if 'tvshowtitle' in data:
                    r = re.findall(r'(watch-tvshow-.+?-\d+\.html)', r)
                    r = [(i, re.findall(r'watch-tvshow-(.+?)-\d+\.html', i)) for i in r]
                else:
                    r = re.findall(r'(watch-movie-.+?-\d+\.html)', r)
                    r = [(i, re.findall(r'watch-movie-(.+?)-\d+\.html', i)) for i in r]
                r = [(i[0], i[1][0]) for i in r if len(i[1]) > 0]
                r = [i for i in r if cleantitle.match_alias(i[1], aliases)]
                r = [i[0] for i in r][0]
                u = urljoin(self.base_link, r)
                for i in range(3):
                    r = client.request(u, headers=headers)
                    if not 'failed' in r:
                        break
                if 'season' in data and 'episode' in data:
                    r = re.findall(r'(episode-.+?-.+?\d+.+?\d+-\d+.html)', r)
                    r = [i for i in r if '-s%02de%02d-' % (int(data['season']), int(data['episode'])) in i.lower()][0]
                    r = urljoin(self.base_link, r)
                    r = client.request(r, headers=headers)
            else:
                r = urljoin(self.base_link, url)
                r = client.request(r, post=post, headers=headers)
            #<title> Watch Star Trek: Strange New Worlds (2022-) - Streaming </title> #Year check option.
            try:
                f = re.findall(r'''["']sources['"]\s*:\s*\[(.*?)\]''', r)[0]
                f = re.findall(r'''['"]*file['"]*\s*:\s*([^\(]+)''', f)[0]
                u = re.findall(r'function\s+%s[^{]+{\s*([^}]+)' % f, r)[0]
                u = re.findall(r'\[([^\]]+)[^+]+\+\s*([^.]+).*?getElementById\("([^"]+)', u)[0]
                a = re.findall(r'var\s+%s\s*=\s*\[([^\]]+)' % u[1], r)[0]
                b = client_utils.parseDOM(r, 'span', attrs={'id': u[2]})[0]
                url = u[0] + a + b
                url = url.replace('"', '').replace(',', '').replace('\/', '/')
                url += '|' + urlencode(headers)
            except:
                try:
                    url =  r = client_utils.unpacked(r)
                    url = url.replace('"', '')
                except:
                    url = re.findall(r'sources[\'"]\s*:\s*\[.*?file[\'"]\s*:\s*(\w+)\(\).*function\s+\1\(\)\s*\{\s*return\([\'"]([^\'"]+)',r,re.DOTALL)[0][1]
            quality = '720p' if '-movie-' in r else 'SD'
            self.results.append({'source': 'Direct', 'quality': quality, 'url': url, 'direct': True})
            return self.results
        except:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url



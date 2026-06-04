# -*- coding: utf-8 -*-

import re
import requests
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
        self.domains = ['levidia.ch']
        self.base_link = 'https://www.levidia.ch'
        self.search_link = '/search.php?q=%s'
        self.php_entrypoint = '/get.php'
        self.sess = requests.Session()
        self.UAx = 'Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36'
        self.notes = 'sim/dupe site to goojara.to & supernova.to i think.'


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


    # written by mbebe on GitHub: https://github.com/mbebe/blomqvist
    def wootly(self, wurl):
        response = None
        UAx = self.UAx
        headers2 = {
            'Host': 'www.wootly.ch',
            'Upgrade-Insecure-Requests': '1',
            'User-Agent': UAx,
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9',
            'Accept-Language': 'en-US,en;q=0.9,pl;q=0.8',
        }
        response = self.sess.get(wurl, headers=headers2, verify=None)
        response_content = (response.content).decode(encoding='utf-8', errors='strict')
        nturl = re.findall(r'iframe src="([^"]+)', response_content)[0]
        headers2.update({'Referer': wurl})
        response = self.sess.get(nturl, headers=headers2, verify=None)
        vv = response.content
        headers = {
            'Host': 'www.wootly.ch',
            'Upgrade-Insecure-Requests': '1',
            'User-Agent': UAx,
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9',
            'Sec-Fetch-Site': 'same-origin',
            'Sec-Fetch-Mode': 'navigate',
            'Sec-Fetch-User': '?1',
            'Sec-Fetch-Dest': 'document',
            'Referer': nturl,
            'Accept-Language': 'en-US,en;q=0.9,pl;q=0.8',
        }
        response = self.sess.get('https://www.wootly.ch/prime', headers=headers, verify=None)
        response_content = (response.content).decode(encoding='utf-8', errors='strict')
        cn = re.findall(r'cn="([^"]+)', response_content, re.DOTALL)[0]
        cv = re.findall(r'cv="([^"]+)', response_content, re.DOTALL)[0]
        gg = self.sess.cookies.get_dict()
        gg[cn] = cv
        data = {'qdf': '1'}
        response = self.sess.post(nturl, headers=headers, cookies=gg, data=data, verify=None)
        response_content = (response.content).decode(encoding='utf-8', errors='strict')
        tk = re.findall(r'tk="([^"]+)', response_content, re.DOTALL)[0]
        vd = re.findall(r'vd="([^"]+)', response_content, re.DOTALL)[0]
        url2 = 'https://wootly.ch/grabd' + "?t=" + tk + "&id=" + vd
        wootlycookies = self.sess.cookies
        response = self.sess.get(url2, headers=headers, cookies=wootlycookies, verify=None)
        response_content = (response.content).decode(encoding='utf-8', errors='strict')
        link = response_content
        link = 'https:' + link if link.startswith('//') else link
        link += '|User-Agent=' + UAx + '&Referer=' + link
        return link


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            aliases = eval(data['aliases'])
            title = data['tvshowtitle'] if 'tvshowtitle' in data else data['title']
            season, episode = (data['season'], data['episode']) if 'tvshowtitle' in data else ('0', '0')
            year = data['year']
            check_term = '%s - Season %s' % (title, season) if 'tvshowtitle' in data else title
            check_title = cleantitle.get(check_term)
            search_url = self.base_link + self.search_link % cleantitle.get_utf8(title)
            if not self.base_link.startswith('https://www.'):
                self.base_link = 'https://www.' + self.base_link.split('://')[1]
            headers = {
                'User-Agent': self.UAx,
                'Accept': '*/*',
                'Accept-Language': 'en-US,en;q=0.5',
                'Accept-Encoding': 'gzip, deflate',
                'Referer': self.base_link,
                'Content-type': 'application/x-www-form-urlencoded',
                'Origin': self.base_link,
                'Connection': 'keep-alive',
                'Sec-Fetch-Dest': 'empty',
                'Sec-Fetch-Mode': 'cors',
                'Sec-Fetch-Site': 'same-origin',
                'TE': 'trailers',
            }
            init = self.sess.get(self.base_link, headers=headers, verify=None)
            cookie_dict = self.sess.cookies.get_dict()
            response = self.sess.post(search_url, headers=headers, cookies=cookie_dict, verify=None)
            r = response.content.decode(encoding='utf-8', errors='strict')
            r = DOM(r, 'div', attrs={'class': 'mainlink'})
            r = list(zip(DOM(r, 'a', ret='href'), DOM(r, 'a')))
            r = [(url, client_utils.remove_tags(html)) for url, html in r]
            # get the year from the html and add it to the tuple
            r = [(url, re.sub(r'\(\d{4}\)', '', html), re.findall(r'\((\d{4})\)', html)[0]) for url, html in r]
            # clean up
            r = [(url, html.strip(), year) for url, html, year in r]
            r = [(i[0], i[1], i[2]) for i in r if len(i[0]) > 0 and len(i[1]) > 0 and len(i[2]) > 0]
            # verify year for tv shows
            # then match aliases and year
            try:
                result_url = next(i[0] for i in r if cleantitle.match_alias(i[1], aliases) and cleantitle.match_year(i[2], data.get('year')))
            except StopIteration:
                try:
                    year2 = data.get('year')
                    result_url = next(i[0] for i in r if cleantitle.match_alias(i[1], aliases) and cleantitle.match_year(i[2], year2))
                except StopIteration:
                    result_url = next(i[0] for i in r if cleantitle.match_alias(i[1], aliases))
            if not result_url:
                return
            result_url = f"{self.base_link}/{result_url}" if not result_url.startswith(self.base_link) else result_url
            if 'tvshowtitle' in data:
                result_url = f"{result_url}&s={season}"
            response = self.sess.get(result_url, headers=headers, cookies=cookie_dict, verify=None)
            r = response.content.decode(encoding='utf-8', errors='strict')
            headers.update({'Referer': result_url})
            if 'tvshowtitle' in data:
                # find the episode and season
                seaepi = f"s{season}e{episode}"
                r10 = DOM(r, 'li', attrs={'class': 'mlist links'})
                r11 = list(zip(DOM(r10, 'a', ret='href'), DOM(r10, 'a')))
                result_url = next(i[0] for i in r11 if seaepi in i[0].lower())
                if not result_url:
                    return
                result_url = f"{self.base_link}/{result_url}" if not result_url.startswith(self.base_link) else result_url
                response = self.sess.get(result_url, headers=headers, cookies=cookie_dict, verify=None)
                r = response.content.decode(encoding='utf-8', errors='strict')
                response_content = r
                r12 = DOM(r, 'li', attrs={'class': 'xxx0'})
                r13 = DOM(r12, 'span', attrs={'class': 'kiri xxx1 xx12'})
                r13 = [(client_utils.remove_tags(site)) for site in r13]
                r14 = DOM(r12, 'a', attrs={'class': 'xxx xflv'}, ret='href')
            else:  # movies
                response_content = r
                r12 = DOM(r, 'li', attrs={'class': 'xxx0'})
                r13 = DOM(r12, 'span', attrs={'class': 'kiri xxx1'})
                r13 = [(client_utils.remove_tags(site)) for site in r13]
                r14 = DOM(r12, 'a', attrs={'class': 'xxx xflv'}, ret='href')
            result_links = list(zip(r13, r14))
            cook2 = self.sess.cookies.get_dict()
            ck, ck2 = re.findall(r"""_3chk\(['"](.+?)['"],['"](.+?)['"]""", response_content, re.DOTALL)[0]
            cook2[ck] = ck2   # add the _3chk property to the cookie dictionary
            headers = {
                'User-Agent': self.UAx,
                'Host': 'www.levidia.ch',
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/png,image/svg+xml,*/*;q=0.8',
                'Accept-Language': 'en-US,en;q=0.5',
                'Accept-Encoding': 'gzip, deflate, br, zstd',
                'Referer': self.base_link,
                'Origin': self.base_link,
                'DNT': '1',
                'Sec-GPC': '1',
                'Upgrade-Insecure-Requests': '1',
                'Sec-Fetch-User': '?1',
                'Connection': 'keep-alive',
                'Sec-Fetch-Dest': 'document',
                'Sec-Fetch-Mode': 'navigate',
                'Sec-Fetch-Site': 'none',
                'Priority': 'u=0, i',
            }
            vurls = []
            for host, link in result_links:
                response = None
                try:
                    response = self.sess.get(link, headers=headers, cookies=cook2, verify=None)
                    link = response.url
                    if 'wootly' in link.lower():
                        try:
                            self.sess.close()
                            link = self.wootly(link)
                            vurls.append((link, host))
                        except:
                            pass
                    else:
                        vurls.append((link, host))
                except:
                    pass
            for link, host in vurls:
                try:
                    if 'wootly' in host.lower():
                        source = {'source': 'wootly.ch', 'quality': 'SD', 'info': '', 'url': link, 'direct': False}
                        self.results.append(source)
                    else:
                        if link.count('//') > 1:
                            link = re.sub(r'(?<!:)//', '/', link)
                        for source in scrape_sources.process(hostDict, link):
                            self.results.append(source)
                except:
                    pass
            return self.results
        except:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url



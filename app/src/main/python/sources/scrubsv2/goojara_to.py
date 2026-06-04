
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
        self.domains = ['goojara.to', 'supernova.to']
        self.base_link = 'https://goojara.to'
        self.search_link = '/?s=%s'
        self.php_entrypoint = '/xhrr.php'
        self.sess = requests.Session()
        self.notes = 'Pulled in defs from mbebe to resolve goojara links'


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
    # License: https://github.com/mbebe/blomqvist/blob/master/LICENSE
    def createCook(self, a, e, cook):
        b = a[-4:]
        c = a[7:10] + b
        d = a[-2:]
        e = e.split()
        f = e[int(d[0])].lower()
        g = e[int(d[1])]
        h = '_' + f[int(b[0])]
        i = g[int(c[0])]
        h += ''.join(f[int(char)] for char in b[1:])
        i += ''.join(g[int(char)] for char in c[1:])
        nt = self.amu(i)
        cook[h] = nt.upper()
        return cook

    # written by mbebe on GitHub: https://github.com/mbebe/blomqvist
    # License: https://github.com/mbebe/blomqvist/blob/master/LICENSE
    def amu(self, zz):
        import ctypes
        import math
        zz += chr(128)
        a = zz

        g = [1518500249, 1859775393, 2400959708, 3395469782]
        b = [1732584193, 4023233417, 2562383102, 271733878, 3285377520]

        l = math.ceil((float(len(a)) / 4 + 2) / 16)
        m = {}

        for h in range(int(l)):
            m = {h: [''] * 16 for _ in range(16)}

            d = 0
            for d in range(16):
                try:
                    a1 = ord(a[64 * h + 4 * d]) << 24
                except:
                    a1 = 0
                try:
                    a2 = ord(a[64 * h + 4 * d + 1]) << 16
                except:
                    a2 = 0

                try:
                    a4 = ord(a[64 * h + 4 * d + 3])
                except:
                    a4 = 0
                try:
                    a3 = ord(a[64 * h + 4 * d + 2]) << 8
                except:
                    a3 = 0

                m[h][d] = a1 | a2 | a3 | a4

        m[l - 1][14] = 8 * (len(a) - 1) / math.pow(2, 32)
        m[l - 1][14] = math.floor(m[l - 1][14])
        m[l - 1][15] = 8 * (len(a) - 1) & 4294967295
        d = []

        def qq2(aa, e, f, gg):
            if aa == 0:
                return ctypes.c_int(e & f ^ ~e & gg).value
            elif aa == 1:
                return ctypes.c_int(e ^ f ^ gg).value
            elif aa == 2:
                return ctypes.c_int(e & f ^ e & gg ^ f & gg).value
            elif aa == 3:
                return ctypes.c_int(e ^ f ^ gg).value

        def unsigned32(signed):
            return signed % 0x100000000

        def zero_fill_right_shift(val, nn):
            return (val >> nn) if val >= 0 else ((val + 0x100000000) >> nn)

        def qq(ab, eb):
            af = unsigned32(ab) >> 32 - eb
            return ctypes.c_int(ab << eb | zero_fill_right_shift(ab, 32 - eb)).value

        for ff in range(80):
            d.append('')
        for h in range(int(l)):

            for c in range(16):
                d[c] = int(m[h][c])

            for c in range(16, 80):
                b1 = int(d[c - 3])
                b2 = int(d[c - 8])
                b3 = int(d[c - 14])
                b4 = int(d[c - 16])
                d[c] = qq(b1 ^ b2 ^ b3 ^ b4, 1)

            n = b[0]
            p = b[1]
            q = b[2]
            r = b[3]
            u = b[4]
            for c in range(80):
                t = int(math.floor(c / 20))
                t = ctypes.c_int(zero_fill_right_shift((qq(n, 5) + qq2(t, p, q, r) + u + g[t] + d[c]), 0)).value
                u = r
                r = q
                q = ctypes.c_int(zero_fill_right_shift(qq(p, 30), 0)).value
                p = n
                n = t
            b[0] = unsigned32(b[0] + n) >> 0
            b[1] = unsigned32(b[1] + p) >> 0
            b[2] = unsigned32(b[2] + q) >> 0
            b[3] = unsigned32(b[3] + r) >> 0
            b[4] = unsigned32(b[4] + u) >> 0

        for g in range(len(b)):
            z = (hex(b[g]).lstrip('0x').rstrip("L"))
            b[g] = ("00000000" + z)[-8:]

        return (','.join(b)).replace(',', '')

    # written by mbebe on GitHub: https://github.com/mbebe/blomqvist
    # License: https://github.com/mbebe/blomqvist/blob/master/LICENSE
    def wootly(self, wurl):
        UAx = 'Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36'

        headers2 = {
            'Host': 'www.wootly.ch',
            'Upgrade-Insecure-Requests': '1',
            'User-Agent': UAx,
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9',
            'Accept-Language': 'en-US,en;q=0.9,pl;q=0.8',
        }
        response = self.sess.get(wurl, headers=headers2, verify=None)
        response_content = response.content.decode(encoding='utf-8', errors='strict')

        nturl = re.findall(r'iframe src="([^"]+)', response_content)[0]
        headers2.update({'Referer': wurl})
        # response = self.sess.get(nturl, headers=headers2, verify=None)
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
        response_content = response.content.decode(encoding='utf-8', errors='strict')

        cn = re.findall(r'cn="([^"]+)', response_content, re.DOTALL)[0]
        cv = re.findall(r'cv="([^"]+)', response_content, re.DOTALL)[0]
        gg = self.sess.cookies.get_dict()
        gg[cn] = cv
        data = {'qdf': '1'}
        response = self.sess.post(nturl, headers=headers, cookies=gg, data=data, verify=None)
        response_content = response.content.decode(encoding='utf-8', errors='strict')

        tk = re.findall(r'tk="([^"]+)', response_content, re.DOTALL)[0]
        vd = re.findall(r'vd="([^"]+)', response_content, re.DOTALL)[0]
        url2 = 'https://wootly.ch/grabd' + "?t=" + tk + "&id=" + vd
        wootlycookies = self.sess.cookies
        response = self.sess.get(url2, headers=headers, cookies=wootlycookies, verify=None)
        response_content = response.content.decode(encoding='utf-8', errors='strict')

        link = response_content
        link = 'https:' + link if link.startswith('//') else link
        link += '|User-Agent='+UAx+'&Referer='+link

        return link

    # written by mbebe on GitHub: https://github.com/mbebe/blomqvist
    # License: https://github.com/mbebe/blomqvist/blob/master/LICENSE
    def GetPlayLink(self, url, headers, lastkuk):
        response = self.sess.get(url.replace('supernova.to', 'goojara.to'), headers=headers, cookies=lastkuk, verify=None)
        link = response.url
        if 'wootly' in link.lower():
            try:
                self.sess.close()
                link = self.wootly(link)
            except:
                return link

        return link

    # written by mbebe on GitHub: https://github.com/mbebe/blomqvist
    # License: https://github.com/mbebe/blomqvist/blob/master/LICENSE
    def GetCook(self, ref):
        UAx = 'Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36'
        ref = ref.replace('www.supernova.to', 'www.goojara.to')
        headers = {
            'upgrade-insecure-requests': '1',
            'user-agent': UAx,
            'accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9',
            'sec-fetch-site': 'none',
            'sec-fetch-mode': 'navigate',
            'sec-fetch-user': '?1',
            'sec-fetch-dest': 'document',
            'accept-language': 'en-US,en;q=0.9',
        }

        response = self.sess.get(ref, headers=headers, verify=None)
        response_content = response.content.decode(encoding='utf-8', errors='strict')

        dtinsshd = re.findall(r'shd" data-ins="(.+?)"', response_content, re.DOTALL)[0]
        gg = self.sess.cookies.get_dict()
        ck, ck2 = re.findall(r"""_3chk\(['"](.+?)['"],['"](.+?)['"]""", response_content, re.DOTALL)[0]
        gg[ck] = ck2
        refe = ref.replace('www.supernova.to', 'www.goojara.to')
        headers = {
            'Host': 'www.goojara.to',
            'user-agent': UAx,
            'content-type': 'application/x-www-form-urlencoded',
            'accept': '*/*',
            'origin': 'https://www.goojara.to',
            'sec-fetch-site': 'same-origin',
            'sec-fetch-mode': 'cors',
            'sec-fetch-dest': 'empty',
            'referer': refe,
            'accept-language': 'en-US,en;q=0.9',
        }

        params = (
            ('p', '2'),
        )
        data = {"act": "1"}
        response = self.sess.post(refe, headers=headers, params=params, cookies=gg, data=data, verify=None)
        response_content = response.content.decode(encoding='utf-8', errors='strict')
        last_cook = self.createCook(dtinsshd, response_content, gg)

        return headers, last_cook

    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            aliases = eval(data['aliases'])
            title = data['tvshowtitle'] if 'tvshowtitle' in data else data['title']
            season, episode = (data['season'], data['episode']) if 'tvshowtitle' in data else ('0', '0')
            year = data['premiered'].split('-')[0] if 'tvshowtitle' in data else data['year']
            check_term = '%s - Season %s' % (title, season) if 'tvshowtitle' in data else title
            
            if not self.base_link.startswith('https://www.'):
                self.base_link = 'https://www.' + self.base_link.split('://')[1]
            headers = {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; rv:109.0) Gecko/20100101 Firefox/115.0',
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

            postdata = {'q': title.lower()}
            url = self.base_link + self.php_entrypoint
            self.cookie = client.request(self.base_link, output='cookie', timeout='30')
            cookie_dict = {cookie.split('=')[0]: cookie.split('=')[1] for cookie in self.cookie.split('; ')}
            rPost = self.sess.post(url, headers=headers, data=postdata, cookies=cookie_dict)
            r = (rPost.content).decode(encoding='utf-8', errors='strict')
            r = DOM(r, 'ul')
            r = list(zip(DOM(r, 'a', ret='href'), DOM(r, 'div')))
            r = [(url, client_utils.remove_tags(html)) for url, html in r]
            r = [(url, re.sub(r'\(\d{4}\)', '', html), re.findall(r'\((\d{4})\)', html)[0]) for url, html in r]
            r = [(i[0], i[1], i[2]) for i in r if len(i[0]) > 0 and len(i[1]) > 0 and len(i[2]) > 0]
            r = [(i[0], i[1].strip(), i[2]) for i in r if len(i[0]) > 0 and len(i[1]) > 0 and len(i[2]) > 0]

            # goojara tv show year are incorrect, sometimes.
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

            result_url = result_url.replace('ww1.', 'www.') if 'ww1.' in result_url else result_url
            r = client.scrapePage(result_url).text

            if 'tvshowtitle' in data:  # get the season and episode
                data_id = DOM(r, 'div', attrs={'id': 'seon'}, ret='data-id')[0]
                postdata = {'s': season, 't': data_id}
                rPost = self.sess.post(url, headers=headers, data=postdata, cookies=cookie_dict)
                r = (rPost.content).decode(encoding='utf-8', errors='strict')
                r = list(zip(DOM(r, 'a', ret='href'), DOM(r, 'span', attrs={'class': 'sea'})))
                check_episode = episode.zfill(2)  # add leading zero
                found_url = [i[0] for i in r if check_episode == i[1]][0]
                result_url = self.base_link + found_url
                r = client.scrapePage(result_url).text

            r = [i for i in DOM(r, 'div', attrs={'class': 'lxbx'}) if 'Direct Links' in i][0]
            result_links = list(zip(DOM(r, 'a', ret='href'), [re.sub(r'<span>.*?</span>', '', site).strip() for site in DOM(r, 'a')]))

            vurls = []
            result_url = result_url.replace('ww1.','www.')
            headers, last_cookie = self.GetCook(result_url)

            for link, host in result_links:
                try:
                    mylink = self.GetPlayLink(link, headers, last_cookie)
                    vurls.append((mylink, host))
                except:
                    # log_utils.log('FAILED: link =' + repr(link), 1)
                    pass

            for link, host in vurls:
                if 'wootly' in link.lower():
                    source = {
                        'source': 'wootly.ch', 
                        'quality': 'SD',
                        'info': '',
                        'url': link,
                        'direct': False,
                        }
                    self.results.append(source)
                else:
                    for source in scrape_sources.process(hostDict, link):
                        self.results.append(source)

            return self.results
        except:
            return self.results


    def resolve(self, url):
        return url



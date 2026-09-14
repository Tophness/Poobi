# -*- coding: utf-8 -*-
import re
import math
import ctypes
import requests
from urllib.parse import quote_plus

SITE = 'Goojara'
BASE = 'https://ww1.goojara.to'
TIMEOUT = 10
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'

_SLUG_RE = re.compile(
    r'<a\s+href="(/[a-zA-Z0-9]{5,12})"[^>]*>\s*(?:<div[^>]*>\s*)*<strong>([^<]+)</strong>(?:\s*\(?(\d{4})\)?)?',
    re.I | re.S
)
_BCG_RE = re.compile(
    r"""<a\s+class=['"]bcg['"]\s+href=['"]([^'"]+)['"][^>]*>\s*([^<\s]+)\s*(?:<span[^>]*>([^<]+)</span>)?""",
    re.I | re.S
)

class source:
    def __init__(self):
        self.results = []
        self.domains = ['goojara.to', 'ww1.goojara.to', 'supernova.to', 'wootly.ch']

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return f"{title}|{year}" if title else None

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return f"{title}|{year}" if title else None

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url: return None
        return f"{url}|{season}|{episode}"

    def sources(self, url, hostDict):
        self.results = []
        if not url: return []
        parts = url.split('|')
        title = parts[0]
        year = parts[1] if len(parts) > 1 else ''
        season = parts[2] if len(parts) > 2 else None
        episode = parts[3] if len(parts) > 3 else None

        sess, z, x = self._session_and_tokens()
        if not (z and x): return []

        is_tv = season is not None and episode is not None
        slug = self._find_slug(sess, z, x, title, year, is_tv=is_tv)
        if not slug: return []

        content_page = BASE + slug
        try:
            r = sess.get(content_page, timeout=TIMEOUT)
            if not r.ok: return []

            if is_tv:
                ep_pattern = r'<span[^>]*class=["\']sea["\'][^>]*>\s*0*' + str(episode) + r'\s*</span>[\s\S]*?<a\s+href=["\'](/e[a-zA-Z0-9]+)["\']'
                ep_match = re.search(ep_pattern, r.text, re.I)

                if not ep_match:
                    m_tid = re.search(r'data-id=["\']([^"\']+)["\']', r.text)
                    if m_tid:
                        ep_post = {'s': str(season), 't': m_tid.group(1)}
                        ep_headers = {
                            'X-Requested-With': 'XMLHttpRequest',
                            'Content-Type': 'application/x-www-form-urlencoded',
                            'Referer': content_page
                        }
                        r_eps = sess.post(f"{BASE}/xmre.php", data=ep_post, headers=ep_headers, timeout=TIMEOUT)
                        ep_match = re.search(ep_pattern, r_eps.text, re.I)

                if not ep_match: return []

                content_page = BASE + ep_match.group(1)
                r = sess.get(content_page, timeout=TIMEOUT)
                if not r.ok: return []

            try:
                ck, ck2 = re.findall(r"""_3chk\(['"](.+?)['"],['"](.+?)['"]""", r.text)[0]
                sess.cookies.set(ck, ck2, domain=BASE.split('//')[1])
                dtinsshd = re.findall(r'shd" data-ins="(.+?)"', r.text)[0]

                p2_headers = {
                    'X-Requested-With': 'XMLHttpRequest',
                    'Content-Type': 'application/x-www-form-urlencoded',
                    'Referer': content_page,
                    'Origin': BASE
                }
                r_p2 = sess.post(content_page, params={'p': '2'}, data={'act': '1'}, headers=p2_headers, timeout=TIMEOUT)
                auth_cookies = self._create_cook(dtinsshd, r_p2.text, sess.cookies.get_dict())
                for k, v in auth_cookies.items():
                    sess.cookies.set(k, v, domain=BASE.split('//')[1])
            except Exception:
                pass

            headers = {
                'User-Agent': UA,
                'Referer': content_page,
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
            }

            seen_hosts = set()
            for href, host_label, qual in _BCG_RE.findall(r.text):
                href = (href or '').strip()
                host_str = (host_label or '').strip().lower()

                if host_str in ('vidsrc', 'streamplay', 'straemplay'): 
                    continue

                if host_str in seen_hosts: 
                    continue
                seen_hosts.add(host_str)

                target_url = BASE + href if href.startswith('/') else href
                try:
                    resp = sess.get(target_url, headers=headers, allow_redirects=False, timeout=3)
                    final_url = resp.headers.get('Location')
                    if final_url and 'go.php' not in final_url:
                        q = (qual or '720p').upper().strip()
                        if 'DVD' in q: q = 'SD'
                        elif 'HDTV' in q or '720' in q: q = '720p'
                        elif 'HDR' in q or '1080' in q: q = '1080p'
                        
                        self.results.append({
                            'source': host_label.capitalize(),
                            'title': f"Goojara {host_label.capitalize()}",
                            'quality': q,
                            'url': final_url,
                            'direct': False,
                            'info': host_label.capitalize()
                        })
                except Exception:
                    pass

        except Exception:
            pass

        return self.results

    def _session_and_tokens(self):
        s = requests.Session()
        s.headers.update({
            'User-Agent': UA,
            'Accept': 'text/html,application/xhtml+xml,*/*',
            'Referer': BASE + '/',
        })
        try:
            r = s.get(BASE + '/', timeout=TIMEOUT)
            m_z = re.search(r'id=["\']res["\'][^>]*data-ins=["\']([^"\']+)["\']', r.text)
            m_x = re.search(r"""['"]?z=['"]?\s*\+\s*\w+\s*\+\s*['"]&x=([a-f0-9]{6,40})&q=""", r.text)
            z = m_z.group(1) if m_z else None
            x = m_x.group(1) if m_x else None
            return s, z, x
        except:
            return s, None, None

    def _find_slug(self, sess, z, x, title, year, is_tv=False):
        body = f"z={z}&x={x}&q={quote_plus(title)}"
        headers = {
            'X-Requested-With': 'XMLHttpRequest',
            'Content-Type': 'application/x-www-form-urlencoded',
            'Referer': BASE + '/',
        }
        try:
            r = sess.post(f"{BASE}/xmre.php", data=body, headers=headers, timeout=TIMEOUT)
            if not r.ok or not r.text: return None
            tl = title.lower()
            yr = str(year or '').strip()
            candidates = _SLUG_RE.findall(r.text)

            prefix = '/t' if is_tv else '/m'

            for slug, label, cand_year in candidates:
                if not slug.startswith(prefix): continue
                lab_lo = (label or '').lower().strip()
                if tl in lab_lo and yr and cand_year and yr == cand_year:
                    return slug

            for slug, label, cand_year in candidates:
                if not slug.startswith(prefix): continue
                lab_lo = (label or '').lower().strip()
                if tl in lab_lo:
                    return slug

            for slug, label, cand_year in candidates:
                lab_lo = (label or '').lower().strip()
                if tl in lab_lo:
                    return slug
        except: pass
        return None

    def _amu(self, zz):
        zz += chr(128)
        a = zz
        g = [1518500249, 1859775393, 2400959708, 3395469782]
        b = [1732584193, 4023233417, 2562383102, 271733878, 3285377520]
        l = math.ceil((float(len(a)) / 4 + 2) / 16)
        m = {}
        for h in range(int(l)):
            m[h] = [''] * 16
            for d in range(16):
                try: a1 = ord(a[64 * h + 4 * d]) << 24
                except: a1 = 0
                try: a2 = ord(a[64 * h + 4 * d + 1]) << 16
                except: a2 = 0
                try: a4 = ord(a[64 * h + 4 * d + 3])
                except: a4 = 0
                try: a3 = ord(a[64 * h + 4 * d + 2]) << 8
                except: a3 = 0
                m[h][d] = a1 | a2 | a3 | a4

        m[l - 1][14] = math.floor(8 * (len(a) - 1) / math.pow(2, 32))
        m[l - 1][15] = 8 * (len(a) - 1) & 4294967295

        def qq2(aa, e, f, gg):
            if aa == 0: return ctypes.c_int(e & f ^ ~e & gg).value
            elif aa == 1: return ctypes.c_int(e ^ f ^ gg).value
            elif aa == 2: return ctypes.c_int(e & f ^ e & gg ^ f & gg).value
            elif aa == 3: return ctypes.c_int(e ^ f ^ gg).value

        def unsigned32(signed): return signed % 0x100000000
        def zf_rshift(val, nn): return (val >> nn) if val >= 0 else ((val + 0x100000000) >> nn)
        def qq(ab, eb):
            return ctypes.c_int(ab << eb | zf_rshift(unsigned32(ab) >> 32 - eb, 0)).value

        d = [''] * 80
        for h in range(int(l)):
            for c in range(16): d[c] = int(m[h][c])
            for c in range(16, 80):
                d[c] = qq(int(d[c - 3]) ^ int(d[c - 8]) ^ int(d[c - 14]) ^ int(d[c - 16]), 1)
            n, p, q, r, u = b[0], b[1], b[2], b[3], b[4]
            for c in range(80):
                t = int(math.floor(c / 20))
                t = ctypes.c_int(zf_rshift((qq(n, 5) + qq2(t, p, q, r) + u + g[t] + d[c]), 0)).value
                u, r, q, p = r, q, ctypes.c_int(zf_rshift(qq(p, 30), 0)).value, n
                n = t
            b[0] = unsigned32(b[0] + n) >> 0
            b[1] = unsigned32(b[1] + p) >> 0
            b[2] = unsigned32(b[2] + q) >> 0
            b[3] = unsigned32(b[3] + r) >> 0
            b[4] = unsigned32(b[4] + u) >> 0

        return ''.join([("00000000" + hex(b[g]).lstrip('0x').rstrip("L"))[-8:] for g in range(len(b))])

    def _create_cook(self, a, e, cook):
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
        nt = self._amu(i)
        cook[h] = nt.upper()
        return cook

    def resolve(self, url):
        return url
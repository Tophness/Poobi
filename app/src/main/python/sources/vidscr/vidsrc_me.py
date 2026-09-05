# -*- coding: utf-8 -*-
import re
import requests
import base64
from urllib.parse import urlparse, urljoin

class source:
    def __init__(self):
        self.results = []
        self.domains = ['vidsrc.me', 'vidsrc.in', 'vidsrc.to', 'vidsrc.net', 'vidsrc.xyz', 'vidsrcme.ru', 'vidsrc.stream', 'vidsrc.icu']
        self.base_link = 'https://v2.vidsrc.me'
        self.ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36'
        self.cdn_pool = [
            'shadowlandschronicles.com', 'cdn-centaurus.com', 'cdn-fnc.com',
            'shadowlands-cdn.com', 'nestra-cdn.com', 'cloudnestra.com', 'tmstr-cdn.com'
        ]
        self.mirrors = [
            'cloudnestra.com', 'nestra-cdn.com', 'tmstr-cdn.com', 'shadowlandschronicles.com'
        ]

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return imdb if imdb else str(tmdb)

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return imdb if imdb else str(tmdb)

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        return f"{url}/{season}/{episode}"

    def sources(self, url, hostDict):
        if not url: return []
        try:
            content = 'movie' if '/' not in url else 'tv'
            embed_url = f"{self.base_link}/embed/{content}/{url}"
            s = requests.Session()
            s.headers.update({
                'User-Agent': self.ua,
                'Referer': self.base_link,
                'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                'Accept-Encoding': 'gzip, deflate'
            })
            
            r = s.get(embed_url, timeout=15).text
            servers = self._extract_servers(r)

            for srv in servers[:5]:
                if srv['type'] == 'cloudnestra':
                    try:
                        self._resolve_cloudnestra(srv['url'], s, embed_url, srv.get('name', 'Cloudnestra'))
                    except: pass
        except: pass
        return self.results

    def _extract_servers(self, html):
        servers = []
        seen = set()
        def _add(url, name='Cloudnestra'):
            if url and url not in seen:
                seen.add(url)
                servers.append({'type': 'cloudnestra', 'url': url, 'name': name})

        for m in re.finditer(r'src=["\'](//cloudnestra\.com/rcp/[^"\']+)["\']', html):
            _add('https:' + m.group(1))
        for m in re.finditer(r'src=["\'](https?://cloudnestra\.com/rcp/[^"\']+)["\']', html):
            _add(m.group(1))
        for m in re.finditer(r'data-hash=["\']([^"\']+)["\'][^>]*data-i=["\'](\d+)["\']', html):
            _add('https://cloudnestra.com/rcp/' + m.group(1), 'Cloudnestra #%s' % m.group(2))
        for m in re.finditer(r'data-hash=["\']([^"\']+)["\']', html):
            if len(m.group(1)) > 20:
                _add('https://cloudnestra.com/rcp/' + m.group(1))
        return servers

    def _fetch_safe(self, sess, url, referer):
        urls_to_try = [url]
        if 'cloudnestra.com/' in url:
            for m in self.mirrors[1:]:
                urls_to_try.append(url.replace('cloudnestra.com', m, 1))

        for target in urls_to_try:
            try:
                r = sess.get(target, headers={'Referer': referer, 'User-Agent': self.ua, 'Accept-Encoding': 'gzip, deflate'}, timeout=10)
                if r.ok and len(r.text) > 100:
                    return r.text
            except: pass
        return ''

    def _resolve_cloudnestra(self, rcp_url, sess, referer, server_name):
        body = self._fetch_safe(sess, rcp_url, referer)
        if not body: return
        rcp_host = urlparse(rcp_url).netloc

        self._find_and_add_direct(body, rcp_url, server_name)

        next_url = self._find_next_hop(body, rcp_host)
        if next_url:
            body2 = self._fetch_safe(sess, next_url, rcp_url)
            if body2:
                self._find_and_add_direct(body2, next_url, server_name)
                
                pool = self._parse_server_pool(body2)
                raw_files = re.findall(r'file\s*:\s*["\']([^"\']+)["\']', body2)
                for raw_file in raw_files:
                    candidates = self._expand_templates(raw_file, pool)
                    for c in candidates:
                        if '.m3u8' in c or '.mp4' in c:
                            self.results.append({
                                'source': server_name,
                                'quality': '720p',
                                'url': f"{c}|Referer=https://cloudnestra.com/&Origin=https://cloudnestra.com&User-Agent={self.ua}",
                                'direct': True,
                                'info': 'HLS' if '.m3u8' in c else 'MP4'
                            })

    def _find_next_hop(self, body, current_host):
        enc = re.search(r'data-h=["\']([0-9a-fA-F]+)["\']', body)
        seed = re.search(r'data-i=["\']([^"\']+)["\']', body)
        if enc and seed:
            decoded = self._xor_decode(enc.group(1), seed.group(1))
            if decoded: return urljoin(f"https://{current_host}", decoded)

        for pat in (
            r'src\s*[:=]\s*["\']([^"\']*?/prorcp/[^"\']+)["\']',
            r'["\'](/prorcp/[A-Za-z0-9+/=_\-]+)["\']',
            r'["\'](/(?:prosrc|rcp2|source|embed/player|pe|nrcp)/[A-Za-z0-9+/=_\-]{8,})["\']',
            r'atob\((["\'][A-Za-z0-9+/=]{20,}["\'])\)'
        ):
            m = re.search(pat, body)
            if m:
                candidate = m.group(1).strip('"\'')
                if 'atob' in pat:
                    try:
                        candidate = base64.b64decode(candidate + '==').decode('utf-8', errors='replace')
                    except: continue
                return urljoin(f"https://{current_host}", candidate)
        return None

    def _xor_decode(self, encoded_hex, seed):
        try:
            buf = bytes.fromhex(encoded_hex)
            seed_b = seed.encode('utf-8')
            out = bytearray(len(buf))
            for i, b in enumerate(buf):
                out[i] = b ^ seed_b[i % len(seed_b)]
            return out.decode('utf-8', errors='replace')
        except: return ''

    def _find_and_add_direct(self, body, referer, server_name):
        pats = [
            r'file\s*:\s*["\']([^"\']+\.(?:m3u8|mp4)[^"\']*)["\']',
            r'sources?\s*:\s*\[\s*\{[^}]*?file\s*:\s*["\']([^"\']+)["\']',
            r'["\'](https?://[^"\']+\.(?:m3u8|mp4)[^"\']*)["\']',
            r'(https?://[^\s\'"<>(){}]+?\.m3u8[^\s\'"<>(){}]*)',
            r'atob\((["\'][A-Za-z0-9+/=]{40,}["\'])\)'
        ]
        for pat in pats:
            for m in re.finditer(pat, body):
                u = m.group(1).replace('\\/', '/').strip('"\'')
                if 'atob' in pat:
                    try:
                        u = base64.b64decode(u + '===').decode('utf-8', errors='replace')
                        nm = re.search(r'(https?://[^\s\'"<>]+?\.m3u8[^\s\'"<>]*)', u)
                        if nm: u = nm.group(1)
                        else: continue
                    except: continue

                if not (u.startswith('http') or u.startswith('//')): continue
                if u.startswith('//'): u = 'https:' + u

                if u not in [r['url'].split('|')[0] for r in self.results]:
                    self.results.append({
                        'source': server_name,
                        'quality': '720p',
                        'url': f"{u}|Referer=https://cloudnestra.com/&Origin=https://cloudnestra.com&User-Agent={self.ua}",
                        'direct': True,
                        'info': 'HLS' if '.m3u8' in u else 'MP4'
                    })

    def _parse_server_pool(self, body):
        pool = []
        for m in re.finditer(r'(?:servers|cdns|hosts|srv|cdn_list)\s*[:=]\s*\[([^\]]+)\]', body, re.I):
            pool += re.findall(r'["\']([a-z0-9.\-]+\.[a-z]{2,})["\']', m.group(1), re.I)
        return list(set(pool))

    def _expand_templates(self, raw_file, page_pool):
        parts = [p.strip() for p in re.split(r'\s+or\s+', raw_file) if p.strip()]
        pool = list(dict.fromkeys(page_pool + self.cdn_pool))
        candidates = []
        for part in parts:
            placeholders = re.findall(r'\{v(\d+)\}', part)
            if not placeholders:
                candidates.append(part)
                continue
            for host in pool:
                url = part
                for n in placeholders:
                    url = url.replace('{v%s}' % n, host)
                candidates.append(url)
        return candidates

    def resolve(self, url):
        return url
# -*- coding: utf-8 -*-
import hashlib
import hmac
import json
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
import requests
from resources.lib.modules import log_utils as log
from resources.lib.modules import purecrypto

API = 'https://api.stigstream.ru'
SITE = 'https://stigstream.ru'
TIMEOUT = 12

API_KEY = '0cb4683fa6eb666bf70712b57e0110adf4a173bd45110869b19c298b724657c2'
_MASTER_HEX = 'e249eabfa7abb1c062c988527e0eedab088ac9c2b495acba8120666a651c337e'
_AES_INFO = b'3b8e1f5c9a2d6f0e4c7b3a8d1f9e5c2a'
_CC_INFO = b'9f2c7e4b1d8a3f6c0e5b9d2a7f4c1e8b'

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/124.0 Safari/537.36')

_FALLBACK_SERVERS = ('Aqua', 'Nova', 'Vix', 'Nebula', 'Quartz',
                     'Obsidian', 'Onyx', 'Atlas')

class source:
    def __init__(self):
        self.results = []
        self.domains = ['stigstream.ru']
        self._client = _StigClient()

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return str(tmdb) if tmdb else None

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return str(tmdb) if tmdb else None

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url: return None
        return f"{url}|{season or 1}|{episode or 1}"

    def sources(self, url, hostDict):
        if not url: return []
        try:
            tmdb_id = url.split('|')[0]
            season = url.split('|')[1] if '|' in url else None
            episode = url.split('|')[2] if '|' in url else None

            servers = self._list_servers()

            if season is None:
                path_tmpl = '/movie/{srv}/{tmdb}'
            else:
                path_tmpl = '/tv/{srv}/{tmdb}/{s}/{e}'

            for srv in servers:
                name = srv.get('name')
                path = path_tmpl.format(srv=name, tmdb=tmdb_id, s=season, e=episode)
                data, status = self._client.call(path)
                if data:
                    for st in (data.get('streams') or []):
                        su = st.get('url')
                        if not su: continue
                        self.results.append({
                            'url': su,
                            'source': f"Stigstream {name}",
                            'quality': st.get('quality') or '720p',
                            'direct': True,
                            'info': 'HLS'
                        })
                if len(self.results) >= 12: break
        except Exception as e:
            log.log(f"stigstream error: {e}")
        return self.results

    def _list_servers(self):
        data, status = self._client.call('/servers')
        if not data:
            return [{'name': n} for n in _FALLBACK_SERVERS]
        if isinstance(data, dict):
            data = data.get('servers') or data.get('data') or []
        return [s for s in data if isinstance(s, dict) and s.get('status') == 'Working']

    def resolve(self, url):
        return url

def _hkdf_sha512(ikm, salt, info, length=32):
    h = hashlib.sha512
    if not salt:
        salt = b'\x00' * h().digest_size
    prk = hmac.new(salt, ikm, h).digest()
    out = b''
    t = b''
    counter = 1
    while len(out) < length:
        t = hmac.new(prk, t + info + bytes([counter]), h).digest()
        out += t
        counter += 1
    return out[:length]

def _decrypt_v3(env, req_token):
    if env.get('v') != '3':
        raise ValueError('stigstream: unexpected envelope version: %r' % env.get('v'))
    master = bytes.fromhex(_MASTER_HEX)
    salt = b''
    if req_token:
        try: salt += bytes.fromhex(req_token)
        except: pass
    salt += bytes.fromhex(env['k'])
    aes_key = _hkdf_sha512(master, salt, _AES_INFO, 32)
    chacha_key = _hkdf_sha512(master, salt, _CC_INFO, 32)
    outer_pt = purecrypto.aes256_gcm_decrypt(aes_key,
                               bytes.fromhex(env['a']),
                               bytes.fromhex(env['c']),
                               bytes.fromhex(env['b']))
    inner = json.loads(outer_pt)
    payload = purecrypto.chacha20_poly1305_decrypt(chacha_key,
                              bytes.fromhex(inner['x']),
                              bytes.fromhex(inner['z']),
                              bytes.fromhex(inner['y']))
    return json.loads(payload)

class _StigClient(object):
    def __init__(self):
        self._sess = requests.Session()
        self._sess.headers.update({
            'User-Agent': UA,
            'Accept': 'application/json',
            'Accept-Encoding': 'gzip, deflate',
            'Origin': SITE,
            'Referer': SITE + '/',
            'X-Api-Key': API_KEY,
        })
        self._token = None
        self._lock = threading.Lock()

    def call(self, path):
        with self._lock:
            sent_token = self._token
        headers = {}
        if sent_token:
            headers['X-Request-Token'] = sent_token
        try:
            r = self._sess.get(API + path, headers=headers, timeout=TIMEOUT)
            next_token = r.headers.get('X-Next-Token')
            if next_token:
                with self._lock: self._token = next_token
            if r.status_code != 200: return None, r.status_code
            return _decrypt_v3(r.json(), sent_token), 200
        except:
            return None, 0

# -*- coding: utf-8 -*-
import json
import requests
import hmac
import hashlib
from resources.lib.modules import purecrypto

API = 'https://api.stigstream.ru'
API_KEY = '0cb4683fa6eb666bf70712b57e0110adf4a173bd45110869b19c298b724657c2'
_MASTER_HEX = 'e249eabfa7abb1c062c988527e0eedab088ac9c2b495acba8120666a651c337e'
_AES_INFO = b'3b8e1f5c9a2d6f0e4c7b3a8d1f9e5c2a'
_CC_INFO = b'9f2c7e4b1d8a3f6c0e5b9d2a7f4c1e8b'

class source:
    def __init__(self):
        self.results = []
        self.domains = ['stigstream.ru']
        self.session = requests.Session()
        self.session.headers.update({'X-Api-Key': API_KEY, 'User-Agent': 'Mozilla/5.0'})
        self.token = None

    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        return str(tmdb) if tmdb else None

    def tvshow(self, imdb, tmdb, tvdb, title, localtitle, aliases, year):
        return str(tmdb) if tmdb else None

    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url: return None
        return f"{url}/{season}/{episode}"

    def sources(self, url, hostDict):
        if not url: return []
        try:
            servers = self._get_servers()
            content_path = '/movie' if '/' not in url else '/tv'
            for srv in servers[:8]: 
                try:
                    path = f"{content_path}/{srv['id']}/{url}"
                    streams = self._get_streams(path)
                    for s in streams:
                        item = {
                            'source': f"Stigstream {srv['name']}",
                            'quality': s.get('quality', '720p'),
                            'url': s['url'],
                            'direct': True,
                            'info': s.get('info', '')
                        }
                        self.results.append(item)
                except: pass
        except: pass
        return self.results

    def _get_servers(self):
        resp = self.session.get(f"{API}/servers", timeout=10)
        return self._decrypt_envelope(resp)['servers']

    def _get_streams(self, path):
        headers = {}
        if self.token: headers['X-Request-Token'] = self.token
        resp = self.session.get(API + path, headers=headers, timeout=10)
        if 'X-Next-Token' in resp.headers: self.token = resp.headers['X-Next-Token']
        data = self._decrypt_envelope(resp)
        streams = []
        for variant in data.get('streams', []):
            streams.append({
                'url': variant['url'],
                'quality': variant.get('q', '720p'),
                'info': f"HLS | {variant.get('n', 'Nebula')}"
            })
        return streams

    def _decrypt_envelope(self, resp):
        env = resp.json()
        if env.get('v') != '3': raise ValueError("Unsupported version")
        
        salt = bytes.fromhex(env['k'])
        if self.token: salt = self.token.encode() + salt
        
        master = bytes.fromhex(_MASTER_HEX)
        prk = hmac.new(salt, master, hashlib.sha256).digest()
        aes_key = hmac.new(prk, _AES_INFO + b'\x01', hashlib.sha256).digest()
        cc_key = hmac.new(prk, _CC_INFO + b'\x01', hashlib.sha256).digest()
        
        inner_hex = purecrypto.aes256_gcm_decrypt(
            aes_key, bytes.fromhex(env['a']), bytes.fromhex(env['c']), bytes.fromhex(env['b'])
        )
        inner = json.loads(inner_hex)
        
        final_json = purecrypto.chacha20_poly1305_decrypt(
            cc_key, bytes.fromhex(inner['x']), bytes.fromhex(inner['z']), bytes.fromhex(inner['y'])
        )
        return json.loads(final_json)

    def resolve(self, url):
        return url

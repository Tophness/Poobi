"""
    Plugin for ResolveURL
    Copyright (C) 2025 gujal

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
"""

import json
import requests
from six.moves import urllib_parse
from resolveurl.lib import helpers
from resolveurl.lib.aesgcm import python_aesgcm
from resolveurl import common
from resolveurl.resolver import ResolveUrl, ResolverError


class ByseResolver(ResolveUrl):
    name = 'Byse'
    domains = [
        'f16px.com', 'bysesayeveum.com', 'bysetayico.com', 'bysevepoin.com', 'bysezejataos.com',
        'bysekoze.com', 'bysesukior.com', 'bysejikuar.com', 'bysefujedu.com', 'bysedikamoum.com',
        'bysebuho.com', "byse.sx", 'filemoon.sx', 'filemoon.to', 'filemoon.in', 'filemoon.link',
        'filemoon.wf', 'cinegrab.com', 'filemoon.eu', 'filemoon.art', 'moonmov.pro', '96ar.com',
        'kerapoxy.cc', 'furher.in', '1azayf9w.xyz', '81u6xl9d.xyz', 'smdfs40r.skin', 'c1z39.com',
        'bf0skv.org', 'z1ekv717.fun', 'l1afav.net', '222i8x.lol', '8mhlloqo.fun', 'f51rm.com',
        'xcoic.com', 'filemoon.nl', 'boosteradx.online', 'streamlyplayer.online', 'bysewihe.com',
        'byselapuix.com'
    ]
    pattern = (
        r'(?://|\.)((?:filemoon|cinegrab|moonmov|kerapoxy|furher|1azayf9w|81u6xl9d|f16px|'
        r'smdfs40r|bf0skv|z1ekv717|l1afav|222i8x|8mhlloqo|96ar|xcoic|f51rm|c1z39|boosteradx|'
        r'byse(?:sayeveum|tayico|vepoin|zejataos|koze|sukior|jikuar|fujedu|dikamoum|buho|wihe|lapuix)?)'
        r'\.(?:sx|to|s?k?in|link|nl|wf|com|eu|art|pro|cc|xyz|org|fun|net|lol|online))'
        r'/(?:(?:e|d|download)/)?([0-9a-zA-Z]+)'
    )

    def get_media_url(self, host, media_id):
        playback_url = 'https://{host}/api/videos/{media_id}/playback'.format(host=host, media_id=media_id)
        origin_referer = 'https://{host}/'.format(host=host)

        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/114.0',
            'Referer': origin_referer,
            'Origin': origin_referer.rstrip('/'),
            'Content-Type': 'application/json'
        }
        
        payload = self.fp(16, 0.6, 0.9)
        
        try:
            response = requests.post(playback_url, headers=headers, json=payload, timeout=10)
            
            if response.status_code != 200:
                raise ResolverError('Server returned status code: %d' % response.status_code)
                
            html_data = response.json()
        except Exception as e:
            raise ResolverError('Request or JSON parse failed: %s' % str(e))

        sources = html_data.get('sources')
        if sources:
            sources = [(x.get('label'), x.get('url')) for x in sources]
            uri = helpers.pick_source(helpers.sort_sources_list(sources))
            if uri.startswith('/'):
                uri = urllib_parse.urljoin(playback_url, uri)
            url = helpers.get_redirect_url(uri, headers=headers)
            return url + helpers.append_headers(headers)
            
        # Decryption flow
        pd = html_data.get('playback')
        if pd:
            iv = self.ft(pd.get('iv'))
            key = self.xn(pd.get('key_parts'))
            pl = self.ft(pd.get('payload'))
            cipher = python_aesgcm.new(key)
            ct = cipher.open(iv, pl)
            
            try:
                decrypted_text = ct.decode('utf-8')
            except UnicodeDecodeError:
                decrypted_text = ct.decode('latin-1')
                
            ct_json = json.loads(decrypted_text)
            sources = ct_json.get('sources')
            if sources:
                sources = [(x.get('label'), x.get('url')) for x in sources]
                uri = helpers.pick_source(helpers.sort_sources_list(sources))
                return uri + helpers.append_headers(headers)

        raise ResolverError('Video Link Not Found')

    def get_url(self, host, media_id):
        redirect_domains = ['boosteradx.online', 'byse.sx']
        if host in redirect_domains:
            host = 'streamlyplayer.online'
        return 'https://{host}/d/{media_id}'.format(host=host, media_id=media_id)

    @staticmethod
    def ft(e):
        if not e:
            return b''
        t = e.replace('-', '+').replace('_', '/')
        missing_padding = len(t) % 4
        if missing_padding:
            t += '=' * (4 - missing_padding)
        return helpers.b64decode(t, binary=True)

    def xn(self, e):
        decoded_parts = []
        for part in e:
            try:
                decoded_parts.append(self.ft(part))
            except Exception:
                pass

        active_parts = [part for part in decoded_parts if len(part) == 16]
        return b''.join(active_parts)

    @staticmethod
    def fp(x, y, z):
        from binascii import hexlify
        from hashlib import sha256
        from os import urandom
        from time import time
        from random import uniform
        v_id = hexlify(urandom(x)).decode()
        d_id = hexlify(urandom(x)).decode()
        ctime = int(time())
        t_data = {
            'viewer_id': v_id,
            'device_id': d_id,
            'confidence': round(uniform(y, z), 2),
            'iat': ctime,
            'exp': ctime + 600
        }
        t_bdata = helpers.b64urlencode(json.dumps(t_data), strip=True)
        t_sig = helpers.b64urlencode(sha256(t_bdata.encode()).digest(), strip=True)
        token = '{0}.{1}'.format(t_bdata, t_sig)
        return {'fingerprint': {'viewer_id': v_id, 'device_id': d_id, 'confidence': t_data['confidence'], 'token': token}}
"""
    Plugin for ResolveURL
    Dedicated for Poobi on Android (Chaquopy + Native ARM C Acceleration)
"""

import json
import base64
import ctypes
from random import uniform
from six.moves import urllib_parse
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError
from modules.purecrypto import aes256_gcm_decrypt

_c_lib = None
try:
    _c_lib = ctypes.CDLL("libbyse_pow.so")
    _c_lib.solve_pow.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_int]
    _c_lib.solve_pow.restype = ctypes.c_int32
except Exception as e:
    _c_lib = None


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
        'byselapuix.com', 'embedplaybyse.top', 'sb1254w9megshle.org', 'streamlyplayero.online',
        'moflix-stream.link', 'bysezoxexe.com'
    ]
    pattern = (
        r'(?://|\.)((?:filemoon|cinegrab|moonmov|kerapoxy|furher|1azayf9w|81u6xl9d|f16px|sb1254w9megshle|'
        r'smdfs40r|bf0skv|z1ekv717|l1afav|222i8x|8mhlloqo|96ar|xcoic|f51rm|c1z39|boosteradx|streamlyplayero?|moflix-stream|'
        r'(?:embedplay)?byse(?:sayeveum|tayico|zejataos|koze|sukior|jikuar|fujedu|dikamoum|buho|wihe|lapuix|vepoin|zoxexe)?)'
        r'\.(?:sx|top?|s?k?in|link|nl|wf|com|eu|art|pro|cc|xyz|org|fun|net|lol|online))'
        r'/(?:(?:e|d|download)/)?([0-9a-zA-Z]+)'
    )
    UA = "Mozilla/5.0 (Linux; Android 10; TX6s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    def get_media_url(self, host, media_id):
        if not _c_lib:
            raise ResolverError("libbyse_pow.so not found in APK jniLibs")

        web_url = self.get_url(host, media_id)
        ref = urllib_parse.urljoin(web_url, '/')
        headers = {
            'User-Agent': self.UA,
            'Referer': web_url,
            'Origin': ref[:-1]
        }

        # Step 1: Details from main host
        details_url = f"{ref}api/videos/{media_id}/details"
        try:
            resp_details = self.net.http_GET(details_url, headers=headers)
            details = json.loads(resp_details.content)
        except Exception:
            try:
                details_url = f"{ref}api/videos/{media_id}/embed/details"
                resp_details = self.net.http_GET(details_url, headers=headers)
                details = json.loads(resp_details.content)
            except Exception:
                raise ResolverError('Video details not found')

        embed_frame_url = details.get('embed_frame_url')
        if embed_frame_url:
            frame_origin = f"https://{urllib_parse.urlparse(embed_frame_url).netloc}"
        else:
            frame_origin = ref[:-1]
            embed_frame_url = web_url

        frame_headers = {
            'User-Agent': self.UA,
            'Referer': embed_frame_url,
            'Origin': frame_origin,
            'Accept': 'application/json, text/plain, */*',
            'Content-Type': 'application/json',
            'X-Embed-Origin': host,
            'X-Embed-Referer': web_url,
            'X-Embed-Parent': embed_frame_url,
            'Sec-Fetch-Site': 'same-origin',
            'Sec-Fetch-Mode': 'cors',
            'Sec-Fetch-Dest': 'empty'
        }

        captcha_url = f"{frame_origin}/api/videos/{media_id}/embed/captcha"
        try:
            resp_cap = self.net.http_POST(captcha_url, headers=frame_headers, form_data={}, jdata=True, timeout=15)
            cap_data = json.loads(resp_cap.content)
        except Exception:
            cap_data = {}

        captcha_token = None
        if cap_data.get('pow_nonce') and cap_data.get('pow_difficulty'):
            nonce = cap_data['pow_nonce']
            diff = int(cap_data['pow_difficulty'])
            pow_token = cap_data['pow_token']

            sol_int = _c_lib.solve_pow(nonce.encode('ascii'), diff, 2)
            if sol_int < 0:
                raise ResolverError("Native PoW solver timed out")

            solution = str(sol_int)

            verify_url = f"{frame_origin}/api/videos/{media_id}/embed/captcha/verify"
            verify_payload = {"pow_token": pow_token, "solution": solution}
            try:
                resp_ver = self.net.http_POST(verify_url, headers=frame_headers, form_data=verify_payload, jdata=True, timeout=15)
                verify_data = json.loads(resp_ver.content)
                captcha_token = verify_data.get('token')
            except Exception:
                pass

        playback_url = f"https://{host}/api/videos/{media_id}/embed/playback"
        playback_headers = {
            'User-Agent': self.UA,
            'Referer': embed_frame_url,
            'Origin': f"https://{host}",
            'Accept': 'application/json, text/plain, */*',
            'Content-Type': 'application/json',
            'X-Embed-Origin': host,
            'X-Embed-Referer': web_url,
            'X-Embed-Parent': embed_frame_url
        }
        if captcha_token:
            playback_headers['X-Captcha-Token'] = captcha_token

        fingerprint = self.fp(16, 0.83, 0.94)
        resp_pb = self.net.http_POST(playback_url, headers=playback_headers, form_data=fingerprint, jdata=True, timeout=20)
        pb_data = json.loads(resp_pb.content)

        if 'sources' in pb_data and pb_data['sources']:
            sources = [(x.get('label'), x.get('url')) for x in pb_data['sources']]
            return helpers.pick_source(helpers.sort_sources_list(sources)) + helpers.append_headers(playback_headers)

        playback = pb_data.get('playback')
        if not playback:
            raise ResolverError('Playback configuration access denied')

        version = int(playback.get('version', 1))
        key_parts = playback.get('key_parts', [])
        iv = self.ft(playback.get('iv'))
        payload = self.ft(playback.get('payload'))

        part_a = key_parts[version - 1]
        part_b = key_parts[30 - version]
        key = self.ft(part_a) + self.ft(part_b)

        tag = payload[-16:]
        ciphertext = payload[:-16]

        decrypted_bytes = aes256_gcm_decrypt(key, iv, ciphertext, tag)
        decrypted_json = json.loads(decrypted_bytes.decode('utf-8'))
        sources = decrypted_json.get('sources', [])

        if sources:
            source_list = [(x.get('label', 'HD'), x.get('url')) for x in sources if x.get('url')]
            return helpers.pick_source(helpers.sort_sources_list(source_list)) + helpers.append_headers(playback_headers)

        raise ResolverError('No playable stream found in decrypted payload')

    def get_url(self, host, media_id):
        return f"https://{host}/e/{media_id}"

    @staticmethod
    def ft(data):
        if isinstance(data, bytes):
            data = data.decode('utf-8')
        data = data.replace('-', '+').replace('_', '/')
        missing = len(data) % 4
        if missing:
            data += '=' * (4 - missing)
        return base64.b64decode(data)

    @staticmethod
    def fp(x, y, z):
        from binascii import hexlify
        from hashlib import sha256
        from os import urandom
        from time import time
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
        token = f"{t_bdata}.{t_sig}"
        t_data.update({'token': token})
        t_data.pop('iat')
        t_data.pop('exp')
        return {'fingerprint': t_data}
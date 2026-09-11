"""
    Plugin for ResolveURL
    Copyright (C) 2020 gujal
"""

import re
import random
import string
import time
from six.moves import urllib_parse
from resolveurl.lib import helpers
from resolveurl import common
from resolveurl.resolver import ResolveUrl, ResolverError


class DoodStreamResolver(ResolveUrl):
    name = 'DoodStream'
    domains = [
        'dood.watch', 'doodstream.com', 'dood.to', 'dood.so', 'dood.cx', 'dood.la', 'dood.ws',
        'dood.sh', 'doodstream.co', 'dood.pm', 'dood.wf', 'dood.re', 'dood.yt', 'dooood.com',
        'dood.stream', 'ds2play.com', 'doods.pro', 'ds2video.com', 'd0o0d.com', 'do0od.com',
        'd0000d.com', 'd000d.com', 'dood.li', 'dood.work', 'dooodster.com', 'vidply.com',
        'all3do.com', 'do7go.com', 'doodcdn.io', 'doply.net', 'vide0.net', 'vvide0.com',
        'd-s.io', 'dsvplay.com', 'myvidplay.com', 'playmogo.com'
    ]
    pattern = (
        r'(?://|\.)((?:do*0*o*0*ds?(?:tream|ter|cdn)?|ds[2v](?:play|video)|(?:my)?v*id(?:pla?y|e0)|all3do|'
        r'd-s|do(?:7go|ply)|playmogo)\.'
        r'(?:[cit]om?|watch|s[ho]|cx|l[ai]|w[sf]|pm|re|yt|stream|pro|work|net))/(?:d|e)/([0-9a-zA-Z]+)'
    )

    def get_media_url(self, host, media_id, subs=False):
        if host not in ['doodstream.com', 'myvidplay.com', 'playmogo.com']:
            host = 'playmogo.com'
        web_url = self.get_url(host, media_id)
        headers = {
            'User-Agent': common.RAND_UA,
            'Referer': f'https://{host}/'
        }

        r = self.net.http_GET(web_url, headers=headers)
        if r.get_url() != web_url:
            parsed = urllib_parse.urlparse(r.get_url()).netloc
            if parsed:
                host = parsed
                web_url = self.get_url(host, media_id)
        headers.update({'Referer': web_url})
        html = r.content

        # Check for deleted/missing media
        if any(msg in html.lower() for msg in ['video not found', 'file not found', 'has been deleted', 'video no longer exists']):
            raise ResolverError('File Not Found or Removed')

        # Follow iframe to embed if on a /d/ landing page
        match = re.search(r'<iframe\s*src="([^"]+)', html)
        if match:
            embed_url = urllib_parse.urljoin(web_url, match.group(1))
            headers.update({'Referer': web_url})
            html = self.net.http_GET(embed_url, headers=headers).content
        else:
            embed_url = web_url.replace('/d/', '/e/')
            headers.update({'Referer': web_url})
            html = self.net.http_GET(embed_url, headers=headers).content

        if any(msg in html.lower() for msg in ['video not found', 'file not found', 'has been deleted']):
            raise ResolverError('File Not Found or Removed')

        # Subtitles extraction
        subtitles = {}
        if subs:
            matches = re.findall(r"""dsplayer\.addRemoteTextTrack\({src:'([^']+)',\s*label:'([^']*)',kind:'captions'""", html)
            if matches:
                for src, label in matches:
                    if len(label) > 1:
                        subtitles[label] = 'https:' + src if src.startswith('//') else src

        # 1. Locate /pass_md5/ URL
        pass_url = None
        m_pass = re.search(r'''['"](/pass_md5/[^'"]+)['"]''', html)
        if m_pass:
            pass_url = m_pass.group(1)
        else:
            m_pass_concat = re.search(r'''['"](/pass_md5/)['"]\s*\+\s*(\w+)''', html)
            if m_pass_concat:
                var_name = m_pass_concat.group(2)
                v_val = re.search(r'''(?:var|let|const)\s+%s\s*=\s*['"]([^'"]+)['"]''' % var_name, html)
                if v_val:
                    pass_url = m_pass_concat.group(1) + v_val.group(1)

        # 2. Locate token query parameter
        token = None
        m_tok = re.search(r'''function\s*makePlay[^{]*\{.*?return[^\w'"`?]*(\?token=[^&"'\s]+)''', html, re.DOTALL)
        if m_tok:
            token = m_tok.group(1)
        else:
            m_tok_direct = re.search(r'''['"](\?token=[^&"'\s]+)''', html) or re.search(r'''(\?token=[a-zA-Z0-9]+)''', html)
            if m_tok_direct:
                token = m_tok_direct.group(1)

        if pass_url and token:
            pass_full_url = urllib_parse.urljoin(embed_url, pass_url)
            pass_headers = dict(headers)
            pass_headers['Referer'] = embed_url

            pass_resp = self.net.http_GET(pass_full_url, headers=pass_headers).content
            if 'cloudflarestorage.' in pass_resp:
                vid_src = pass_resp.strip() + helpers.append_headers(headers)
            else:
                expiry = f"&expiry={int(time.time() * 1000)}" if 'expiry=' not in token else ""
                vid_src = self.dood_decode(pass_resp) + token + expiry + helpers.append_headers(headers)

            if subs:
                return vid_src, subtitles
            return vid_src

        raise ResolverError('Video Link Not Found')

    def get_url(self, host, media_id):
        return self._default_get_url(host, media_id, template='https://{host}/d/{media_id}')

    def dood_decode(self, data):
        t = string.ascii_letters + string.digits
        return data + ''.join([random.choice(t) for _ in range(10)])
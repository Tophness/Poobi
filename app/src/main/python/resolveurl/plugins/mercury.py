# -*- coding: utf-8 -*-
"""
    Plugin for ResolveURL - Mercury / StreamingNow
    Copyright (C) 2026 Poobi
"""

import re
import json
import subprocess
from urllib.parse import urlparse
from resolveurl import common
from resolveurl.lib import helpers
from resolveurl.resolver import ResolveUrl, ResolverError


class MercuryResolver(ResolveUrl):
    name = 'Mercury'
    domains = ['vidlink.pro', 'streamingnow.mov', 'multiembed.mov']
    pattern = r'(?://|\.)(vidlink\.pro/api/mercury|streamingnow\.mov|multiembed\.mov)(?:/\?play=|\?id=)?([0-9a-zA-Z-_\+/=]+)'

    def get_media_url(self, host, media_id):
        if 'multiembed.mov' in host:
            web_url = f"https://multiembed.mov/?video_id={media_id}&tmdb=1"
        elif 'vidlink.pro' in host:
            web_url = f"https://vidlink.pro/api/mercury?id={media_id}&type=movie"
        else:
            web_url = f"https://streamingnow.mov/?play={media_id}"

        headers = {
            'User-Agent': common.RAND_UA,
            'Referer': 'https://cinespot.org/',
            'Origin': 'https://cinespot.org',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
        }

        resp = self.net.http_GET(web_url, headers=headers)
        html = resp.content

        # Handle multiembed 302 redirect
        if resp.get_url() != web_url and 'streamingnow.mov' in resp.get_url():
            web_url = resp.get_url()
            headers['Referer'] = 'https://multiembed.mov/'

        m_key = re.search(r"window\['([^']+)'\]\s*=\s*'([^']+)'", html)
        if not m_key:
            # Check for direct m3u8
            m3u8 = re.search(r'["\'](https?://[^"\']+\.m3u8[^"\']*)["\']', html)
            if m3u8:
                return m3u8.group(1) + helpers.append_headers(headers)
            raise ResolverError("Mercury: Cipher payload not found")

        var_name = m_key.group(1)
        ciphertext = m_key.group(2)

        # Execute headless deobfuscation in Node
        stream_url = self._eval_node_cipher(html, var_name, web_url)
        if stream_url:
            stream_headers = {
                'User-Agent': common.RAND_UA,
                'Referer': web_url,
                'verifypeer': 'false'
            }
            return stream_url + helpers.append_headers(stream_headers)

        raise ResolverError("Mercury: Failed to evaluate stream configuration")

    def _eval_node_cipher(self, html, var_name, web_url):
        scripts = re.findall(r'<script[^>]*>(.*?)</script>', html, re.DOTALL)
        full_js = "\n".join(scripts)

        eval_script = f"""
        const globalEnv = {{
            userAgent: "{common.RAND_UA}",
            href: "{web_url}"
        }};

        class MockEl {{
            constructor(tag) {{ this.tagName = (tag || 'DIV').toUpperCase(); this.style = {{}}; this.children = []; }}
            setAttribute(k, v) {{ this[k] = v; }}
            getAttribute(k) {{ return this[k] || null; }}
            appendChild(c) {{ this.children.push(c); return c; }}
            insertBefore(n) {{ return n; }}
            addEventListener() {{}}
            removeEventListener() {{}}
            set src(v) {{ if (v && v.includes('.m3u8')) console.log("STREAM_OUTPUT:" + v); }}
        }}

        const windowMock = new Proxy(global, {{
            get(target, prop) {{
                if (prop in target) return target[prop];
                if (prop === 'HTMLElement' || prop === 'Element' || prop === 'Node') return MockEl;
                if (prop === 'document') return {{
                    createElement: (t) => new MockEl(t),
                    getElementsByTagName: () => [new MockEl('SCRIPT')],
                    getElementById: () => new MockEl('DIV'),
                    querySelector: () => new MockEl('DIV'),
                    querySelectorAll: () => [],
                    body: new MockEl('BODY'),
                    documentElement: new MockEl('HTML')
                }};
                if (prop === 'localStorage' || prop === 'sessionStorage') return {{
                    getItem: () => null, setItem: () => {{}}, removeItem: () => {{}}
                }};
                if (prop === 'navigator') return {{ userAgent: globalEnv.userAgent, language: 'en-US' }};
                if (prop === 'location') return {{ href: globalEnv.href, protocol: 'https:', host: 'vidlink.pro' }};
                if (prop === 'jwplayer') return () => ({{
                    setup: (c) => {{
                        const f = (c.sources && c.sources[0]) ? c.sources[0].file : c.file;
                        if (f) console.log("STREAM_OUTPUT:" + f);
                    }},
                    on: () => {{}}
                }});
                return function() {{ return windowMock; }};
            }}
        }});

        global.window = windowMock;
        global.self = windowMock;
        global.document = windowMock.document;
        global.HTMLElement = MockEl;
        global.localStorage = windowMock.localStorage;

        try {{
            {full_js}
        }} catch(e) {{}}
        """

        try:
            res = subprocess.run(["node", "-e", eval_script], capture_output=True, text=True, timeout=8)
            for line in res.stdout.splitlines():
                if line.startswith("STREAM_OUTPUT:"):
                    return line.replace("STREAM_OUTPUT:", "").strip()
        except Exception:
            pass

        return None

    def get_url(self, host, media_id):
        return f"https://{host}/api/mercury?id={media_id}&type=movie"
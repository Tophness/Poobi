# -*- coding: utf-8 -*-

import re

from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import client
from resources.lib.modules import scrape_sources
#from resources.lib.modules import log_utils

_BASE = ('https://hdhub.thevolecitor.qzz.io/'
         'eyJ0b3Jib3giOiJ1bnNldCIsInF1YWxpdGllcyI6IjIxNjBwLDEwODBwLDcyMHAiLCJzb3J0IjoiZGVzYyJ9')


class source:
    def __init__(self):
        self.results = []
        self.base_link = _BASE
        self.domains = ['hdhub.thevolecitor.qzz.io']
        self.probe_link = _BASE + '/stream/movie/tt0111161.json'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'media': 'movie'}
        return urlencode(url)


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        url = {'imdb': imdb, 'media': 'series'}
        return urlencode(url)


    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url:
            return
        url = parse_qs(url)
        url = dict([(i, url[i][0]) if url[i] else (i, '') for i in url])
        url['season'], url['episode'] = season, episode
        return urlencode(url)


    def _imdb_id(self, imdb):
        if not imdb or imdb == '0':
            return None
        imdb = str(imdb).strip()
        if not imdb.startswith('tt'):
            imdb = 'tt' + re.sub(r'[^0-9]', '', imdb)
        return imdb


    def _fetch_streams(self, media_type, resource_id):
        try:
            api_url = '%s/stream/%s/%s.json' % (_BASE, media_type, resource_id)
            data = client.request(
                api_url,
                headers={'User-Agent': client.UserAgent, 'Accept': 'application/json'},
                timeout='15',
                output='json',
            )
            if isinstance(data, dict):
                return data.get('streams') or []
        except Exception:
            #log_utils.log('_fetch_streams', 1)
            pass
        return []


    def _parse_stream(self, stream):
        name = stream.get('name', '') or ''
        desc = stream.get('description', '') or ''
        url = stream.get('url', '') or ''
        hints = stream.get('behaviorHints', {}) or {}

        size_str = ''
        size_bytes = hints.get('videoSize', 0) or 0
        if size_bytes:
            size_str = self._fmt_size(size_bytes)
        else:
            m = re.search(r'💾\s*([\d.]+\s*(?:GB|MB))', desc, re.I)
            if m:
                size_str = m.group(1).strip()

        haystack = (name + ' ' + desc).upper()
        quality = ''
        if '4K' in haystack or '2160P' in haystack or 'UHD' in haystack:
            quality = '4K'
        elif '1080P' in haystack:
            quality = '1080p'
        elif '720P' in haystack:
            quality = '720p'
        elif '480P' in haystack:
            quality = '480p'

        codec = ''
        if re.search(r'HEVC|x265|H\.265|H265', desc, re.I):
            codec = 'HEVC'
        elif re.search(r'AVC|x264|H\.264|H264', desc, re.I):
            codec = 'AVC'

        audio = ''
        for tag, pat in (
            ('Hindi', r'hindi'), ('Tamil', r'tamil'), ('Telugu', r'telugu'),
            ('Dual', r'dual.?audio'), ('Multi', r'multi.?audio'), ('English', r'english'),
        ):
            if re.search(pat, desc, re.I):
                audio = tag
                break

        hdr = ''
        if re.search(r'HDR10\+', desc, re.I):
            hdr = 'HDR10+'
        elif re.search(r'HDR10', desc, re.I):
            hdr = 'HDR10'
        elif re.search(r'DV|Dolby.?Vision', desc, re.I):
            hdr = 'DV'
        elif re.search(r'\bHDR\b', desc, re.I):
            hdr = 'HDR'

        rtype = ''
        if re.search(r'REMUX', desc, re.I):
            rtype = 'REMUX'
        elif re.search(r'BluRay|BDRip', desc, re.I):
            rtype = 'BluRay'
        elif re.search(r'WEB-?DL', desc, re.I):
            rtype = 'WEB-DL'
        elif re.search(r'WEBRip', desc, re.I):
            rtype = 'WEBRip'

        url_l = url.lower()
        if 'pixeldrain' in url_l:
            server = 'PixelDrain'
        elif 'drive.google' in url_l or 'googleusercontents' in url_l:
            server = 'Drive'
        elif 'telegramcdn' in url_l or 'telegram' in url_l:
            server = 'TG CDN'
        elif 'workers.dev' in url_l or 'fsl-bucket' in url_l:
            server = 'CDN'
        elif url.startswith('magnet:') or url.endswith('.torrent'):
            server = 'Torrent'
        else:
            server = ''
            lines = [l.strip() for l in desc.split('\n') if l.strip()]
            if lines:
                m = re.search(r'^([A-Za-z0-9_\-\.v]+)\s*\|', lines[-1])
                if m:
                    server = m.group(1).strip()

        return {
            'url': url,
            'quality': quality,
            'codec': codec,
            'audio': audio,
            'hdr': hdr,
            'rtype': rtype,
            'size_str': size_str,
            'server': server,
            'raw_name': name,
        }


    def _fmt_size(self, nbytes):
        try:
            if nbytes >= 1073741824:
                return '%.1f GB' % (float(nbytes) / 1073741824)
            if nbytes >= 1048576:
                return '%.1f MB' % (float(nbytes) / 1048576)
        except Exception:
            pass
        return ''


    def _build_info(self, parsed):
        parts = []
        for key in ('server', 'quality', 'codec', 'hdr', 'rtype', 'audio', 'size_str'):
            val = parsed.get(key)
            if val:
                parts.append(val)
        if parts:
            return ' | '.join(parts)
        return parsed.get('raw_name') or 'HdHub'


    def _append_stream(self, hostDict, stream):
        try:
            parsed = self._parse_stream(stream)
            url = parsed.get('url')
            if not url or url.startswith('magnet:') or url.endswith('.torrent'):
                return
            if not url.startswith('http'):
                return
            info = self._build_info(parsed)
            quality = parsed.get('quality') or None
            item = scrape_sources.make_direct_item(hostDict, url, host='Direct', info=info, prep=True)
            if not item.get('url'):
                return
            if quality:
                item['quality'] = quality
            if scrape_sources.check_host_limit(item['source'], self.results):
                return
            self.results.append(item)
        except Exception:
            #log_utils.log('_append_stream', 1)
            pass


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            imdb = self._imdb_id(data.get('imdb'))
            if not imdb:
                return self.results
            media = data.get('media', 'movie')
            if media == 'series':
                season = data.get('season')
                episode = data.get('episode')
                if not (season and episode):
                    return self.results
                resource_id = '%s:%s:%s' % (imdb, int(season), int(episode))
                streams = self._fetch_streams('series', resource_id)
            else:
                streams = self._fetch_streams('movie', imdb)
            for stream in streams:
                self._append_stream(hostDict, stream)
            return self.results
        except Exception:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url

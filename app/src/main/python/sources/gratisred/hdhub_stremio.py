# -*- coding: utf-8 -*-

import re
from six.moves.urllib_parse import parse_qs, urlencode
from resources.lib.modules import client
from resources.lib.modules import scrape_sources

_BASE = ('https://hdhub.thevolecitor.qzz.io/'
         'eyJ0b3Jib3giOiJ1bnNldCIsInF1YWxpdGllcyI6IjIxNjBwLDEwODBwLDcyMHAiLCJzb3J0IjoiZGVzYyJ9')


class source:
    def __init__(self):
        self.results = []
        self.base_link = _BASE
        self.domains = ['hdhub.thevolecitor.qzz.io']
        self.probe_link = _BASE + '/stream/movie/tt0111161.json'
        self._grouped_releases = {}

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
            pass
        return []

    def _parse_stream(self, stream):
        name = stream.get('name', '') or ''
        desc = stream.get('description', '') or ''
        url = stream.get('url', '') or ''
        hints = stream.get('behaviorHints', {}) or {}

        if not url or not url.startswith('http'):
            return None

        url_l = url.lower()
        if 'hubcloud.' in url_l or 'download only' in desc.lower() or 'download only' in name.lower():
            return None

        size_str = ''
        size_bytes = hints.get('videoSize', 0) or 0
        if size_bytes:
            size_str = self._fmt_size(size_bytes)
        else:
            m = re.search(r'💾\s*([\d.]+\s*(?:GB|MB))', desc, re.I)
            if m:
                size_str = m.group(1).strip()

        haystack = (name + ' ' + desc).upper()
        quality = 'SD'
        quality_rank = 3
        if '4K' in haystack or '2160P' in haystack or 'UHD' in haystack:
            quality = '4K'
            quality_rank = 0
        elif '1080P' in haystack:
            quality = '1080p'
            quality_rank = 1
        elif '720P' in haystack:
            quality = '720p'
            quality_rank = 2

        codec = ''
        if re.search(r'HEVC|x265|H\.265|H265', desc, re.I):
            codec = 'HEVC'
        elif re.search(r'AVC|x264|H\.264|H264', desc, re.I):
            codec = 'AVC'

        desc_l = desc.lower()
        is_hindi = any(k in desc_l for k in ['hin', 'hindi'])
        is_english = any(k in desc_l for k in ['eng', 'english', 'esub'])
        is_multi = any(k in desc_l for k in ['tam', 'tel', 'mal', 'kan', 'multi'])

        if is_english and not is_hindi and not is_multi:
            audio = 'English'
            lang_priority = 0
        elif is_hindi and is_english:
            audio = 'Dual-Audio (Hin/Eng)'
            lang_priority = 1
        elif is_multi:
            audio = 'Multi-Audio'
            lang_priority = 2
        elif is_hindi:
            audio = 'Hindi'
            lang_priority = 3
        else:
            audio = ''
            lang_priority = 4

        fps = ''
        m_fps = re.search(r'(\d+FPS)', haystack)
        if m_fps:
            fps = m_fps.group(1)

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

        if 'pixeldrain' in url_l:
            server = 'PixelDrain'
        elif 'r2.cloudflarestorage' in url_l or '.r2.dev' in url_l:
            server = 'FSL (R2)'
        elif 'drive.google' in url_l or 'googleusercontent' in url_l:
            server = 'Google CDN'
        elif 'telegramcdn' in url_l or 'telegram' in url_l:
            server = 'TG CDN'
        elif 'workers.dev' in url_l:
            server = 'Cloudflare Worker'
        else:
            server = 'Cloud CDN'

        first_line = desc.split('|')[0] if '|' in desc else desc
        raw_clean = re.sub(r'\[.*?\]', '', first_line).strip()
        raw_clean = re.sub(r'^\d+[\s_.-]+', '', raw_clean).strip()
        raw_clean = re.sub(r'\.(?:mkv|mp4)$', '', raw_clean, flags=re.I).strip()
        release_title = raw_clean if len(raw_clean) > 5 else (name.replace('\n', ' ').strip() or 'HDHub Stream')

        release_key = f"{release_title.lower()}_{size_str}"

        return {
            'url': url,
            'quality': quality,
            'quality_rank': quality_rank,
            'lang_priority': lang_priority,
            'codec': codec,
            'audio': audio,
            'fps': fps,
            'hdr': hdr,
            'rtype': rtype,
            'size_str': size_str,
            'server': server,
            'raw_name': name,
            'release_title': release_title,
            'release_key': release_key,
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
        for key in ('server', 'quality', 'codec', 'hdr', 'fps', 'rtype', 'audio', 'size_str'):
            val = parsed.get(key)
            if val:
                parts.append(val)
        return ' | '.join(parts) if parts else 'HdHub'

    def sources(self, url, hostDict):
        try:
            self.results = []
            self._grouped_releases = {}
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

            for s in streams:
                parsed = self._parse_stream(s)
                if not parsed:
                    continue

                clean_url = scrape_sources.prepare_link(parsed['url'])
                if not clean_url:
                    continue
                parsed['url'] = clean_url

                key = parsed['release_key']
                if key not in self._grouped_releases:
                    self._grouped_releases[key] = {
                        'primary': parsed,
                        'mirrors': []
                    }
                else:
                    existing_entry = self._grouped_releases[key]
                    existing_entry['mirrors'].append({
                        'url': clean_url,
                        'name': f"{parsed['server']} ({parsed['quality']})"
                    })

            unfiltered_items = []
            for group in self._grouped_releases.values():
                primary = group['primary']
                mirrors = group['mirrors']

                alt_urls = [m['url'] for m in mirrors]
                alt_names = [m['name'] for m in mirrors]

                all_alt_urls = [primary['url']] + alt_urls
                all_alt_names = [f"{primary['server']} (Primary)"] + alt_names

                item = {
                    'title': primary['release_title'],
                    'source': primary['server'],
                    'quality': primary['quality'],
                    'quality_rank': primary['quality_rank'],
                    'lang_priority': primary['lang_priority'],
                    'info': self._build_info(primary),
                    'url': primary['url'],
                    'direct': True,
                    'is_video': True,
                    'audio': primary['audio'],
                    'languages_display': primary['audio'],
                    'size': primary['size_str'],
                    'size_str': primary['size_str'],
                    'alternative_urls': all_alt_urls if len(all_alt_urls) > 1 else [],
                    'alternative_names': all_alt_names if len(all_alt_names) > 1 else []
                }
                unfiltered_items.append(item)

            unfiltered_items.sort(key=lambda x: (x['lang_priority'], x['quality_rank']))

            self.results = unfiltered_items
            return self.results
        except Exception:
            return self.results

    def resolve(self, url):
        return url
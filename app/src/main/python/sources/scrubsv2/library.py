# -*- coding: utf-8 -*-

import simplejson as json
from six import ensure_str, ensure_text
from six.moves.urllib_parse import urlparse, urlencode, parse_qs

from resources.lib.modules import cleantitle
from resources.lib.modules import control
#from resources.lib.modules import log_utils


class source:
    def __init__(self):
        self.results = []
        self.domains = []
        self.base_link = ''


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'localtitle': localtitle, 'year': year}
        url = urlencode(url)
        return url


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        url = {'imdb': imdb, 'tvshowtitle': tvshowtitle, 'localtvshowtitle': localtvshowtitle, 'year': year}
        url = urlencode(url)
        return url


    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url:
            return
        url = parse_qs(url)
        url = dict([(i, url[i][0]) if url[i] else (i, '') for i in url])
        url.update({'premiered': premiered, 'season': season, 'episode': episode})
        return urlencode(url)


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            content_type = 'episode' if 'tvshowtitle' in data else 'movie'
            years = (data['year'], str(int(data['year'])+1), str(int(data['year'])-1))
            if content_type == 'movie':
                title = cleantitle.get(data['title'])
                localtitle = cleantitle.get(data['localtitle'])
                ids = [data['imdb']]
                r = control.jsonrpc('{"jsonrpc": "2.0", "method": "VideoLibrary.GetMovies", "params": {"filter":{"or": [{"field": "year", "operator": "is", "value": "%s"}, {"field": "year", "operator": "is", "value": "%s"}, {"field": "year", "operator": "is", "value": "%s"}]}, "properties": ["imdbnumber", "title", "originaltitle", "file"]}, "id": 1}' % years)
                r = ensure_text(r, 'utf-8', errors='ignore')
                r = json.loads(r)['result']['movies']
                r = [i for i in r if str(i['imdbnumber']) in ids or any(x in [cleantitle.get(i['title']), cleantitle.get(i['originaltitle'])] for x in [title, localtitle])]
                r = [i for i in r if not ensure_str(i['file']).endswith('.strm')][0]
                r = control.jsonrpc('{"jsonrpc": "2.0", "method": "VideoLibrary.GetMovieDetails", "params": {"properties": ["streamdetails", "file"], "movieid": %s }, "id": 1}' % str(r['movieid']))
                r = ensure_text(r, 'utf-8', errors='ignore')
                r = json.loads(r)['result']['moviedetails']
            elif content_type == 'episode':
                title = cleantitle.get(data['tvshowtitle'])
                localtitle = cleantitle.get(data['localtvshowtitle'])
                season, episode = data['season'], data['episode']
                r = control.jsonrpc('{"jsonrpc": "2.0", "method": "VideoLibrary.GetTVShows", "params": {"filter":{"or": [{"field": "year", "operator": "is", "value": "%s"}, {"field": "year", "operator": "is", "value": "%s"}, {"field": "year", "operator": "is", "value": "%s"}]}, "properties": ["imdbnumber", "title", "originaltitle"]}, "id": 1}' % years)
                r = ensure_text(r, 'utf-8', errors='ignore')
                r = json.loads(r)['result']['tvshows']
                r = [i for i in r if any(x in [cleantitle.get(i['title']), cleantitle.get(i['originaltitle'])] for x in [title, localtitle])][0]
                r = control.jsonrpc('{"jsonrpc": "2.0", "method": "VideoLibrary.GetEpisodes", "params": {"filter":{"and": [{"field": "season", "operator": "is", "value": "%s"}, {"field": "episode", "operator": "is", "value": "%s"}]}, "properties": ["file"], "tvshowid": %s }, "id": 1}' % (str(season), str(episode), str(r['tvshowid'])))
                r = ensure_text(r, 'utf-8', errors='ignore')
                r = json.loads(r)['result']['episodes']
                r = [i for i in r if not ensure_str(i['file']).endswith('.strm')][0]
                r = control.jsonrpc('{"jsonrpc": "2.0", "method": "VideoLibrary.GetEpisodeDetails", "params": {"properties": ["streamdetails", "file"], "episodeid": %s }, "id": 1}' % str(r['episodeid']))
                r = ensure_text(r, 'utf-8', errors='ignore')
                r = json.loads(r)['result']['episodedetails']
            url = ensure_str(r['file'])
            try:
                qual = int(r['streamdetails']['video'][0]['width'])
            except:
                qual = -1
            if qual >= 2160:
                quality = '4k'
            elif 1920 <= qual < 2000:
                quality = '1080p'
            elif 1280 <= qual < 1900:
                quality = '720p'
            elif qual < 1280:
                quality = 'sd'
            info = []
            try:
                f = control.openFile(url) ; s = f.size() ; f.close()
                s = '%.2f GB' % (float(s)/1024/1024/1024)
                info.append(s)
            except:
                pass
            try:
                c = r['streamdetails']['video'][0]['codec']
                if c == 'avc1':
                    c = 'h264'
                info.append(c)
            except:
                pass
            try:
                ac = r['streamdetails']['audio'][0]['codec']
                if ac == 'dca':
                    ac = 'dts'
                if ac == 'dtshd_ma':
                    ac = 'dts-hd ma'
                info.append(ac)
            except:
                pass
            try:
                ach = r['streamdetails']['audio'][0]['channels']
                if ach == 1:
                    ach = 'mono'
                if ach == 2:
                    ach = '2.0'
                if ach == 6:
                    ach = '5.1'
                if ach == 8:
                    ach = '7.1'
                info.append(ach)
            except:
                pass
            info = ' | '.join(info)
            self.results.append({'source': 'Local', 'quality': quality, 'info': info, 'url': url, 'local': True, 'direct': True})
            return self.results
        except:
            #log_utils.log('sources', 1)
            return self.results


    def resolve(self, url):
        return url



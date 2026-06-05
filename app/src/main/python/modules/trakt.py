# -*- coding: utf-8 -*-

import re
import time

import requests

import six
from six.moves import urllib_parse
import simplejson as json

from resources.lib.modules import cache
from resources.lib.modules import cleandate
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import control
from resources.lib.modules import log_utils

if six.PY2:
    str = unicode
elif six.PY3:
    str = unicode = basestring = str

BASE_URL = 'https://api.trakt.tv'
REDIRECT_URI = 'urn:ietf:wg:oauth:2.0:oob'
V2_API_KEY = '234c0be756f91aae005aaf7be2457d2e6f305fb94b6af2c7735a00f0fd64eb60'
CLIENT_SECRET = 'a910d7ef82bbfbb09d8ab1e8ca5f3b0450faf92c734807eec4a267c9e79e9c31'


def getTraktCredentialsInfo():
    user = control.setting('trakt.user').strip()
    token = control.setting('trakt.token')
    refresh = control.setting('trakt.refresh')
    if (user == '' or token == '' or refresh == ''):
        return False
    return True


def __getTraktALT(url, post=None):
    try:
        url = urllib_parse.urljoin(BASE_URL, url) if not url.startswith(BASE_URL) else url
        post = json.dumps(post) if post else None
        headers = {'Content-Type': 'application/json', 'trakt-api-key': V2_API_KEY, 'trakt-api-version': '2'}
        if getTraktCredentialsInfo():
            headers.update({'Authorization': 'Bearer %s' % control.setting('trakt.token')})
        result = client.request(url, post=post, headers=headers, output='extended', error=True)
        result = client_utils.byteify(result)
        resp_code = result[1]
        resp_header = result[2]
        result = result[0]
        if resp_code in ['423', '500', '502', '503', '504', '520', '521', '522', '524']:
            log_utils.log('Trakt Error: %s' % str(resp_code))
            control.infoDialog('Trakt Error: ' + str(resp_code), sound=True)
            return
        elif resp_code in ['429']:
            log_utils.log('Trakt Rate Limit Reached: %s' % str(resp_code))
            control.infoDialog('Trakt Rate Limit Reached: ' + str(resp_code), sound=True)
            return
        elif resp_code in ['404']:
            log_utils.log('Trakt Object Not Found : %s' % str(resp_code))
            return
        if resp_code not in ['401', '405', '403']:
            return result, resp_header
        oauth = urllib_parse.urljoin(BASE_URL, '/oauth/token')
        opost = {'client_id': V2_API_KEY, 'client_secret': CLIENT_SECRET, 'redirect_uri': REDIRECT_URI, 'grant_type': 'refresh_token', 'refresh_token': control.setting('trakt.refresh')}
        result = client.request(oauth, post=json.dumps(opost), headers=headers)
        result = client_utils.json_loads_as_str(result)
        token, refresh = result['access_token'], result['refresh_token']
        control.setSetting(id='trakt.token', value=token)
        control.setSetting(id='trakt.refresh', value=refresh)
        headers['Authorization'] = 'Bearer %s' % token
        result = client.request(url, post=post, headers=headers, output='extended', error=True)
        result = client_utils.byteify(result)
        return result[0], result[2]
    except:
        pass


def __getTrakt(url, post=None):
    try:
        url = urllib_parse.urljoin(BASE_URL, url) if not url.startswith(BASE_URL) else url
        post = json.dumps(post) if post else None
        headers = {'Content-Type': 'application/json', 'trakt-api-key': V2_API_KEY, 'trakt-api-version': '2'}
        if getTraktCredentialsInfo():
            headers.update({'Authorization': 'Bearer %s' % control.setting('trakt.token')})
        if not post:
            r = requests.get(url, headers=headers, timeout=30)
        else:
            r = requests.post(url, data=post, headers=headers, timeout=30)
        r.encoding = 'utf-8'
        resp_code = str(r.status_code)
        resp_header = r.headers
        result = r.text
        if resp_code in ['423', '500', '502', '503', '504', '520', '521', '522', '524']:
            log_utils.log('Trakt Error: %s' % str(resp_code))
            control.infoDialog('Trakt Error: ' + str(resp_code), sound=True)
            return
        elif resp_code in ['429']:
            log_utils.log('Trakt Rate Limit Reached: %s' % str(resp_code))
            control.infoDialog('Trakt Rate Limit Reached: ' + str(resp_code), sound=True)
            return
        elif resp_code in ['404']:
            log_utils.log('Trakt Object Not Found : %s' % str(resp_code))
            return
        if resp_code not in ['401', '405', '403']:
            return result, resp_header
        oauth = urllib_parse.urljoin(BASE_URL, '/oauth/token')
        opost = {'client_id': V2_API_KEY, 'client_secret': CLIENT_SECRET, 'redirect_uri': REDIRECT_URI, 'grant_type': 'refresh_token', 'refresh_token': control.setting('trakt.refresh')}
        result = requests.post(oauth, data=json.dumps(opost), headers=headers, timeout=30).json()
        token, refresh = result['access_token'], result['refresh_token']
        control.setSetting(id='trakt.token', value=token)
        control.setSetting(id='trakt.refresh', value=refresh)
        headers['Authorization'] = 'Bearer %s' % token
        if not post:
            r = requests.get(url, headers=headers, timeout=30)
        else:
            r = requests.post(url, data=post, headers=headers, timeout=30)
        r.encoding = 'utf-8'
        return r.text, r.headers
    except:
        pass


def _released_key(item):
    if 'released' in item:
        return item['released'] or '0'
    elif 'first_aired' in item:
        return item['first_aired'] or '0'
    else:
        return '0'


def sort_list(sort_key, sort_direction, list_data):
    reverse = False if sort_direction == 'asc' else True
    if sort_key == 'rank':
        return sorted(list_data, key=lambda x: x['rank'], reverse=reverse)
    elif sort_key == 'added':
        return sorted(list_data, key=lambda x: x['listed_at'], reverse=reverse)
    elif sort_key == 'title':
        return sorted(list_data, key=lambda x: x[x['type']].get('title'), reverse=reverse)
    elif sort_key == 'released':
        return sorted(list_data, key=lambda x: _released_key(x[x['type']]), reverse=reverse)
    elif sort_key == 'runtime':
        return sorted(list_data, key=lambda x: x[x['type']].get('runtime', 0), reverse=reverse)
    elif sort_key == 'popularity':
        return sorted(list_data, key=lambda x: x[x['type']].get('votes', 0), reverse=reverse)
    elif sort_key == 'percentage':
        return sorted(list_data, key=lambda x: x[x['type']].get('rating', 0), reverse=reverse)
    elif sort_key == 'votes':
        return sorted(list_data, key=lambda x: x[x['type']].get('votes', 0), reverse=reverse)
    else:
        return list_data


def getTraktAsJson(url, post=None):
    try:
        r, res_headers = __getTrakt(url, post)
        r = client_utils.json_loads_as_str(r)
        if 'X-Sort-By' in res_headers and 'X-Sort-How' in res_headers:
            r = sort_list(res_headers['X-Sort-By'], res_headers['X-Sort-How'], r)
        return r
    except:
        pass


def authTrakt():
    try:
        if getTraktCredentialsInfo() == True:
            if control.yesnoDialog('An account already exists.' + '[CR]' + 'Do you want to reset?', heading='Trakt'):
                control.setSetting(id='trakt.user', value='')
                control.setSetting(id='trakt.authed', value='')
                control.setSetting(id='trakt.token', value='')
                control.setSetting(id='trakt.refresh', value='')
            raise Exception()
        result = getTraktAsJson('/oauth/device/code', {'client_id': V2_API_KEY})
        verification_url = six.ensure_text('1) Visit : [COLOR skyblue]%s[/COLOR]' % result['verification_url'])
        user_code = six.ensure_text('2) When prompted enter : [COLOR skyblue]%s[/COLOR]' % result['user_code'])
        expires_in = int(result['expires_in'])
        device_code = result['device_code']
        interval = result['interval']
        progressDialog = control.progressDialog
        progressDialog.create('Trakt')
        for i in range(0, expires_in):
            try:
                percent = int(100 * float(i) / int(expires_in))
                progressDialog.update(max(1, percent), verification_url + '[CR]' + user_code)
                if progressDialog.iscanceled():
                    break
                time.sleep(1)
                if not float(i) % interval == 0:
                    raise Exception()
                r = getTraktAsJson('/oauth/device/token', {'client_id': V2_API_KEY, 'client_secret': CLIENT_SECRET, 'code': device_code})
                if 'access_token' in r:
                    break
            except:
                pass
        try:
            progressDialog.close()
        except:
            pass
        token, refresh = r['access_token'], r['refresh_token']
        headers = {'Content-Type': 'application/json', 'trakt-api-key': V2_API_KEY, 'trakt-api-version': 2, 'Authorization': 'Bearer %s' % token}
        result = client.request(urllib_parse.urljoin(BASE_URL, '/users/me'), headers=headers)
        result = client_utils.json_loads_as_str(result)
        user = result['username']
        authed = '' if user == '' else str('yes')
        control.setSetting(id='trakt.user', value=user)
        control.setSetting(id='trakt.authed', value=authed)
        control.setSetting(id='trakt.token', value=token)
        control.setSetting(id='trakt.refresh', value=refresh)
        raise Exception()
    except:
        control.openSettings(query='2.3')


def getTraktIndicatorsInfo():
    indicators = control.setting('indicators') if getTraktCredentialsInfo() == False else control.setting('indicators.alt')
    indicators = True if indicators == '1' else False
    return indicators


def getTraktAddonMovieInfo():
    try:
        scrobble = control.addon('script.trakt').getSetting('scrobble_movie')
    except:
        scrobble = ''
    try:
        ExcludeHTTP = control.addon('script.trakt').getSetting('ExcludeHTTP')
    except:
        ExcludeHTTP = ''
    try:
        authorization = control.addon('script.trakt').getSetting('authorization')
    except:
        authorization = ''
    if scrobble == 'true' and ExcludeHTTP == 'false' and not authorization == '':
        return True
    else:
        return False


def getTraktAddonEpisodeInfo():
    try:
        scrobble = control.addon('script.trakt').getSetting('scrobble_episode')
    except:
        scrobble = ''
    try:
        ExcludeHTTP = control.addon('script.trakt').getSetting('ExcludeHTTP')
    except:
        ExcludeHTTP = ''
    try:
        authorization = control.addon('script.trakt').getSetting('authorization')
    except:
        authorization = ''
    if scrobble == 'true' and ExcludeHTTP == 'false' and not authorization == '':
        return True
    else:
        return False


def slug(name):
    name = name.strip()
    name = name.lower()
    name = re.sub('[^a-z0-9_]', '-', name)
    name = re.sub('--+', '-', name)
    if name.endswith('-'):
        name = name.rstrip('-')
    return name


def manager(name, imdb, tmdb, content):
    try:
        post = {"movies": [{"ids": {"imdb": imdb}}]} if content == 'movie' else {"shows": [{"ids": {"tmdb": tmdb}}]}
        items = [('Add to [B]Collection[/B]', '/sync/collection')]
        items += [('Remove from [B]Collection[/B]', '/sync/collection/remove')]
        items += [('Add to [B]Watchlist[/B]', '/sync/watchlist')]
        items += [('Remove from [B]Watchlist[/B]', '/sync/watchlist/remove')]
        items += [('Add to [B]new List[/B]', '/users/me/lists/%s/items')]
        result = getTraktAsJson('/users/me/lists')
        lists = [(i['name'], i['ids']['slug']) for i in result]
        lists = [lists[i//2] for i in range(len(lists)*2)]
        for i in range(0, len(lists), 2):
            lists[i] = ((six.ensure_str('Add to [B]%s[/B]' % lists[i][0])), '/users/me/lists/%s/items' % lists[i][1])
        for i in range(1, len(lists), 2):
            lists[i] = ((six.ensure_str('Remove from [B]%s[/B]' % lists[i][0])), '/users/me/lists/%s/items/remove' % lists[i][1])
        items += lists
        select = control.selectDialog([i[0] for i in items], 'Trakt Manager')
        if select == -1:
            return
        elif select == 4:
            t = 'Add to [B]new List[/B]'
            k = control.keyboard('', t) ; k.doModal()
            new = k.getText() if k.isConfirmed() else None
            if (new == None or new == ''):
                return
            result = __getTrakt('/users/me/lists', post={"name": new, "privacy": "private"})[0]
            try:
                slug = client_utils.json_loads_as_str(result)['ids']['slug']
            except:
                return control.infoDialog('Trakt Manager', heading=str(name), sound=True, icon='ERROR')
            result = __getTrakt(items[select][1] % slug, post=post)[0]
        else:
            result = __getTrakt(items[select][1], post=post)[0]
        icon = control.infoLabel('ListItem.Icon') if not result == None else 'ERROR'
        control.infoDialog('Trakt Manager', heading=str(name), sound=True, icon=icon)
    except:
        return


def getActivity():
    try:
        i = getTraktAsJson('/sync/last_activities')
        activity = []
        activity.append(i['movies']['collected_at'])
        activity.append(i['episodes']['collected_at'])
        activity.append(i['movies']['watchlisted_at'])
        activity.append(i['shows']['watchlisted_at'])
        activity.append(i['seasons']['watchlisted_at'])
        activity.append(i['episodes']['watchlisted_at'])
        activity.append(i['lists']['updated_at'])
        activity.append(i['lists']['liked_at'])
        activity = [int(cleandate.iso_2_utc(i)) for i in activity]
        activity = sorted(activity, key=int)[-1]
        return activity
    except:
        pass


def getWatchedActivity():
    try:
        i = getTraktAsJson('/sync/last_activities')
        activity = []
        activity.append(i['movies']['watched_at'])
        activity.append(i['episodes']['watched_at'])
        activity = [int(cleandate.iso_2_utc(i)) for i in activity]
        activity = sorted(activity, key=int)[-1]
        return activity
    except:
        pass


def syncMovies(user):
    try:
        if getTraktCredentialsInfo() == False:
            return
        indicators = getTraktAsJson('/users/me/watched/movies')
        indicators = [i['movie']['ids'] for i in indicators]
        indicators = [str(i['imdb']) for i in indicators if 'imdb' in i]
        return indicators
    except:
        pass


def cachesyncMovies(timeout=0):
    indicators = cache.get(syncMovies, timeout, control.setting('trakt.user').strip())
    return indicators


def timeoutsyncMovies():
    timeout = cache.timeout(syncMovies, control.setting('trakt.user').strip())
    return timeout


def syncTVShows(user):
    try:
        if getTraktCredentialsInfo() == False:
            return
        indicators = getTraktAsJson('/users/me/watched/shows?extended=full')
        indicators = [(i['show']['ids']['tmdb'], i['show']['aired_episodes'], sum([[(s['number'], e['number']) for e in s['episodes']] for s in i['seasons']], [])) for i in indicators]
        indicators = [(str(i[0]), int(i[1]), i[2]) for i in indicators]
        return indicators
    except:
        pass


def cachesyncTVShows(timeout=0):
    indicators = cache.get(syncTVShows, timeout, control.setting('trakt.user').strip())
    return indicators


def timeoutsyncTVShows():
    timeout = cache.timeout(syncTVShows, control.setting('trakt.user').strip())
    if not timeout:
        timeout = 0
    return timeout


def syncSeason(imdb):
    try:
        if getTraktCredentialsInfo() == False:
            return
        indicators = getTraktAsJson('/shows/%s/progress/watched?specials=false&hidden=false' % imdb)
        indicators = indicators['seasons']
        indicators = [(i['number'], [x['completed'] for x in i['episodes']]) for i in indicators]
        indicators = ['%01d' % int(i[0]) for i in indicators if not False in i[1]]
        return indicators
    except:
        pass


def markMovieAsWatched(imdb):
    if not imdb.startswith('tt'):
        imdb = 'tt' + imdb
    return __getTrakt('/sync/history', {"movies": [{"ids": {"imdb": imdb}}]})[0]


def markMovieAsNotWatched(imdb):
    if not imdb.startswith('tt'):
        imdb = 'tt' + imdb
    return __getTrakt('/sync/history/remove', {"movies": [{"ids": {"imdb": imdb}}]})[0]


def markTVShowAsWatched(imdb):
    return __getTrakt('/sync/history', {"shows": [{"ids": {"imdb": imdb}}]})[0]


def markTVShowAsNotWatched(imdb):
    return __getTrakt('/sync/history/remove', {"shows": [{"ids": {"imdb": imdb}}]})[0]


def markEpisodeAsWatched(imdb, season, episode):
    season, episode = int('%01d' % int(season)), int('%01d' % int(episode))
    return __getTrakt('/sync/history', {"shows": [{"seasons": [{"episodes": [{"number": episode}], "number": season}], "ids": {"imdb": imdb}}]})[0]


def markEpisodeAsNotWatched(imdb, season, episode):
    season, episode = int('%01d' % int(season)), int('%01d' % int(episode))
    return __getTrakt('/sync/history/remove', {"shows": [{"seasons": [{"episodes": [{"number": episode}], "number": season}], "ids": {"imdb": imdb}}]})[0]


def getMovieTranslation(id, lang, full=False):
    url = '/movies/%s/translations/%s' % (id, lang)
    try:
        item = getTraktAsJson(url)[0]
        return item if full else item.get('title')
    except:
        pass


def getTVShowTranslation(id, lang, season='', episode='', full=False):
    if season and episode:
        url = '/shows/%s/seasons/%s/episodes/%s/translations/%s' % (id, season, episode, lang)
    else:
        url = '/shows/%s/translations/%s' % (id, lang)
    try:
        item = getTraktAsJson(url)[0]
        return item if full else item.get('title')
    except:
        pass


def getMovieAliases(id):
    try:
        return getTraktAsJson('/movies/%s/aliases' % id)
    except:
        return []


def getTVShowAliases(id):
    try:
        return getTraktAsJson('/shows/%s/aliases' % id)
    except:
        return []


def getMovieSummary(id, full=False):
    try:
        url = '/movies/%s' % id
        if full:
            url += '?extended=full'
        return getTraktAsJson(url)
    except:
        return


def getTVShowSummary(id, full=False):
    try:
        url = '/shows/%s' % id
        if full:
            url += '?extended=full'
        return getTraktAsJson(url)
    except:
        return


def getSeasonsSummary(id, full=False, episodes=False):  #Uses imdb_id, full or episodes but not both.
    try:
        url = '/shows/%s/seasons' % id
        if full:
            url += '?extended=full'
        if episodes:
            url += '?extended=episodes'
        return getTraktAsJson(url)
    except:
        return


def getEpisodeSummary(id, season, episode='', full=False):
    try:
        if not episode:
            url = '/shows/%s/seasons/%s' % (id, season)
            #url += '?translations=en'
        else:
            url = '/shows/%s/seasons/%s/episodes/%s' % (id, season, episode)
        if full:
            url += '?extended=full'
        return getTraktAsJson(url)
    except:
        return


#/shows/game-of-thrones/seasons/1/people
#/shows/game-of-thrones/seasons/1/people?extended=guest_stars

#/shows/game-of-thrones/seasons/1/episodes/1/people
#/shows/game-of-thrones/seasons/1/episodes/1/people?extended=guest_stars


def getPeople(id, content_type, full=False): #Uses imdb_id
    try:
        url = '/%s/%s/people' % (content_type, id)
        if full:
            url += '?extended=full'
        return getTraktAsJson(url)
    except:
        return


def getStudio(id, content_type): #Uses imdb_id
    try:
        url = '/%s/%s/studios' % (content_type, id)
        return getTraktAsJson(url)
    except:
        return


def getGenre(content, type, type_id):
    try:
        r = getTraktAsJson('/search/%s/%s?type=%s&extended=full' % (type, type_id, content))
        return r[0].get(content, {}).get('genres', [])
    except:
        return []


def SearchMovie(title, year='', full=False):
    try:
        url = '/search/movie?query=%s' % urllib_parse.quote_plus(title)
        if year:
            url += '&year=%s' % year
        if full:
            url += '&extended=full'
        return getTraktAsJson(url)
    except:
        return


def SearchTVShow(title, year='', full=False):
    try:
        url = '/search/show?query=%s' % urllib_parse.quote_plus(title)
        if year:
            url += '&year=%s' % year
        if full:
            url += '&extended=full'
        return getTraktAsJson(url)
    except:
        return


def SearchEpisode(title, season, episode, full=False):
    try:
        url = '/search/%s/seasons/%s/episodes/%s' % (title, season, episode)
        if full:
            url += '&extended=full'
        return getTraktAsJson(url)
    except:
        return


def SearchAll(title, year='', full=False):
    try:
        return SearchMovie(title, year, full) + SearchTVShow(title, year, full)
    except:
        return



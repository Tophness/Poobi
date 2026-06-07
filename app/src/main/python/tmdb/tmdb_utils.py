# -*- coding: utf-8 -*-

import requests
from requests.compat import json, str
from modules import control

USERNAME = control.setting('tmdb.user')
PASSWORD = control.setting('tmdb.pass')
SESSION_ID = control.setting('tmdb.session')
ACCOUNT_ID = control.setting('tmdb.id')

API_KEY = control.setting('tmdb.api')
if not API_KEY:
    API_KEY = '55d4ea0acb04a10053c2be637eb707a9'

original_artwork = control.setting('original.artwork') or 'false'
if original_artwork == 'true':
    image_link = 'https://image.tmdb.org/t/p/original'
else:
    image_link = 'https://image.tmdb.org/t/p/w%s%s'

API_URL = 'https://api.themoviedb.org/3'
ART_URL = 'https://image.tmdb.org/t/p/original'
HEADERS = {'Content-Type': 'application/json;charset=utf-8'}


def parse_genres(genres, line=True):
    new_genres = []
    try:
        url = API_URL + '/genre/movie/list?api_key=%s&language=en-US' % API_KEY
        movie_genres = requests.get(url, headers=HEADERS).json().get('genres', [])
        url = API_URL + '/genre/tv/list?api_key=%s&language=en-US' % API_KEY
        tv_genres = requests.get(url, headers=HEADERS).json().get('genres', [])

        all_genres = {str(g['id']): g['name'] for g in movie_genres + tv_genres}
        for genre_id in genres:
            name = all_genres.get(str(genre_id))
            if name: new_genres.append(name)

        return ', '.join(new_genres) if line else new_genres
    except:
        return new_genres


def getTMDbCredentialsInfo():
    if (USERNAME == '' or PASSWORD == '' or SESSION_ID == '' or ACCOUNT_ID == ''):
        return False
    return True


def authTMDb():
    global SESSION_ID, USERNAME, PASSWORD
    # Refresh settings in case they were just updated in UI
    USERNAME = control.setting('tmdb.user')
    PASSWORD = control.setting('tmdb.pass')
    SESSION_ID = control.setting('tmdb.session')

    try:
        if not SESSION_ID == '':
            if control.yesnoDialog('A Session Already Exists.' + '[CR]' + 'Delete and create new session?', heading='TMDB'):
                delete_session()
                # Refresh status after deletion
                SESSION_ID = ''
            else:
                return

        if not USERNAME or not PASSWORD:
            control.infoDialog('Check Account Credentials. Username and Password are required.', sound=True)
            return

        request_token = create_request_token()
        if not request_token:
            raise Exception("Failed to create request token. Check your internet and API key.")

        request_token = create_session_with_login(request_token)
        if not request_token:
            raise Exception("Failed to validate login. Check your TMDb username and password.")

        session_id = create_session(request_token)
        if not session_id:
            raise Exception("Failed to create session.")

        control.setSetting(id='tmdb.session', value=session_id)
        # Log details but don't show annoying OK dialog
        get_account_details(session_id, show_dialog=False)
        control.infoDialog('TMDb Auth Successful.', sound=True)
        return True
    except Exception as e:
        import traceback
        print(f"TMDB Auth Error: {traceback.format_exc()}")
        control.infoDialog('TMDb Auth Failed: %s' % str(e), sound=True)
        return


def create_request_token():
    try:
        url = API_URL + '/authentication/token/new?api_key=%s' % API_KEY
        result = requests.get(url, headers=HEADERS).json()
        if not result.get('success') is True:
            return None
        return result['request_token']
    except:
        return None


def create_session_with_login(request_token):
    try:
        url = API_URL + '/authentication/token/validate_with_login?api_key=%s' % API_KEY
        post = {"username": "%s" % str(USERNAME), "password": "%s" % str(PASSWORD), "request_token": "%s" % str(request_token)}
        result = requests.post(url, data=json.dumps(post), headers=HEADERS).json()
        if not result.get('success') is True:
            return None
        return result['request_token']
    except:
        return None


def create_session(request_token):
    try:
        url = API_URL + '/authentication/session/new?api_key=%s' % API_KEY
        post = {"request_token": "%s" % str(request_token)}
        result = requests.post(url, data=json.dumps(post), headers=HEADERS).json()
        if not result.get('success') is True:
            return None
        return result['session_id']
    except:
        return None


def delete_session():
    global SESSION_ID
    try:
        current_session = control.setting('tmdb.session')
        if current_session == '':
            return
        url = API_URL + '/authentication/session?api_key=%s' % API_KEY
        post = {"session_id": "%s" % str(current_session)}
        requests.delete(url, data=json.dumps(post), headers=HEADERS)
    except:
        pass
    finally:
        control.setSetting(id='tmdb.session', value='')
        control.setSetting(id='tmdb.id', value='')
        SESSION_ID = ''


def get_account_details(session_id, show_dialog=True):
    try:
        url = API_URL + '/account?api_key=%s&session_id=%s' % (API_KEY, session_id)
        result = requests.get(url, headers=HEADERS).json()
        control.setSetting(id='tmdb.id', value=str(result['id']))
        if show_dialog:
            message = ('username: %s' % str(result['username']) + '[CR]' + 'name: %s' % str(result['name']) + '[CR]' + 'id: %s' % str(result['id']))
            return control.okDialog(message, heading='TMDB Account Details')
        return True
    except:
        return False

def get_movie_trailers(tmdb):
    try:
        url = API_URL + '/movie/%s/videos?api_key=%s&language=en-US' % (tmdb, API_KEY)
        return requests.get(url, headers=HEADERS).json().get('results', [])
    except: return []

def get_tvshow_trailers(tmdb):
    try:
        url = API_URL + '/tv/%s/videos?api_key=%s&language=en-US' % (tmdb, API_KEY)
        return requests.get(url, headers=HEADERS).json().get('results', [])
    except: return []

def get_season_trailers(tmdb, season):
    try:
        url = API_URL + '/tv/%s/season/%s/videos?api_key=%s&language=en-US' % (tmdb, season, API_KEY)
        return requests.get(url, headers=HEADERS).json().get('results', [])
    except: return []

def get_episode_trailers(tmdb, season, episode):
    try:
        url = API_URL + '/tv/%s/season/%s/episode/%s/videos?api_key=%s&language=en-US' % (tmdb, season, episode, API_KEY)
        return requests.get(url, headers=HEADERS).json().get('results', [])
    except: return []

def get_tmdb_artwork(tmdb, content, season=None, episode=None):
    try:
        media_type = "movie" if content == "movie" else "tv"
        if season and episode:
            ending = '/%s/%s/season/%s/episode/%s/images?api_key=%s' % (media_type, tmdb, season, episode, API_KEY)
        elif season:
            ending = '/%s/%s/season/%s/images?api_key=%s' % (media_type, tmdb, season, API_KEY)
        else:
            ending = '/%s/%s/images?api_key=%s' % (media_type, tmdb, API_KEY)
        url = API_URL + ending
        result = requests.get(url, headers=HEADERS).json()
        poster = fanart = banner = '0'
        posters = result.get('posters', [])
        if posters:
            posters = [x for x in posters if x.get('iso_639_1') == 'en'] + [x for x in posters if not x.get('iso_639_1') == 'en']
            path = posters[0]['file_path']
            poster = 'https://image.tmdb.org/t/p/original' + path if original_artwork == 'true' else 'https://image.tmdb.org/t/p/w500' + path
        backdrops = result.get('backdrops', [])
        if backdrops:
            backdrops = [x for x in backdrops if x.get('width') == 1920] + [x for x in backdrops if x.get('width') < 1920]
            path = backdrops[0]['file_path']
            fanart = 'https://image.tmdb.org/t/p/original' + path if original_artwork == 'true' else 'https://image.tmdb.org/t/p/w1280' + path
        logos = result.get('logos', [])
        if logos:
            path = logos[0]['file_path']
            banner = 'https://image.tmdb.org/t/p/original' + path
        return poster, fanart, banner
    except:
        return '0', '0', '0'

def get_popular_people(page=1):
    try:
        url = API_URL + 'person/popular?api_key=%s&language=en-US&page=%s' % (API_KEY, page)
        return requests.get(url, headers=HEADERS).json()
    except: return {}

def find_people(query, page=1):
    try:
        url = API_URL + 'search/person?api_key=%s&query=%s&language=en-US&page=%s' % (API_KEY, query, page)
        return requests.get(url, headers=HEADERS).json()
    except: return {}

def get_person_credits(person_id, media_type='combined'):
    try:
        url = API_URL + 'person/%s/%s_credits?api_key=%s&language=en-US' % (person_id, media_type, API_KEY)
        return requests.get(url, headers=HEADERS).json()
    except: return {}

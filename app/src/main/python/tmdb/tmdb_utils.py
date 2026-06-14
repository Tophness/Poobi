# -*- coding: utf-8 -*-

import requests
from requests.compat import json, str
from modules import control

USERNAME = control.setting('tmdb.user')
PASSWORD = control.setting('tmdb.pass')
SESSION_ID = control.setting('tmdb.session')
ACCOUNT_ID = control.setting('tmdb.id')

API_KEY = control.setting('tmdb.api') or '55d4ea0acb04a10053c2be637eb707a9'
READ_TOKEN = 'eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI1NWQ0ZWEwYWNiMDRhMTAwNTNjMmJlNjM3ZWI3MDdhOSIsIm5iZiI6MTc2NTAzMzYyMi4yNzgsInN1YiI6IjY5MzQ0Njk2MGY0YjhlN2QwODA1Y2U3MCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.phA7fC-bjtOcl_BHzESyOU4L__3eAhM1c5Nb4A2VK2A'

API_URL = 'https://api.themoviedb.org/3'

def get_headers():
    return {
        "Authorization": f"Bearer {READ_TOKEN}",
        "Content-Type": "application/json;charset=utf-8"
    }

def get_movies_in_cinemas(page=1):
    url = f"{API_URL}/movie/now_playing?page={page}"
    return requests.get(url, headers=get_headers()).json()

def get_trending(media_type='all', time_window='day', page=1):
    url = f"{API_URL}/trending/{media_type}/{time_window}?page={page}"
    return requests.get(url, headers=get_headers()).json()

def get_popular(media_type='movie', page=1):
    url = f"{API_URL}/{media_type}/popular?page={page}"
    return requests.get(url, headers=get_headers()).json()

def get_details(tmdb_id, media_type='movie'):
    url = f"{API_URL}/{media_type}/{tmdb_id}?append_to_response=credits,videos,images,external_ids,recommendations,release_dates,content_ratings"
    return requests.get(url, headers=get_headers()).json()

def get_tv_next_episode(tmdb_id):
    details = get_details(tmdb_id, 'tv')
    return details.get('next_episode_to_air')

def search(query, media_type='multi', page=1):
    url = f"{API_URL}/search/{media_type}?query={query}&page={page}"
    return requests.get(url, headers=get_headers()).json()

def get_genres(media_type='movie'):
    url = f"{API_URL}/genre/{media_type}/list"
    return requests.get(url, headers=get_headers()).json()

def get_top_rated(media_type='movie', page=1):
    url = f"{API_URL}/{media_type}/top_rated?page={page}"
    return requests.get(url, headers=get_headers()).json()

def get_upcoming_movies(page=1):
    url = f"{API_URL}/movie/upcoming?page={page}"
    return requests.get(url, headers=get_headers()).json()

def get_person_details(person_id):
    url = f"{API_URL}/person/{person_id}?append_to_response=combined_credits,images"
    return requests.get(url, headers=get_headers()).json()

def get_popular_people(page=1):
    url = f"{API_URL}/person/popular?page={page}"
    return requests.get(url, headers=get_headers()).json()

def get_external_ids(tmdb_id, media_type):
    url = f"{API_URL}/{media_type}/{tmdb_id}/external_ids"
    return requests.get(url, headers=get_headers()).json()

def get_tv_episodes(tv_id, season_number):
    url = f"{API_URL}/tv/{tv_id}/season/{season_number}"
    return requests.get(url, headers=get_headers()).json()

def parse_genres(genres, line=True):
    new_genres = []
    try:
        movie_genres = requests.get(f"{API_URL}/genre/movie/list", headers=get_headers()).json().get('genres', [])
        tv_genres = requests.get(f"{API_URL}/genre/tv/list", headers=get_headers()).json().get('genres', [])
        all_genres = {str(g['id']): g['name'] for g in movie_genres + tv_genres}
        for genre_id in genres:
            name = all_genres.get(str(genre_id))
            if name: 
                new_genres.append(name)
        return ', '.join(new_genres) if line else new_genres
    except:
        return new_genres

def getTMDbCredentialsInfo():
    if (USERNAME == '' or PASSWORD == '' or SESSION_ID == '' or ACCOUNT_ID == ''):
        return False
    return True


def authTMDb():
    global SESSION_ID, USERNAME, PASSWORD
    USERNAME = control.setting('tmdb.user')
    PASSWORD = control.setting('tmdb.pass')
    SESSION_ID = control.setting('tmdb.session')

    try:
        if not SESSION_ID == '':
            if control.yesnoDialog('A Session Already Exists.' + '[CR]' + 'Delete and create new session?', heading='TMDB'):
                delete_session()
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
        url = f"{API_URL}/authentication/token/new?api_key={API_KEY}"
        result = requests.get(url).json()
        if not result.get('success') is True:
            return None
        return result['request_token']
    except:
        return None

def create_session_with_login(request_token):
    try:
        url = f"{API_URL}/authentication/token/validate_with_login?api_key={API_KEY}"
        post = {"username": f"{str(USERNAME)}", "password": f"{str(PASSWORD)}", "request_token": f"{str(request_token)}"}
        result = requests.post(url, data=json.dumps(post), headers=get_headers()).json()
        if not result.get('success') is True:
            return None
        return result['request_token']
    except:
        return None

def create_session(request_token):
    try:
        url = f"{API_URL}/authentication/session/new?api_key={API_KEY}"
        post = {"request_token": f"{str(request_token)}"}
        result = requests.post(url, data=json.dumps(post), headers=get_headers()).json()
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
        url = f"{API_URL}/authentication/session?api_key={API_KEY}"
        post = {"session_id": f"{str(current_session)}"}
        requests.delete(url, data=json.dumps(post), headers=get_headers())
    except:
        pass
    finally:
        control.setSetting(id='tmdb.session', value='')
        control.setSetting(id='tmdb.id', value='')
        SESSION_ID = ''

def get_account_details(session_id, show_dialog=True):
    try:
        url = f"{API_URL}/account?session_id={session_id}"
        result = requests.get(url, headers=get_headers()).json()
        control.setSetting(id='tmdb.id', value=str(result['id']))
        if show_dialog:
            message = (f"username: {str(result['username'])}[CR]name: {str(result['name'])}[CR]id: {str(result['id'])}")
            return control.okDialog(message, heading='TMDB Account Details')
        return True
    except:
        return False

def get_movie_trailers(tmdb):
    try:
        url = f"{API_URL}/movie/{tmdb}/videos"
        return requests.get(url, headers=get_headers()).json().get('results', [])
    except: return []

def get_tvshow_trailers(tmdb):
    try:
        url = f"{API_URL}/tv/{tmdb}/videos"
        return requests.get(url, headers=get_headers()).json().get('results', [])
    except: return []

def get_season_trailers(tmdb, season):
    try:
        url = f"{API_URL}/tv/{tmdb}/season/{season}/videos"
        return requests.get(url, headers=get_headers()).json().get('results', [])
    except: return []

def get_episode_trailers(tmdb, season, episode):
    try:
        url = f"{API_URL}/tv/{tmdb}/season/{season}/episode/{episode}/videos"
        return requests.get(url, headers=get_headers()).json().get('results', [])
    except: return []

def get_tmdb_artwork(tmdb, content, season=None, episode=None):
    try:
        original_artwork = control.setting('original.artwork') or 'false'
        media_type = "movie" if content == "movie" else "tv"
        if season and episode:
            ending = f"/tv/{tmdb}/season/{season}/episode/{episode}/images"
        elif season:
            ending = f"/tv/{tmdb}/season/{season}/images"
        else:
            ending = f"/{media_type}/{tmdb}/images"
        url = API_URL + ending
        result = requests.get(url, headers=get_headers()).json()
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
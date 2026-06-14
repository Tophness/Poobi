import requests
import json
from modules import control
from trakt.trakt_auth import BASE_URL, CLIENT_ID

def is_movie_watched(item_json):
    token = control.setting('trakt.token')
    if not token or token == '0':
        return False
    
    try:
        item = json.loads(item_json)
    except Exception:
        return False

    headers = {
        'Content-Type': 'application/json',
        'trakt-api-key': CLIENT_ID,
        'trakt-api-version': '2',
        'Authorization': f'Bearer {token}'
    }
    
    ids = {}
    if item.get('imdb'): ids['imdb'] = item['imdb']
    if item.get('tmdb'): ids['tmdb'] = item['tmdb']
    if not ids.get('tmdb') and item.get('id'): ids['tmdb'] = item['id']
    
    try:
        response = requests.get(f"{BASE_URL}/sync/watched/movies", headers=headers)
        if response.status_code == 200:
            watched_movies = response.json()
            for m in watched_movies:
                m_ids = m.get('movie', {}).get('ids', {})
                if ids.get('imdb') and m_ids.get('imdb') == ids['imdb']:
                    return True
                if ids.get('tmdb') and str(m_ids.get('tmdb')) == str(ids['tmdb']):
                    return True
        return False
    except Exception:
        return False
import requests
from modules import control

BASE_URL = 'https://api.trakt.tv'
CLIENT_ID = 'ef0b7f6f8b8806a5df10e37bae8d5487b9cbec20e424a6527c1f3020cfa363a7'

def scrobble(item_json, season=None, episode=None, progress=100.0):
    token = control.setting('trakt.token')
    if not token or token == '0':
        return False
    
    import json
    item = json.loads(item_json)
    
    headers = {
        'Content-Type': 'application/json',
        'trakt-api-key': CLIENT_ID,
        'trakt-api-version': '2',
        'Authorization': f'Bearer {token}'
    }
    
    payload = {
        "progress": progress,
        "app_version": "1.0",
        "app_date": "2024-08-01"
    }
    
    ids = {}
    if 'imdb' in item and item['imdb']: ids['imdb'] = item['imdb']
    if 'tmdb' in item and item['tmdb']: ids['tmdb'] = item['tmdb']
    
    # Also check 'id' as it might be TMDB ID
    if 'id' in item and not ids.get('tmdb'):
        ids['tmdb'] = item['id']

    if season is not None and episode is not None:
        payload["show"] = {"ids": ids}
        payload["episode"] = {"number": episode, "season": season}
        url = f"{BASE_URL}/scrobble/stop" # Mark as finished
    else:
        payload["movie"] = {"ids": ids}
        url = f"{BASE_URL}/scrobble/stop"
        
    try:
        response = requests.post(url, json=payload, headers=headers)
        return response.status_code in [201, 204, 200]
    except:
        return False

def add_to_history(item_json, season=None, episode=None):
    # Simple sync/history add
    token = control.setting('trakt.token')
    if not token or token == '0':
        return False
    
    import json
    item = json.loads(item_json)
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

    data = {}
    if season is not None and episode is not None:
        data["shows"] = [{
            "ids": ids,
            "seasons": [{
                "number": season,
                "episodes": [{"number": episode}]
            }]
        }]
    else:
        data["movies"] = [{"ids": ids}]
        
    try:
        response = requests.post(f"{BASE_URL}/sync/history", json=data, headers=headers)
        return response.status_code == 201
    except:
        return False

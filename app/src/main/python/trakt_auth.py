import time
import requests
import json
from modules import control

BASE_URL = 'https://api.trakt.tv'
CLIENT_ID = 'ef0b7f6f8b8806a5df10e37bae8d5487b9cbec20e424a6527c1f3020cfa363a7'
CLIENT_SECRET = 'ad86f5aef36c5a08039a6f4c8f24ca018ffd7d45fb867cd9bf8c2bd675d3de79'

def get_device_code():
    url = f"{BASE_URL}/oauth/device/code"
    payload = {"client_id": CLIENT_ID}
    response = requests.post(url, json=payload)
    return response.json()

def poll_for_token(device_code):
    url = f"{BASE_URL}/oauth/device/token"
    payload = {
        "code": device_code,
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET
    }
    
    response = requests.post(url, json=payload)
    if response.status_code == 200:
        data = response.json()
        token = data.get("access_token")
        refresh = data.get("refresh_token")
        
        # Get username
        headers = {
            'Content-Type': 'application/json',
            'trakt-api-key': CLIENT_ID,
            'trakt-api-version': '2',
            'Authorization': f'Bearer {token}'
        }
        user_data = requests.get(f"{BASE_URL}/users/me", headers=headers).json()
        username = user_data.get("username")
        
        control.setSetting('trakt.token', token)
        control.setSetting('trakt.refresh', refresh)
        control.setSetting('trakt.user', username)
        control.setSetting('trakt.authed', 'yes')
        
        return {"status": "success", "username": username}
    elif response.status_code == 400:
        return {"status": "pending"}
    else:
        return {"status": "error", "message": response.text}

def get_trakt_username():
    return control.setting('trakt.user')

def logout_trakt():
    control.setSetting('trakt.token', '')
    control.setSetting('trakt.refresh', '')
    control.setSetting('trakt.user', '')
    control.setSetting('trakt.authed', '')

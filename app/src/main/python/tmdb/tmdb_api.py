import requests
import json
from datetime import datetime

API_KEY = '55d4ea0acb04a10053c2be637eb707a9'
READ_TOKEN = 'eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI1NWQ0ZWEwYWNiMDRhMTAwNTNjMmJlNjM3ZWI3MDdhOSIsIm5iZiI6MTc2NTAzMzYyMi4yNzgsInN1YiI6IjY5MzQ0Njk2MGY0YjhlN2QwODA1Y2U3MCIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.phA7fC-bjtOcl_BHzESyOU4L__3eAhM1c5Nb4A2VK2A'
BASE_URL = 'https://api.themoviedb.org/3'

def get_headers():
    return {
        "Authorization": f"Bearer {READ_TOKEN}",
        "Content-Type": "application/json;charset=utf-8"
    }

def get_movies_in_cinemas(page=1):
    url = f"{BASE_URL}/movie/now_playing?page={page}"
    return requests.get(url, headers=get_headers()).json()

def get_trending(media_type='all', time_window='day', page=1):
    url = f"{BASE_URL}/trending/{media_type}/{time_window}?page={page}"
    return requests.get(url, headers=get_headers()).json()

def get_popular(media_type='movie', page=1):
    url = f"{BASE_URL}/{media_type}/popular?page={page}"
    return requests.get(url, headers=get_headers()).json()

def get_details(tmdb_id, media_type='movie'):
    # media_type can be 'movie' or 'tv'
    url = f"{BASE_URL}/{media_type}/{tmdb_id}?append_to_response=credits,videos,images,external_ids,recommendations,release_dates,content_ratings"
    return requests.get(url, headers=get_headers()).json()

def get_tv_next_episode(tmdb_id):
    details = get_details(tmdb_id, 'tv')
    return details.get('next_episode_to_air')

def search(query, media_type='multi', page=1):
    url = f"{BASE_URL}/search/{media_type}?query={query}&page={page}"
    return requests.get(url, headers=get_headers()).json()

def get_genres(media_type='movie'):
    url = f"{BASE_URL}/genre/{media_type}/list"
    return requests.get(url, headers=get_headers()).json()

def get_top_rated(media_type='movie', page=1):
    url = f"{BASE_URL}/{media_type}/top_rated?page={page}"
    return requests.get(url, headers=get_headers()).json()

def get_upcoming_movies(page=1):
    url = f"{BASE_URL}/movie/upcoming?page={page}"
    return requests.get(url, headers=get_headers()).json()

def get_person_details(person_id):
    url = f"{BASE_URL}/person/{person_id}?append_to_response=combined_credits,images"
    return requests.get(url, headers=get_headers()).json()

def get_popular_people(page=1):
    url = f"{BASE_URL}/person/popular?page={page}"
    return requests.get(url, headers=get_headers()).json()

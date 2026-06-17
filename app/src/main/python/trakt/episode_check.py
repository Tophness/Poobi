import os
import sys
import json
import requests
import time
from datetime import datetime, timedelta
from modules import control
from trakt.trakt_auth import CLIENT_ID, BASE_URL

try:
    from com.chaquo.python import Python
    python_context = Python.getPlatform().getApplication()
    FILES_DIR = str(python_context.getFilesDir())
except:
    FILES_DIR = "."
USERDATA_PATH = os.path.realpath(os.path.join(FILES_DIR, 'userdata'))
PROGRESS_CACHE_FILE = os.path.join(USERDATA_PATH, 'trakt_progress_cache.json')

def check_new_episodes(favorites_json, last_check_json):
    favorites = json.loads(favorites_json)
    last_check = json.loads(last_check_json)

    new_episodes = []
    updated_last_check = last_check.copy()

    now = datetime.utcnow()
    start_date = (now - timedelta(days=7)).strftime('%Y-%m-%d')
    
    token = control.setting('trakt.token')
    headers = {
        'Content-Type': 'application/json',
        'trakt-api-version': '2',
        'trakt-api-key': CLIENT_ID
    }
    
    is_authed = token and token != '0' and control.setting('trakt.authed') == 'yes'
    if is_authed:
        headers['Authorization'] = f'Bearer {token}'
        url = f"{BASE_URL}/calendars/my/shows/{start_date}/14"
    else:
        url = f"{BASE_URL}/calendars/all/shows/{start_date}/14"

    try:
        response = requests.get(url, headers=headers, timeout=10)
        if response.status_code == 200:
            calendar_items = response.json()
            fav_map = {str(item.get('id')): item for item in favorites if item.get('media_type') == 'tv'}

            for entry in calendar_items:
                show = entry.get('show', {})
                show_ids = show.get('ids', {})
                tmdb_id = str(show_ids.get('tmdb') or '')
                
                if tmdb_id in fav_map:
                    item = fav_map[tmdb_id]
                    show_title = item.get('title') or show.get('title') or "Unknown Show"
                    episode = entry.get('episode', {})
                    ep_id = str(episode.get('ids', {}).get('trakt') or '')
                    ep_season = episode.get('season')
                    ep_number = episode.get('number')
                    ep_title = episode.get('title')
                    
                    first_aired_str = entry.get('first_aired')
                    if first_aired_str:
                        clean_date = first_aired_str.replace('Z', '').split('.')[0]
                        try:
                            dt = datetime.strptime(clean_date, '%Y-%m-%dT%H:%M:%S')
                            is_past = dt <= datetime.utcnow()
                            last_seen_ep_id = last_check.get(tmdb_id)
                            
                            if is_past:
                                if ep_id != last_seen_ep_id:
                                    new_episodes.append({
                                        "show_id": tmdb_id,
                                        "show_title": show_title,
                                        "episode_name": ep_title,
                                        "season": ep_season,
                                        "number": ep_number,
                                        "airdate": first_aired_str[:10],
                                        "episode_id": ep_id,
                                        "item": item
                                    })
                                    updated_last_check[tmdb_id] = ep_id
                        except Exception as ex:
                            print(f"[DEBUG] check_new_episodes: Date parse error: {ex}")
        else:
            print(f"[DEBUG] check_new_episodes: Error reading Trakt Calendar response: {response.text}")
    except Exception as e:
        print(f"[DEBUG] check_new_episodes exception occurred: {e}")
        
    return json.dumps({
        "new_episodes": new_episodes,
        "last_check": updated_last_check
    })

def get_all_shows_progress(favorites_json):
    favorites = json.loads(favorites_json)
    
    token = control.setting('trakt.token')
    if not token or token == '0' or control.setting('trakt.authed') != 'yes':
        return json.dumps({})
        
    headers = {
        'Content-Type': 'application/json',
        'trakt-api-version': '2',
        'trakt-api-key': CLIENT_ID,
        'Authorization': f'Bearer {token}'
    }

    cache = {}
    if os.path.exists(PROGRESS_CACHE_FILE):
        try:
            with open(PROGRESS_CACHE_FILE, 'r') as f:
                cache = json.load(f)
        except:
            pass

    current_time = time.time()
    CACHE_EXPIRY = 900
    
    updated_cache = {}
    results = {}
    fav_ids = set()
    
    for item in favorites:
        if item.get('media_type') != 'tv':
            continue
            
        tmdb_id = str(item.get('id'))
        show_title = item.get('title') or item.get('name') or "Unknown Show"
        fav_ids.add(tmdb_id)

        imdb_id = item.get('imdb') or item.get('imdb_id')

        if not imdb_id or imdb_id == 'null':
            results[tmdb_id] = {"unwatched_total": 0, "newer_unwatched": 0}
            continue

        cached_entry = cache.get(tmdb_id)
        if cached_entry and (current_time - cached_entry.get('timestamp', 0) < CACHE_EXPIRY):
            results[tmdb_id] = {
                "unwatched_total": cached_entry.get('unwatched_total', 0),
                "newer_unwatched": cached_entry.get('newer_unwatched', 0)
            }
            updated_cache[tmdb_id] = cached_entry
        else:
            try:
                url = f"{BASE_URL}/shows/{imdb_id}/progress/watched"
                response = requests.get(url, headers=headers, timeout=8)
                
                if response.status_code == 200:
                    progress_data = response.json()
                    
                    aired = progress_data.get('aired', 0)
                    completed = progress_data.get('completed', 0)
                    unwatched_total = max(0, aired - completed)
                    
                    last_episode = progress_data.get('last_episode')
                    
                    newer_unwatched = 0
                    if last_episode:
                        last_season = last_episode.get('season', 0)
                        last_number = last_episode.get('number', 0)
                        
                        for s in progress_data.get('seasons', []):
                            s_num = s.get('number', 0)
                            if s_num == 0:
                                continue
                            for ep in s.get('episodes', []):
                                ep_num = ep.get('number', 0)
                                is_newer = (s_num > last_season) or (s_num == last_season and ep_num > last_number)
                                if is_newer:
                                    newer_unwatched += 1
                        else:
                            newer_unwatched = unwatched_total

                        results[tmdb_id] = {
                            "unwatched_total": unwatched_total,
                            "newer_unwatched": newer_unwatched
                        }
                        updated_cache[tmdb_id] = {
                            "unwatched_total": unwatched_total,
                            "newer_unwatched": newer_unwatched,
                            "timestamp": current_time
                        }
                    else:
                        if cached_entry:
                            results[tmdb_id] = {
                                "unwatched_total": cached_entry.get('unwatched_total', 0),
                                "newer_unwatched": cached_entry.get('newer_unwatched', 0)
                            }
                            updated_cache[tmdb_id] = cached_entry
                        else:
                            results[tmdb_id] = {"unwatched_total": 0, "newer_unwatched": 0}
            except Exception as e:
                print(f"[DEBUG] Trakt progress fetch exception for {tmdb_id}: {e}")
                if cached_entry:
                    results[tmdb_id] = {
                        "unwatched_total": cached_entry.get('unwatched_total', 0),
                        "newer_unwatched": cached_entry.get('newer_unwatched', 0)
                    }
                    updated_cache[tmdb_id] = cached_entry
                else:
                    results[tmdb_id] = {"unwatched_total": 0, "newer_unwatched": 0}

        cleaned_cache = {k: v for k, v in updated_cache.items() if k in fav_ids}
        
        try:
            with open(PROGRESS_CACHE_FILE, 'w') as f:
                json.dump(cleaned_cache, f, indent=4)
        except Exception as e:
            print(f"[DEBUG] Failed saving progress cache file: {e}")
            
        return json.dumps(results)

def clear_trakt_module_cache_for_item(tmdb_id):
    try:
        import sqlite3
        tmdb_str = str(tmdb_id)
        for root, dirs, files in os.walk(USERDATA_PATH):
            for file in files:
                if file.endswith('.db') or file.endswith('.sqlite'):
                    db_path = os.path.join(root, file)
                    try:
                        conn = sqlite3.connect(db_path)
                        cursor = conn.cursor()
                        cursor.execute("SELECT name FROM sqlite_master WHERE type='table';")
                        tables = [row[0] for row in cursor.fetchall()]
                        
                        for table in tables:
                            table_lower = table.lower()
                            if any(k in table_lower for k in ['trakt', 'watched', 'sync', 'cache']):
                                cursor.execute(f"PRAGMA table_info({table});")
                                columns = [row[1].lower() for row in cursor.fetchall()]
                                
                                deleted = False
                                for col in columns:
                                    if col in ['tmdb', 'tmdb_id', 'show_id', 'id']:
                                        cursor.execute(f"DELETE FROM {table} WHERE {col} = ? OR {col} = ?", (tmdb_str, int(tmdb_str) if tmdb_str.isdigit() else tmdb_str))
                                        print(f"[DEBUG] clear_trakt_module_cache_for_item: Wiped matching ID rows in '{table}' ({col} matching {tmdb_str})")
                                        deleted = True
                                    elif col in ['key', 'cache_id']:
                                        cursor.execute(f"DELETE FROM {table} WHERE {col} LIKE ? OR {col} LIKE ?", (f"%{tmdb_str}%", f"%{tmdb_str}%"))
                                        print(f"[DEBUG] clear_trakt_module_cache_for_item: Wiped matching wildcard key rows in '{table}' ({col} matching %{tmdb_str}%)")
                                        deleted = True

                                if not deleted:
                                    for col in columns:
                                        try:
                                            cursor.execute(f"DELETE FROM {table} WHERE CAST({col} AS TEXT) = ?", (tmdb_str,))
                                        except:
                                            pass
                        conn.commit()
                        conn.close()
                    except Exception as e:
                        print(f"[DEBUG] Failed to clear DB {file}: {e}")
    except Exception as e:
        print(f"[DEBUG] clear_trakt_module_cache_for_item error: {e}")

def invalidate_show_progress_cache(tmdb_id):
    if os.path.exists(PROGRESS_CACHE_FILE):
        try:
            with open(PROGRESS_CACHE_FILE, 'r') as f:
                cache = json.load(f)
            if str(tmdb_id) in cache:
                del cache[str(tmdb_id)]
                with open(PROGRESS_CACHE_FILE, 'w') as f:
                    json.dump(cache, f, indent=4)
        except Exception as e:
            print(f"[DEBUG] Failed to invalidate cache entry: {e}")
            
    clear_trakt_module_cache_for_item(tmdb_id)

def get_watched_status(tmdb_id, season, episodes_json):
    episodes = json.loads(episodes_json)
    if control.setting('trakt.authed') != 'yes':
        return json.dumps([False] * len(episodes))

    from modules import trakt
    try:
        watched_shows = trakt.syncTVShows(control.setting('trakt.user'))
        show_watched = None
        for s in watched_shows:
            if str(s[0]) == str(tmdb_id):
                show_watched = s[2]
                break

        if not show_watched:
            return json.dumps([False] * len(episodes))

        results = []
        for ep in episodes:
            ep_num = ep.get('episode_number')
            is_watched = (int(season), int(ep_num)) in show_watched
            results.append(is_watched)
        return json.dumps(results)
    except Exception as e:
        return json.dumps([False] * len(episodes))
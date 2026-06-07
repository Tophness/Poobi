import requests
import json
from datetime import datetime
from modules import control
from tmdb import tmdb_api

def check_new_episodes(favorites_json, last_check_json):
    favorites = json.loads(favorites_json)
    last_check = json.loads(last_check_json) # dict: show_id -> last_aired_ep_id

    new_episodes = []
    updated_last_check = last_check.copy()

    for item in favorites:
        if item.get('media_type') != 'tv':
            continue
            
        tmdb_id = str(item.get('id'))
        
        try:
            # Get show details to find last aired episode
            details = tmdb_api.get_details(tmdb_id, 'tv')
            last_ep = details.get('last_episode_to_air')

            if last_ep:
                ep_id = str(last_ep.get('id'))
                last_seen_ep_id = last_check.get(tmdb_id)
                
                if ep_id != last_seen_ep_id:
                    # It's a new episode since we last checked
                    airdate = last_ep.get('air_date')
                    if airdate:
                        dt = datetime.strptime(airdate, '%Y-%m-%d')
                        # Only notify if it aired recently (e.g. last 30 days) to avoid spamming old shows
                        if (datetime.now() - dt).days <= 30:
                            new_episodes.append({
                                "show_id": tmdb_id,
                                "show_title": item.get('title'),
                                "episode_name": last_ep.get('name'),
                                "season": last_ep.get('season_number'),
                                "number": last_ep.get('episode_number'),
                                "airdate": airdate,
                                "episode_id": ep_id,
                                "item": item
                            })
                    updated_last_check[tmdb_id] = ep_id
        except:
            pass
            
    return json.dumps({
        "new_episodes": new_episodes,
        "last_check": updated_last_check
    })

def get_watched_status(tmdb_id, season, episodes_json):
    episodes = json.loads(episodes_json)
    if control.setting('trakt.authed') != 'yes':
        return json.dumps([False] * len(episodes))

    from modules import trakt
    try:
        watched_shows = trakt.syncTVShows(control.setting('trakt.user'))
        # watched_shows is list of (tmdb_id, aired_count, [(s, e), ...])

        show_watched = None
        for s in watched_shows:
            if str(s[0]) == str(tmdb_id):
                show_watched = s[2] # List of (s, e)
                break

        if not show_watched:
            return json.dumps([False] * len(episodes))

        results = []
        for ep in episodes:
            ep_num = ep.get('episode_number')
            is_watched = (int(season), int(ep_num)) in show_watched
            results.append(is_watched)
        return json.dumps(results)
    except:
        return json.dumps([False] * len(episodes))

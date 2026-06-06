import requests
import json
from datetime import datetime
from modules import control

TMDB_API_KEY = 'YOUR_TMDB_API_KEY_HERE' # Should probably get it from somewhere else
# Actually, scrubsv2 uses its own methods.

def check_new_episodes(favorites_json):
    favorites = json.loads(favorites_json)
    new_episodes = []
    
    # We can use TMDB or TVMaze to check for new episodes
    # TVMaze is free and doesn't always need an API key for simple checks
    
    for item in favorites:
        if item.get('media_type') != 'tv':
            continue
            
        show_id = item.get('id')
        imdb_id = item.get('imdb')
        
        # Try TVMaze lookup
        try:
            if imdb_id:
                url = f"https://api.tvmaze.com/lookup/shows?imdb={imdb_id}"
            else:
                title = item.get('title')
                url = f"https://api.tvmaze.com/singlesearch/shows?q={title}"
                
            response = requests.get(url)
            if response.status_code == 200:
                show_data = response.json()
                show_maze_id = show_data.get('id')
                
                # Get latest episode
                ep_url = f"https://api.tvmaze.com/shows/{show_maze_id}/episodes"
                ep_response = requests.get(ep_url)
                if ep_response.status_code == 200:
                    episodes = ep_response.json()
                    if episodes:
                        latest = episodes[-1]
                        airdate = latest.get('airdate')
                        if airdate:
                            dt = datetime.strptime(airdate, '%Y-%m-%d')
                            # If it aired in the last 7 days
                            if (datetime.now() - dt).days <= 7 and (datetime.now() - dt).days >= 0:
                                new_episodes.append({
                                    "show_title": item.get('title'),
                                    "episode_name": latest.get('name'),
                                    "season": latest.get('season'),
                                    "number": latest.get('number'),
                                    "airdate": airdate,
                                    "item": item
                                })
        except:
            pass
            
    return json.dumps(new_episodes)

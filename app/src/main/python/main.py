import os
import sys
import json
import time
import requests
import traceback
import threading
import importlib.util
import types
import inspect
import re

try:
    from com.chaquo.python import Python
    python_context = Python.getPlatform().getApplication()
    FILES_DIR = str(python_context.getFilesDir())
except:
    FILES_DIR = "."

# Define function to get path at runtime
def get_path(pkg):
    try:
        import importlib.util
        spec = importlib.util.find_spec(pkg)
        if spec and spec.origin:
            return os.path.dirname(spec.origin)
    except: pass
    return None

# Use the discovered path or a direct construction if spec fails
SOURCES_PATH = get_path("sources") or os.path.join(FILES_DIR, "chaquopy/AssetFinder/app/sources")
MODULES_PATH = get_path("modules") or os.path.join(FILES_DIR, "chaquopy/AssetFinder/app/modules")
RESOLVEURL_PATH = get_path("resolveurl") or os.path.join(FILES_DIR, "chaquopy/AssetFinder/app/resolveurl")

PROJECT_ROOT = os.path.dirname(SOURCES_PATH)
# If for some reason SOURCES_PATH is still wrong, hardcode the one we know worked
if not os.path.exists(SOURCES_PATH):
    # Try one more variation seen in error messages
    alt_root = "/data/data/com.poobi.tvbrowser/files/chaquopy/AssetFinder/app"
    if os.path.exists(alt_root):
        PROJECT_ROOT = alt_root
    else:
        PROJECT_ROOT = "/data/user/0/com.poobi.tvbrowser/files/chaquopy/AssetFinder/app"

    SOURCES_PATH = os.path.join(PROJECT_ROOT, "sources")
    MODULES_PATH = os.path.join(PROJECT_ROOT, "modules")
    RESOLVEURL_PATH = os.path.join(PROJECT_ROOT, "resolveurl")
USERDATA_PATH = os.path.join(FILES_DIR, 'userdata')
CONFIG_FILE = os.path.join(USERDATA_PATH, 'config.json')

if not os.path.exists(USERDATA_PATH): os.makedirs(USERDATA_PATH)

# Add relevant paths to sys.path
# We only add PROJECT_ROOT. Adding RESOLVEURL_PATH or SUBTITLES_PATH directly 
# causes "lib" namespace collisions because both contain a "lib" folder.
for path in [PROJECT_ROOT, MODULES_PATH, RESOLVEURL_PATH]:
    if path and path not in sys.path:
        sys.path.append(path)

# Patch subtitles manager to use writable FILES_DIR and avoid metadata permission errors on Android
try:
    import subtitles.manager as sub_manager
    sub_manager.PROJECT_ROOT = FILES_DIR
    import shutil
    # Android blocks copystat/copy2 on some filesystems; standard copy is safer
    sub_manager.shutil.copy2 = shutil.copy
except Exception as e:
    print(f"Subtitle patching failed: {e}")

sys.modules['resources'] = types.ModuleType('resources')
sys.modules['resources.lib'] = types.ModuleType('resources.lib')
try:
    import modules
    sys.modules['resources.lib.modules'] = modules
except ImportError:
    pass

sys.argv[:] = ['plugin://plugin.video.universal/', '1', '']

try:
    import resolveurl
except ImportError:
    resolveurl = None

DEFAULT_WHITELIST = [
    'vidsrc.me', '2embed.me', 'vidsrc.to', 'vidlink.org', 'vidsrc.mov', 
    '2embed.ru', '2embed.cc', 'goload.io', 'goload.pro', 'vidembed.cc',
    'vidcloud9.com', 'voxzer.org', 'ronemo.com', 'streamembed.net',
    'databasegdriveplayer.co', 'bnwmovies.com', 'levidia.ch', 'mp4hydra.top',
    'vidsrc.fyi', 'vidrock.net', 'vidnest.fun', 'vidking.net',
    'vidfast.pro', 'vidup.to', 'videasy.net', '111movies.com',
    'multiembed.mov', 'superflixapi.co', 'peachify.top', 'gdriveplayer.us',
    'gomo.to', 'vsembed.ru', 'cloudnestra.com', 'putgate.org', 'goodstream.cc',
    'stigstream.ru', 'linkbin.me', 'hlspanel.xyz', 'furher.in', 'playerhost.net',
    'gomostream.com', 'gomoplayer.com', 'database.gdriveplayer.co', 'database.gdriveplayer.io',
    'database.gdriveplayer.me', 'database.gdriveplayer.us', 'database.gdriveplayer.xyz',
    'downloads-anymovies.co', 'downloads-anymovies.com', 'mp4hydra.org', 'mp4hydra.info',
    'naijavault.com', 'seriezloaded.com.ng', 'stagatv.com', 'tvseries.in', 'mobiletvshows.site',
    'fzmovies.live'
]

def gather_provider_pack_hosts():
    hosts = set()
    if not os.path.exists(SOURCES_PATH):
        return []
    for pack in os.listdir(SOURCES_PATH):
        pack_dir = os.path.join(SOURCES_PATH, pack)
        if not os.path.isdir(pack_dir):
            continue
        for file in os.listdir(pack_dir):
            if not (file.endswith('.py') and not file.startswith('__')):
                continue
            filepath = os.path.join(pack_dir, file)
            try:
                with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
                    content = f.read()
                matches = re.findall(r"self\.domains\s*=\s*\[([^\]]*)\]", content, re.DOTALL)
                for list_content in matches:
                    found = re.findall(r"['\"]([^'\"]+)['\"]", list_content)
                    for d in found:
                        if d and '*' not in d:
                            hosts.add(d.lower())
            except Exception:
                pass
    return sorted(list(hosts))

def get_default_whitelist():
    hosts = list(DEFAULT_WHITELIST)
    if resolveurl and hasattr(resolveurl, 'relevant_resolvers'):
        try:
            resolvers = resolveurl.relevant_resolvers(order_matters=True)
            for r in resolvers:
                if not '*' in r.domains:
                    for d in r.domains:
                        d_low = d.lower()
                        if d_low not in hosts:
                            hosts.append(d_low)
        except: pass
    for h in gather_provider_pack_hosts():
        if h not in hosts:
            hosts.append(h)
    return sorted(hosts)

def load_config():
    default_config = {
        "timeout_mode": "Both",
        "global_timeout": 30,
        "per_source_timeout": 15,
        "use_only_whitelisted_hosts": True,
        "whitelisted_hosts": get_default_whitelist(),
        "subtitles_languages": "English",
        "subtitles_limit": 20,
        "addic7ed_enabled": True,
        "bsplayer_enabled": False,
        "opensubtitles_enabled": True,
        "opensubtitles_org_enabled": False,
        "podnadpisi_enabled": False,
        "subdl_enabled": True,
        "subsource_enabled": True,
        "opensubtitles_username": "",
        "opensubtitles_password": "",
        "opensubtitles_org_username": "",
        "opensubtitles_org_password": "",
        "subdl_apikey": "",
        "subsource_apikey": "",
        "sub_retention_days": 3,
        "up_next_popup_pref": "Ask",
        "autoplay_next_pref": "Closest Source"
    }
    
    # Initialize all detected packs as enabled by default in the base config
    try:
        if os.path.exists(SOURCES_PATH):
            for d in os.listdir(SOURCES_PATH):
                if os.path.isdir(os.path.join(SOURCES_PATH, d)):
                    default_config[f"pack_{d}"] = True
    except: pass

    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, 'r') as f:
                user_cfg = json.load(f)
                default_config.update(user_cfg)
        except Exception: pass
    
    # Subtitle purging
    try:
        import subtitles.manager as sub_manager
        sub_manager.purge_old_subtitles(default_config.get('sub_retention_days', 3))
    except: pass

    return default_config

def save_config(cfg):
    with open(CONFIG_FILE, 'w') as f:
        json.dump(cfg, f, indent=4)

GLOBAL_CONFIG = load_config()

class UniversalScraper:
    def __init__(self, enabled_packs):
        self.enabled_packs = enabled_packs
        self.sources = []
        self.provider_instances = {}
        self.status = {"total": 0, "current": 0, "message": "Initializing...", "timeout": 0}
        self.stop_event = threading.Event()
        self.pause_event = threading.Event()
        self.pause_event.set()

        cfg = GLOBAL_CONFIG
        use_only = cfg.get("use_only_whitelisted_hosts", True)

        if use_only:
            self.hostDict = cfg.get("whitelisted_hosts", [])
            self.hostDict = list(set([h.lower() for h in self.hostDict]))
        else:
            # When whitelist is OFF, we use an empty list instead of None.
            # This prevents crashes in source files that do 'hostDict.append()'
            # while 'source_utils.is_host_valid' is updated to treat empty as 'allow all'.
            self.hostDict = []

        # Subset of the whitelist that corresponds to provider-pack source domains
        # (e.g. 'freeprojecttv.cyou'). Used to skip entire providers whose
        # self.domains are not in the whitelist. Only meaningful when hostDict
        # is non-empty (i.e. the whitelist is active).
        provider_set = set(gather_provider_pack_hosts())
        self.provider_hosts = [h for h in self.hostDict if h in provider_set]

    def getSources(self, title, year, imdb, tmdb, tvdb='0', season=None, episode=None, tvshowtitle=None, premiered='0'):
        providers = []

        for pack in self.enabled_packs:
            pack_dir = os.path.join(SOURCES_PATH, pack)
            if not os.path.exists(pack_dir): 
                continue
            
            files = os.listdir(pack_dir)
            for file in files:
                if file.endswith('.py') and not file.startswith('__'):
                    mod_name = f"sources.{pack}.{file[:-3]}"
                    file_path = os.path.join(pack_dir, file)
                    spec = importlib.util.spec_from_file_location(mod_name, file_path)
                    mod = importlib.util.module_from_spec(spec)
                    sys.modules[mod_name] = mod
                    try:
                        spec.loader.exec_module(mod)
                        if hasattr(mod, 'source'):
                            instance = mod.source()
                            # If the whitelist is active and the provider declared
                            # one or more self.domains, require at least one of them
                            # to be whitelisted. Providers with empty domains are
                            # always allowed (no info to filter on).
                            instance_domains = [d.lower() for d in (getattr(instance, 'domains', None) or [])]
                            if self.hostDict and instance_domains:
                                if not any(d in self.provider_hosts for d in instance_domains):
                                    continue
                            providers.append((pack, file[:-3], instance))
                            # Use a unique key for provider instances to avoid collisions between packs
                            self.provider_instances[f"{pack}_{file[:-3]}"] = instance
                    except Exception:
                        pass

        self.status["total"] = len(providers)
        self.status["current"] = 0
        self.status["message"] = f"Found {len(providers)} providers..."

        content = 'movie' if tvshowtitle is None else 'episode'
        threads = []
        # We pass aliases as a string representation of a list because many providers 
        # use eval(data['aliases']) and would crash if the key is missing or not a string.
        aliases_str = "[]" 
        
        for pack_name, name, provider in providers:
            if content == 'movie' and hasattr(provider, 'movie'):
                threads.append(threading.Thread(target=self.worker, args=(
                    provider, content, title, title, aliases_str, year, imdb, tmdb, None, None, None, None, name, pack_name
                )))
            elif content == 'episode' and hasattr(provider, 'tvshow'):
                threads.append(threading.Thread(target=self.worker, args=(
                    provider, content, title, title, aliases_str, year, imdb, tmdb, tvdb, season, episode, premiered, name, pack_name
                )))

        [t.start() for t in threads]
        mode = GLOBAL_CONFIG.get('timeout_mode', 'Both')
        global_to = GLOBAL_CONFIG.get('global_timeout', 30)
        per_source_to = GLOBAL_CONFIG.get('per_source_timeout', 15)
        max_wait = global_to if mode in ["Global", "Both"] else (per_source_to + 5)
        self.status["timeout"] = max_wait
        
        start_time = time.time()
        paused_duration = 0
        while any(t.is_alive() for t in threads):
            if self.stop_event.is_set():
                self.status["message"] = "Stopped!"
                break

            if not self.pause_event.is_set():
                p_start = time.time()
                self.status["message"] = "Paused..."
                while not self.pause_event.is_set():
                    if self.stop_event.is_set(): break
                    time.sleep(0.5)
                paused_duration += (time.time() - p_start)
                if self.stop_event.is_set():
                    self.status["message"] = "Stopped!"
                    break

            elapsed = time.time() - start_time - paused_duration
            if elapsed > max_wait: 
                self.status["message"] = "Timeout reached!"
                break
            
            alive = len([t for t in threads if t.is_alive()])
            self.status["message"] = f"Waiting for {alive} providers ({round(max_wait - elapsed)}s left)..."
            time.sleep(0.5)

        if not self.stop_event.is_set():
            self.status["message"] = f"Finished! Found {len(self.sources)} sources."

        # Quality sorting
        quality_map = {'4k': 0, '1080p': 1, '720p': 2, 'hd': 2, 'sd': 3, 'cam': 4, 'scr': 4}
        for s in self.sources:
            s['q_sort'] = quality_map.get(str(s.get('quality')).lower(), 3)
        self.sources.sort(key=lambda x: x['q_sort'])

        # Final filtering: Ensure every result has a 'provider' and 'source' field
        for s in self.sources:
            if 'source' not in s and 'host' in s: s['source'] = s['host']
            if 'provider' not in s: s['provider'] = '[Unknown]'

        return self.sources

    def worker(self, provider, content, title, localtitle, aliases, year, imdb, tmdb, tvdb, season, episode, premiered, name, pack_name):
        try:
            if self.stop_event.is_set(): return
            
            while not self.pause_event.is_set():
                if self.stop_event.is_set(): return
                time.sleep(0.5)

            if content == 'movie':
                sig = inspect.signature(provider.movie)
                if 'tmdb' in sig.parameters:
                    url = provider.movie(imdb, tmdb, title, localtitle, aliases, year)
                else:
                    url = provider.movie(imdb, title, localtitle, aliases, year)
            else:
                sig = inspect.signature(provider.tvshow)
                if 'tmdb' in sig.parameters:
                    url = provider.tvshow(imdb, tmdb, tvdb, title, localtitle, aliases, year)
                else:
                    url = provider.tvshow(imdb, tvdb, title, localtitle, aliases, year)
                
                if url and hasattr(provider, 'episode'):
                    ep_sig = inspect.signature(provider.episode)
                    if 'tmdb' in ep_sig.parameters:
                        url = provider.episode(url, imdb, tmdb, tvdb, title, premiered, season, episode)
                    else:
                        url = provider.episode(url, imdb, tvdb, title, premiered, season, episode)

            if url:
                if self.stop_event.is_set(): return
                while not self.pause_event.is_set():
                    if self.stop_event.is_set(): return
                    time.sleep(0.5)

                sources_sig = inspect.signature(provider.sources)
                if 'hostprDict' in sources_sig.parameters:
                    results = provider.sources(url, self.hostDict, [])
                else:
                    results = provider.sources(url, self.hostDict)

                if results:
                    for res in results:
                        if self.stop_event.is_set(): break
                        res.setdefault('provider', f"[{pack_name}] {name}")
                        res.setdefault('direct', False)
                        # Store the internal key for resolution
                        res['provider_key'] = f"{pack_name}_{name}"
                    self.sources.extend(results)
            self.status["current"] += 1
        except Exception:
            self.status["current"] += 1
            # traceback.print_exc()

    def resolveSource(self, source_data):
        url = source_data.get('url')
        provider_key = source_data.get('provider_key')
        is_video = source_data.get('direct', False)

        if provider_key in self.provider_instances:
            provider = self.provider_instances[provider_key]
            if hasattr(provider, 'resolve'):
                try: 
                    new_url = provider.resolve(url)
                    if new_url:
                        url = new_url
                        # If the provider resolved it, we tend to treat it as video 
                        # unless it's obviously a webpage
                        is_video = True
                except: pass

        if not url: return None, False

        try:
            import modules.scrape_sources as scrape_sources
            url = scrape_sources.prepare_link(url)
        except Exception: pass

        if not url: return None, False

        if resolveurl and hasattr(resolveurl, 'HostedMediaFile'):
            try:
                if resolveurl.HostedMediaFile(url):
                    resolved = resolveurl.resolve(url)
                    if resolved: return resolved, True
            except: pass
            
        # Fallback check for direct links or common video extensions
        video_extensions = ('.m3u8', '.mp4', '.mkv', '.ts', '.webm', '.mpd', '.avi', '.flv', '.mov')
        # Also check for common video hosting keywords if it's not a known extension
        video_keywords = ['/embed/', '/player/', 'vidsrc', '2embed', 'vidlink', 'vidcloud', 'vcloud', 'googlevideo', 'gvideo']
        
        url_lower = url.lower()
        if any(url_lower.split('?')[0].endswith(ext) for ext in video_extensions) or '/hls/' in url_lower:
            is_video = True
        elif any(k in url_lower for k in video_keywords):
            is_video = True
                
        return url, is_video

# --- ANDROID BRIDGE FUNCTIONS ---

active_scraper = None

def set_config(cfg_json):
    try:
        cfg = json.loads(cfg_json)
        global GLOBAL_CONFIG
        
        # If enabled_packs list is provided, update individual pack_ flags to keep in sync
        if "enabled_packs" in cfg:
            enabled = cfg["enabled_packs"]
            if os.path.exists(SOURCES_PATH):
                for d in os.listdir(SOURCES_PATH):
                    if os.path.isdir(os.path.join(SOURCES_PATH, d)):
                        GLOBAL_CONFIG[f"pack_{d}"] = d in enabled

        GLOBAL_CONFIG.update(cfg)
        save_config(GLOBAL_CONFIG)
        return "Success"
    except Exception as e:
        return str(e)

def get_enabled_packs():
    try:
        packs = [d for d in os.listdir(SOURCES_PATH) if os.path.isdir(os.path.join(SOURCES_PATH, d))]
        return json.dumps(packs)
    except:
        return json.dumps([])

def get_all_hosts():
    try:
        resolveurl_hosts = set()
        if resolveurl and hasattr(resolveurl, 'relevant_resolvers'):
            try:
                resolvers = resolveurl.relevant_resolvers(order_matters=True)
                for r in resolvers:
                    if hasattr(r, 'domains') and r.domains:
                        for dom in r.domains:
                            if '*' not in dom:
                                resolveurl_hosts.add(dom.lower())
            except: pass
        
        # Add scrape_sources.py hosts to resolveurl category
        try:
            scrape_sources_path = os.path.join(PROJECT_ROOT, 'modules', 'scrape_sources.py')
            if os.path.exists(scrape_sources_path):
                with open(scrape_sources_path, 'r', encoding='utf-8', errors='ignore') as f:
                    content = f.read()
                matches = re.findall(r'(\w+(?:_domains|_working_domains|_redir_domains))\s*=\s*\[(.*?)\]', content, re.DOTALL)
                for var_name, list_content in matches:
                    found = re.findall(r"['\"]([^'\"]+)['\"]", list_content)
                    for dom in found:
                        resolveurl_hosts.add(dom.lower())
        except: pass

        provider_hosts = set(gather_provider_pack_hosts())
        # Remove duplicates from provider_hosts if they are already in resolveurl_hosts
        provider_hosts = provider_hosts - resolveurl_hosts
        
        return json.dumps({
            "ResolveURL Hosts": sorted(list(resolveurl_hosts)),
            "Provider Pack Hosts": sorted(list(provider_hosts))
        })
    except:
        return json.dumps({})


def get_tv_seasons(tv_id):
    try:
        api_key = "f5608fba6ab49e9985828b35d5653321"
        url = f"https://api.themoviedb.org/3/tv/{tv_id}?api_key={api_key}"
        res = requests.get(url).json()
        return json.dumps(res)
    except:
        return json.dumps({})

def get_tv_episodes(tv_id, season_number):
    try:
        api_key = "f5608fba6ab49e9985828b35d5653321"
        url = f"https://api.themoviedb.org/3/tv/{tv_id}/season/{season_number}?api_key={api_key}"
        res = requests.get(url).json().get('episodes', [])
        return json.dumps(res)
    except:
        return json.dumps([])

def search_subtitles(item_json, season=None, episode=None):
    try:
        import subtitles.manager as sub_manager
        item = json.loads(item_json)
        tmdb_id = str(item['id'])
        api_key = "f5608fba6ab49e9985828b35d5653321"
        media_type = item.get('media_type', 'movie')
        
        ext_url = f"https://api.themoviedb.org/3/{media_type}/{tmdb_id}/external_ids?api_key={api_key}"
        imdb_id = requests.get(ext_url).json().get('imdb_id', '0')
        
        title = item.get('orig_title') or item.get('title')
        year = item.get('year') or (item.get('release_date') or '0000')[:4]

        results = sub_manager.search_subtitles(
            imdb_id=imdb_id,
            title=title,
            year=year,
            season=season,
            episode=episode,
            tvshow=title if media_type == 'tv' else None,
            settings=GLOBAL_CONFIG
        )
        return json.dumps(results or [])
    except Exception:
        traceback.print_exc()
        return json.dumps([])

def get_subtitle_file(service_name, action_args_json):
    try:
        import subtitles.manager as sub_manager
        action_args = json.loads(action_args_json)
        filepath = sub_manager.download_subtitle(
            service_name,
            action_args,
            settings=GLOBAL_CONFIG
        )
        return filepath if filepath else ""
    except Exception:
        traceback.print_exc()
        return ""

def get_scrape_status():
    global active_scraper
    if active_scraper:
        res = active_scraper.status.copy()
        sources = active_scraper.sources[:]
        
        # Check if we have cached display results for this count to save CPU
        cached_count = getattr(active_scraper, "_last_format_count", -1)
        if cached_count == len(sources) and hasattr(active_scraper, "_cached_display_sources"):
            res["sources"] = active_scraper._cached_display_sources
            return json.dumps(res)

        display_sources = []
        video_extensions = ('.m3u8', '.mp4', '.mkv', '.ts', '.webm', '.mpd', '.avi', '.flv', '.mov')
        video_keywords = ['/embed/', '/player/', 'vidsrc', '2embed', 'vidlink', 'vidcloud', 'vcloud', 'googlevideo', 'gvideo']

        # Quality sorting for current sources
        quality_map = {'4k': 0, '1080p': 1, '720p': 2, 'hd': 2, 'sd': 3, 'cam': 4, 'scr': 4}
        for s in sources:
            s['q_sort'] = quality_map.get(str(s.get('quality')).lower(), 3)
        sources.sort(key=lambda x: x['q_sort'])

        for s in sources:
            url = s.get('url', '')
            is_video = s.get('direct', False)
            if not is_video:
                url_lower = url.lower()
                if any(url_lower.split('?')[0].endswith(ext) for ext in video_extensions) or '/hls/' in url_lower:
                    is_video = True
                elif any(k in url_lower for k in video_keywords):
                    is_video = True
                elif resolveurl and hasattr(resolveurl, 'HostedMediaFile'):
                    try:
                        if resolveurl.HostedMediaFile(url):
                            is_video = True
                    except: pass

            title_prefix = "[BROWSER] " if not is_video else ""
            display_sources.append({
                "title": f"{title_prefix}[{s.get('quality', 'SD')}] {s.get('source')} ({s.get('provider')})",
                "source_data": json.dumps(s)
            })
        
        active_scraper._last_format_count = len(sources)
        active_scraper._cached_display_sources = display_sources
        res["sources"] = display_sources
        return json.dumps(res)
    return json.dumps({"total": 0, "current": 0, "message": "No active scrape", "timeout": 0, "sources": []})

def stop_scrape():
    global active_scraper
    if active_scraper:
        active_scraper.stop_event.set()
        active_scraper.pause_event.set() # Unpause if paused so threads can exit
        return "Stopped"
    return "No active scraper"

def pause_scrape():
    global active_scraper
    if active_scraper:
        active_scraper.pause_event.clear()
        active_scraper.status["message"] = "Paused..."
        return "Paused"
    return "No active scraper"

def resume_scrape():
    global active_scraper
    if active_scraper:
        active_scraper.pause_event.set()
        active_scraper.status["message"] = "Resuming..."
        return "Resumed"
    return "No active scraper"

def search(query):
    try:
        # TMDB Search
        api_key = "f5608fba6ab49e9985828b35d5653321"
        # Try movie search
        res = requests.get(f"https://api.themoviedb.org/3/search/multi?api_key={api_key}&query={query.replace(' ', '+')}").json().get('results', [])
        # Filter for movie/tv and ensure they have enough info
        filtered = []
        for item in res:
            if item.get('media_type') in ['movie', 'tv']:
                title = item.get('title') or item.get('name')
                year = (item.get('release_date') or item.get('first_air_date') or '0000')[:4]
                filtered.append({
                    "title": f"{title} ({year})",
                    "id": item['id'],
                    "media_type": item['media_type'],
                    "release_date": item.get('release_date') or item.get('first_air_date'),
                    "overview": item.get('overview'),
                    "poster_path": item.get('poster_path'),
                    "orig_title": title,
                    "year": year
                })
        return json.dumps(filtered)
    except Exception as e:
        return json.dumps([{"title": f"Error: {str(e)}", "url": ""}])

def scrape(item_json, season=None, episode=None):
    global active_scraper
    stop_scrape()
    try:
        item = json.loads(item_json)
        tmdb_id = str(item['id'])
        api_key = "f5608fba6ab49e9985828b35d5653321"
        media_type = item.get('media_type', 'movie')
        
        ext_url = f"https://api.themoviedb.org/3/{media_type}/{tmdb_id}/external_ids?api_key={api_key}"
        imdb_id = requests.get(ext_url).json().get('imdb_id', '0')
        
        # Get enabled packs
        if not os.path.exists(SOURCES_PATH):
            return json.dumps([{"title": f"Error: SOURCES_PATH not found at {SOURCES_PATH}", "source_data": ""}])

        packs = [d for d in os.listdir(SOURCES_PATH) if os.path.isdir(os.path.join(SOURCES_PATH, d))]
        
        enabled_packs = GLOBAL_CONFIG.get("enabled_packs")
        if enabled_packs is None:
            # Fallback to individual flags, which default to True in load_config
            enabled_packs = [p for p in packs if GLOBAL_CONFIG.get(f"pack_{p}", True)]
        
        active_scraper = UniversalScraper(enabled_packs)
        
        title = item.get('orig_title') or item.get('title')
        year = item.get('year') or '0000'
        
        sources = active_scraper.getSources(
            title=title, 
            year=year, 
            imdb=imdb_id, 
            tmdb=tmdb_id,
            tvshowtitle=title if media_type == 'tv' else None,
            season=season,
            episode=episode
        )
        
        # Format for display
        display_sources = []
        video_extensions = ('.m3u8', '.mp4', '.mkv', '.ts', '.webm', '.mpd', '.avi', '.flv', '.mov')
        video_keywords = ['/embed/', '/player/', 'vidsrc', '2embed', 'vidlink', 'vidcloud', 'vcloud', 'googlevideo', 'gvideo']
        
        for s in sources:
            url = s.get('url', '')
            is_video = s.get('direct', False)
            if not is_video:
                url_lower = url.lower()
                if any(url_lower.split('?')[0].endswith(ext) for ext in video_extensions) or '/hls/' in url_lower:
                    is_video = True
                elif any(k in url_lower for k in video_keywords):
                    is_video = True
                elif resolveurl and hasattr(resolveurl, 'HostedMediaFile'):
                    try:
                        if resolveurl.HostedMediaFile(url):
                            is_video = True
                    except: pass

            title_prefix = "[BROWSER] " if not is_video else ""
            display_sources.append({
                "title": f"{title_prefix}[{s.get('quality', 'SD')}] {s.get('source')} ({s.get('provider')})",
                "source_data": json.dumps(s)
            })
            
        return json.dumps(display_sources)
    except Exception as e:
        traceback.print_exc()
        return json.dumps([{"title": f"Scrape Error: {str(e)}", "source_data": ""}])
    finally:
        active_scraper = None

def resolve(source_data_json):
    try:
        source_data = json.loads(source_data_json)
        # Get enabled packs
        packs = [d for d in os.listdir(SOURCES_PATH) if os.path.isdir(os.path.join(SOURCES_PATH, d))]
        enabled_packs = GLOBAL_CONFIG.get("enabled_packs")
        if enabled_packs is None:
            enabled_packs = [p for p in packs if GLOBAL_CONFIG.get(f"pack_{p}", True)]
        
        scraper = UniversalScraper(enabled_packs)
        url, is_video = scraper.resolveSource(source_data)
        return json.dumps({"url": url if url else "", "is_video": is_video})
    except Exception as e:
        return json.dumps({"error": str(e)})

# --- END ANDROID BRIDGE ---

try:
    from PyQt6.QtWidgets import (QApplication, QMainWindow, QWidget, QVBoxLayout, 
                                 QHBoxLayout, QLineEdit, QPushButton, QListWidget, 
                                 QListWidgetItem, QLabel, QProgressBar, QMessageBox, 
                                 QSplitter, QDialog, QCheckBox, QScrollArea, QComboBox, 
                                 QTimeEdit, QFormLayout, QFrame, QTabWidget)
    from PyQt6.QtCore import Qt, QThread, pyqtSignal, QSize, QTime
    from PyQt6.QtGui import QPixmap, QImage
except ImportError:
    # Define dummy classes to prevent NameError on Android where PyQt6 is missing
    class QDialog: pass
    class QMainWindow: pass
    class QWidget: pass
    class QThread: pass
    class pyqtSignal:
        def __init__(self, *args): pass
        def emit(self, *args): pass
    class QImage: pass
    Qt = type('Qt', (), {'Orientation': type('Orientation', (), {'Horizontal': 1, 'Vertical': 2})})
    pass

def gather_all_hosts_dynamically():
    hosts = set()

    if resolveurl and hasattr(resolveurl, 'relevant_resolvers'):
        try:
            resolvers = resolveurl.relevant_resolvers(order_matters=True)
            for r in resolvers:
                if hasattr(r, 'domains') and r.domains:
                    for dom in r.domains:
                        if '*' not in dom:
                            hosts.add(dom.lower())
        except Exception:
            pass

    try:
        scrape_sources_path = os.path.join(PROJECT_ROOT, 'modules', 'scrape_sources.py')
        if os.path.exists(scrape_sources_path):
            with open(scrape_sources_path, 'r', encoding='utf-8', errors='ignore') as f:
                content = f.read()
            matches = re.findall(r'(\w+(?:_domains|_working_domains|_redir_domains))\s*=\s*\[(.*?)\]', content, re.DOTALL)
            for var_name, list_content in matches:
                found = re.findall(r"['\"]([^'\"]+)['\"]", list_content)
                for dom in found:
                    hosts.add(dom.lower())
    except Exception:
        pass
    
    for h in gather_provider_pack_hosts():
        hosts.add(h)

    return sorted(list(hosts))

class SettingsDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Engine & Provider Settings")
        self.resize(500, 600)
        self.layout = QVBoxLayout(self)
        self.cfg = GLOBAL_CONFIG
        self.tabs = QTabWidget()
        self.layout.addWidget(self.tabs)
        self.tab_general = QWidget()
        self.layout_general = QVBoxLayout(self.tab_general)
        self.form_layout = QFormLayout()
        self.combo_mode = QComboBox()
        self.combo_mode.addItems(["Global", "Per-Source", "Both"])
        self.combo_mode.setCurrentText(self.cfg.get("timeout_mode", "Both"))
        self.combo_mode.currentTextChanged.connect(self.update_ui_state)
        self.time_global = QTimeEdit()
        self.time_global.setDisplayFormat("mm:ss")
        g_sec = self.cfg.get("global_timeout", 30)
        self.time_global.setTime(QTime(0, g_sec // 60, g_sec % 60))
        self.time_source = QTimeEdit()
        self.time_source.setDisplayFormat("mm:ss")
        s_sec = self.cfg.get("per_source_timeout", 15)
        self.time_source.setTime(QTime(0, s_sec // 60, s_sec % 60))
        self.form_layout.addRow("Timeout Mode:", self.combo_mode)
        self.form_layout.addRow("Global Timeout:", self.time_global)
        self.form_layout.addRow("Per-Source Timeout:", self.time_source)
        self.layout_general.addLayout(self.form_layout)
        self.update_ui_state(self.combo_mode.currentText())
        line = QFrame()
        line.setFrameShape(QFrame.Shape.HLine)
        line.setFrameShadow(QFrame.Shadow.Sunken)
        self.layout_general.addWidget(line)
        self.layout_general.addWidget(QLabel("<b>Provider Packs</b>"))
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        content = QWidget()
        self.vbox = QVBoxLayout(content)
        self.checkboxes = {}
        packs = [d for d in os.listdir(SOURCES_PATH) if os.path.isdir(os.path.join(SOURCES_PATH, d))]
        if not packs:
            self.vbox.addWidget(QLabel("No provider packs found in sources/ directory."))
        else:
            for pack in packs:
                cb = QCheckBox(f"Enable '{pack}'")
                cb.setChecked(self.cfg.get(f"pack_{pack}", True))
                self.checkboxes[f"pack_{pack}"] = cb
                self.vbox.addWidget(cb)
        self.vbox.addStretch()
        scroll.setWidget(content)
        self.layout_general.addWidget(scroll)
        self.tabs.addTab(self.tab_general, "General Settings")
        self.tab_hosts = QWidget()
        self.layout_hosts = QVBoxLayout(self.tab_hosts)
        self.cb_use_only = QCheckBox("Use only these hosts")
        self.cb_use_only.setChecked(self.cfg.get("use_only_whitelisted_hosts", True))
        self.cb_use_only.stateChanged.connect(self.update_hosts_enabled_state)
        self.layout_hosts.addWidget(self.cb_use_only)
        search_layout = QHBoxLayout()
        self.host_search = QLineEdit()
        self.host_search.setPlaceholderText("Search hosts...")
        self.host_search.textChanged.connect(self.filter_hosts)
        search_layout.addWidget(self.host_search)
        self.btn_all = QPushButton("Select All")
        self.btn_all.clicked.connect(self.select_all_hosts)
        self.btn_none = QPushButton("Clear All")
        self.btn_none.clicked.connect(self.clear_all_hosts)
        self.btn_default = QPushButton("Reset to Defaults")
        self.btn_default.clicked.connect(self.reset_hosts_to_default)
        search_layout.addWidget(self.btn_all)
        search_layout.addWidget(self.btn_none)
        search_layout.addWidget(self.btn_default)
        self.layout_hosts.addLayout(search_layout)
        self.scroll_hosts = QScrollArea()
        self.scroll_hosts.setWidgetResizable(True)
        content_hosts = QWidget()
        self.vbox_hosts = QVBoxLayout(content_hosts)
        self.host_checkboxes = {}
        self.all_dynamic_hosts = gather_all_hosts_dynamically()
        whitelisted = self.cfg.get("whitelisted_hosts", [])
        whitelisted_low = [w.lower() for w in whitelisted]
        for h in self.all_dynamic_hosts:
            cb = QCheckBox(h)
            cb.setChecked(h in whitelisted_low)
            cb.stateChanged.connect(self.on_host_checkbox_changed)
            self.host_checkboxes[h] = cb
            self.vbox_hosts.addWidget(cb)
        self.vbox_hosts.addStretch()
        self.scroll_hosts.setWidget(content_hosts)
        self.layout_hosts.addWidget(self.scroll_hosts)
        self.tabs.addTab(self.tab_hosts, "Provider Whitelist")
        self.update_hosts_enabled_state()
        btn_save = QPushButton("Save && Close")
        btn_save.clicked.connect(self.save_and_close)
        self.layout.addWidget(btn_save)

    def update_hosts_enabled_state(self):
        is_checked = self.cb_use_only.isChecked()
        self.host_search.setEnabled(is_checked)
        self.btn_all.setEnabled(is_checked)
        self.btn_none.setEnabled(is_checked)
        self.btn_default.setEnabled(is_checked)
        self.scroll_hosts.setEnabled(is_checked)
        if is_checked:
            any_checked = any(cb.isChecked() for cb in self.host_checkboxes.values())
            if not any_checked:
                self.reset_hosts_to_default()

    def filter_hosts(self, text):
        text = text.lower().strip()
        for h, cb in self.host_checkboxes.items():
            cb.setVisible(not text or text in h)

    def select_all_hosts(self):
        for cb in self.host_checkboxes.values():
            if cb.isVisible():
                cb.setChecked(True)

    def clear_all_hosts(self):
        for cb in self.host_checkboxes.values():
            cb.setChecked(False)

    def reset_hosts_to_default(self):
        default_hosts = get_default_whitelist()
        default_hosts_low = [d.lower() for d in default_hosts]
        for h, cb in self.host_checkboxes.items():
            cb.setChecked(h in default_hosts_low)

    def on_host_checkbox_changed(self):
        any_checked = any(cb.isChecked() for cb in self.host_checkboxes.values())
        self.cb_use_only.blockSignals(True)
        self.cb_use_only.setChecked(any_checked)
        self.cb_use_only.blockSignals(False)
        is_checked = self.cb_use_only.isChecked()
        self.host_search.setEnabled(is_checked)
        self.btn_all.setEnabled(is_checked)
        self.btn_none.setEnabled(is_checked)
        self.btn_default.setEnabled(is_checked)
        self.scroll_hosts.setEnabled(is_checked)

    def update_ui_state(self, mode):
        lbl_global = self.form_layout.labelForField(self.time_global)
        lbl_source = self.form_layout.labelForField(self.time_source)

        if mode == "Global":
            self.time_global.setVisible(True)
            if lbl_global: lbl_global.setVisible(True)
            self.time_source.setVisible(False)
            if lbl_source: lbl_source.setVisible(False)
        elif mode == "Per-Source":
            self.time_global.setVisible(False)
            if lbl_global: lbl_global.setVisible(False)
            self.time_source.setVisible(True)
            if lbl_source: lbl_source.setVisible(True)
        else:
            self.time_global.setVisible(True)
            if lbl_global: lbl_global.setVisible(True)
            self.time_source.setVisible(True)
            if lbl_source: lbl_source.setVisible(True)

    def save_and_close(self):
        self.cfg["timeout_mode"] = self.combo_mode.currentText()
        g_time = self.time_global.time()
        self.cfg["global_timeout"] = g_time.minute() * 60 + g_time.second()
        s_time = self.time_source.time()
        self.cfg["per_source_timeout"] = s_time.minute() * 60 + s_time.second()
        for pack_key, cb in self.checkboxes.items():
            self.cfg[pack_key] = cb.isChecked()

        self.cfg["use_only_whitelisted_hosts"] = self.cb_use_only.isChecked()
        self.cfg["whitelisted_hosts"] = [h for h, cb in self.host_checkboxes.items() if cb.isChecked()]
        save_config(self.cfg)
        global GLOBAL_CONFIG
        GLOBAL_CONFIG = self.cfg
        self.accept()

class SearchWorker(QThread):
    results_ready = pyqtSignal(list)
    def __init__(self, query):
        super().__init__()
        self.query = query
    def run(self):
        try:
            api_key = "f5608fba6ab49e9985828b35d5653321"
            res = requests.get(f"https://api.themoviedb.org/3/search/movie?api_key={api_key}&query={self.query.replace(' ', '+')}").json().get('results', [])
            self.results_ready.emit(res)
        except: self.results_ready.emit([])

class MovieItemWidget(QWidget):
    def __init__(self, item, parent=None):
        super().__init__(parent)
        layout = QHBoxLayout(self); layout.setContentsMargins(5, 5, 5, 5)
        self.poster = QLabel(); self.poster.setFixedSize(80, 120); self.poster.setStyleSheet("background: #2d2d2d; border-radius: 4px;"); self.poster.setScaledContents(True); layout.addWidget(self.poster)
        info = QVBoxLayout(); info.addWidget(QLabel(f"<b style='color: #e0e0e0;'>{item.get('title') or item.get('name')} ({item.get('release_date', '0000')[:4]})</b>"))
        blurb = QLabel(item.get('overview', '...')); blurb.setWordWrap(True); blurb.setStyleSheet("color: #999; font-size: 11px;"); info.addWidget(blurb); layout.addLayout(info)
        if item.get('poster_path'):
            self.dl = PosterDownloader(f"https://image.tmdb.org/t/p/w185{item['poster_path']}")
            self.dl.finished.connect(lambda img: self.poster.setPixmap(QPixmap.fromImage(img))); self.dl.start()

class PosterDownloader(QThread):
    finished = pyqtSignal(QImage)
    def __init__(self, url): super().__init__(); self.url = url
    def run(self):
        try: data = requests.get(self.url, timeout=5).content; img = QImage(); img.loadFromData(data); self.finished.emit(img)
        except: pass

class ScrapeWorker(QThread):
    sources_ready = pyqtSignal(list)
    def __init__(self, item, enabled_packs): 
        super().__init__()
        self.item = item
        self.enabled_packs = enabled_packs

    def run(self):
        try:
            tmdb_id = str(self.item['id'])
            api_key = "f5608fba6ab49e9985828b35d5653321"
            ext_url = f"https://api.themoviedb.org/3/movie/{tmdb_id}/external_ids?api_key={api_key}"
            imdb_id = requests.get(ext_url).json().get('imdb_id', '0')
            scraper = UniversalScraper(self.enabled_packs)
            file_sources = scraper.getSources(
                title=self.item['title'], 
                year=self.item.get('release_date', '0000')[:4], 
                imdb=imdb_id, 
                tmdb=tmdb_id
            )
            self.sources_ready.emit(file_sources)
        except Exception:
            traceback.print_exc()
            self.sources_ready.emit([])

class UniversalApp(QMainWindow):
    def __init__(self):
        super().__init__()

    def open_settings(self):
        dlg = SettingsDialog(self)
        dlg.exec()

    def get_enabled_packs(self):
        packs = [d for d in os.listdir(SOURCES_PATH) if os.path.isdir(os.path.join(SOURCES_PATH, d))]
        return [p for p in packs if GLOBAL_CONFIG.get(f"pack_{p}", True)]

    def start_search(self):
        self.results.clear(); self.sources_list.clear()
        self.btn.setEnabled(False)
        self.search_worker = SearchWorker(self.input.text())
        self.search_worker.results_ready.connect(self.on_search_results)
        self.search_worker.start()

    def on_search_results(self, results):
        self.movie_data = results
        for item in results:
            li = QListWidgetItem(self.results); li.setSizeHint(QSize(400, 130))
            self.results.setItemWidget(li, MovieItemWidget(item))
        self.btn.setEnabled(True)

    def on_selected(self, li):
        idx = self.results.row(li); self.sources_list.clear()
        self.progress.setVisible(True); self.progress.setRange(0, 0)
        enabled_packs = self.get_enabled_packs()
        self.worker = ScrapeWorker(self.movie_data[idx], enabled_packs)
        self.worker.sources_ready.connect(self.on_found)
        self.worker.start()

    def on_found(self, slist):
        self.progress.setVisible(False); self.found_sources = slist
        if not slist: self.sources_list.addItem("No sources found.")
        for s in slist: 
            self.sources_list.addItem(f"[{s.get('quality', 'SD')}] {s.get('source')} ({s.get('provider')})")

    def on_resolve(self, li):
        idx = self.sources_list.row(li)
        if idx >= len(self.found_sources): return
        source_data = self.found_sources[idx]
        try:
            scraper = UniversalScraper(self.get_enabled_packs())
            final_url, is_video = scraper.resolveSource(source_data)
            
            if final_url:
                QApplication.clipboard().setText(final_url)
            else:
                pass
        except Exception:
            traceback.print_exc()

if __name__ == "__main__":
    app = QApplication(sys.argv); window = UniversalApp(); window.show(); sys.exit(app.exec())
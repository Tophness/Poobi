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
from concurrent.futures import ThreadPoolExecutor

try:
    from com.chaquo.python import Python
    python_context = Python.getPlatform().getApplication()
    FILES_DIR = str(python_context.getFilesDir())
except:
    FILES_DIR = "."

def get_path(pkg):
    try:
        import importlib.util
        spec = importlib.util.find_spec(pkg)
        if spec and spec.origin:
            return os.path.dirname(spec.origin)
    except: pass
    return None

SOURCES_PATH = get_path("sources") or os.path.join(FILES_DIR, "chaquopy/AssetFinder/app/sources")
MODULES_PATH = get_path("modules") or os.path.join(FILES_DIR, "chaquopy/AssetFinder/app/modules")
RESOLVEURL_PATH = get_path("resolveurl") or os.path.join(FILES_DIR, "chaquopy/AssetFinder/app/resolveurl")

PROJECT_ROOT = os.path.dirname(SOURCES_PATH)
if not os.path.exists(SOURCES_PATH):
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

for path in [PROJECT_ROOT, MODULES_PATH, RESOLVEURL_PATH]:
    if path and path not in sys.path:
        sys.path.append(path)

try:
    import subtitles.manager as sub_manager
    sub_manager.PROJECT_ROOT = FILES_DIR
    import shutil
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
        "up_next_time_pref": 20,
        "autoplay_next_pref": "Closest Source"
    }

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
        self._last_format_count = -1
        self._cached_display_sources = []

        cfg = GLOBAL_CONFIG
        use_only = cfg.get("use_only_whitelisted_hosts", True)

        if use_only:
            self.hostDict = cfg.get("whitelisted_hosts", [])
            self.hostDict = list(set([h.lower() for h in self.hostDict]))
        else:
            self.hostDict = []

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
                            instance_domains = [d.lower() for d in (getattr(instance, 'domains', None) or [])]
                            if self.hostDict and instance_domains:
                                if not any(d in self.provider_hosts for d in instance_domains):
                                    continue
                            providers.append((pack, file[:-3], instance))
                            self.provider_instances[f"{pack}_{file[:-3]}"] = instance
                    except Exception:
                        pass

        self.status["total"] = len(providers)
        self.status["current"] = 0
        self.status["message"] = f"Found {len(providers)} providers..."

        content = 'movie' if tvshowtitle is None else 'episode'
        compatible_providers = []
        for pack_name, name, provider in providers:
            if content == 'movie' and hasattr(provider, 'movie'):
                compatible_providers.append((pack_name, name, provider))
            elif content == 'episode' and hasattr(provider, 'tvshow'):
                compatible_providers.append((pack_name, name, provider))

        self.status["total"] = len(compatible_providers)
        self.status["current"] = 0
        self.status["message"] = f"Found {len(compatible_providers)} compatible providers..."

        aliases_str = "[]"

        executor = ThreadPoolExecutor(max_workers=3)
        futures = []

        for pack_name, name, provider in compatible_providers:
            if content == 'movie':
                args = (
                    provider, content, title, title, aliases_str, year, imdb, tmdb, None, None, None, None, name, pack_name
                )
            elif content == 'episode':
                args = (
                    provider, content, title, title, aliases_str, year, imdb, tmdb, tvdb, season, episode, premiered, name, pack_name
                )
            futures.append(executor.submit(self.worker, *args))

        mode = GLOBAL_CONFIG.get('timeout_mode', 'Both')
        global_to = GLOBAL_CONFIG.get('global_timeout', 30)
        per_source_to = GLOBAL_CONFIG.get('per_source_timeout', 15)
        max_wait = global_to if mode in ["Global", "Both"] else (per_source_to + 5)
        self.status["timeout"] = max_wait
        
        start_time = time.monotonic()
        paused_duration = 0
        while not all(f.done() for f in futures):
            if self.stop_event.is_set():
                self.status["message"] = "Stopped!"
                break

            if not self.pause_event.is_set():
                p_start = time.monotonic()
                self.status["message"] = "Paused..."
                while not self.pause_event.is_set():
                    if self.stop_event.is_set(): break
                    time.sleep(0.2)
                paused_duration += (time.monotonic() - p_start)
                if self.stop_event.is_set():
                    self.status["message"] = "Stopped!"
                    break
                self.status["message"] = "Resuming..."
                time.sleep(0.1) # Let threads catch up

            elapsed = time.monotonic() - start_time - paused_duration
            if elapsed > max_wait: 
                self.status["message"] = "Timeout reached!"
                break
            
            alive = len([f for f in futures if not f.done()])
            self.status["message"] = f"Waiting for {alive} providers ({round(max_wait - elapsed)}s left)..."
            time.sleep(0.5)

        for f in futures:
            f.cancel()
        executor.shutdown(wait=False)

        if not self.stop_event.is_set():
            if self.status["message"] != "Timeout reached!":
                self.status["message"] = f"Finished! Found {len(self.sources)} sources."
            else:
                self.status["message"] = f"Timeout reached! Found {len(self.sources)} sources."

        quality_map = {'4k': 0, '1080p': 1, '720p': 2, 'hd': 2, 'sd': 3, 'cam': 4, 'scr': 4}
        for s in self.sources:
            s['q_sort'] = quality_map.get(str(s.get('quality')).lower(), 3)
        self.sources.sort(key=lambda x: x['q_sort'])

        for s in self.sources:
            if 'source' not in s and 'host' in s: s['source'] = s['host']
            if 'provider' not in s: s['provider'] = '[Unknown]'

        return self.sources

    def worker(self, provider, content, title, localtitle, aliases, year, imdb, tmdb, tvdb, season, episode, premiered, name, pack_name):
        try:
            if self.stop_event.is_set(): return
            
            while not self.pause_event.is_set():
                if self.stop_event.is_set(): return
                time.sleep(0.2)

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
                    time.sleep(0.2)

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

        video_extensions = ('.m3u8', '.mp4', '.mkv', '.ts', '.webm', '.mpd', '.avi', '.flv', '.mov')
        video_keywords = ['/embed/', '/player/', 'vidsrc', '2embed', 'vidlink', 'vidcloud', 'vcloud', 'googlevideo', 'gvideo']
        
        url_lower = url.lower()
        if any(url_lower.split('?')[0].endswith(ext) for ext in video_extensions) or '/hls/' in url_lower:
            is_video = True
        elif any(k in url_lower for k in video_keywords):
            is_video = True
                
        return url, is_video


active_scraper = None

def set_config(cfg_json):
    try:
        cfg = json.loads(cfg_json)
        global GLOBAL_CONFIG

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
        provider_hosts = provider_hosts - resolveurl_hosts
        
        return json.dumps({
            "ResolveURL Hosts": sorted(list(resolveurl_hosts)),
            "Provider Pack Hosts": sorted(list(provider_hosts))
        })
    except:
        return json.dumps({})


def get_tv_seasons(tv_id):
    try:
        import tmdb.tmdb_utils as tmdb_utils
        res = tmdb_utils.get_details(tv_id, 'tv')
        return json.dumps(res)
    except:
        return json.dumps({})

def get_tv_episodes(tv_id, season_number):
    try:
        import tmdb.tmdb_utils as tmdb_utils
        res = tmdb_utils.get_tv_episodes(tv_id, season_number).get('episodes', [])
        return json.dumps(res)
    except:
        return json.dumps([])

def search_subtitles(item_json, season=None, episode=None):
    try:
        import subtitles.manager as sub_manager
        import tmdb.tmdb_utils as tmdb_utils
        item = json.loads(item_json)
        tmdb_id = str(item['id'])
        media_type = item.get('media_type', 'movie')
        
        ext_ids = tmdb_utils.get_external_ids(tmdb_id, media_type)
        imdb_id = ext_ids.get('imdb_id', '0')
        
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
    s_inst = active_scraper
    if s_inst:
        res = s_inst.status.copy()
        sources = s_inst.sources[:]

        cached_count = getattr(s_inst, "_last_format_count", -1)
        if cached_count == len(sources) and hasattr(s_inst, "_cached_display_sources"):
            res["sources"] = s_inst._cached_display_sources
            return json.dumps(res)

        display_sources = []
        video_extensions = ('.m3u8', '.mp4', '.mkv', '.ts', '.webm', '.mpd', '.avi', '.flv', '.mov')
        video_keywords = ['/embed/', '/player/', 'vidsrc', '2embed', 'vidlink', 'vidcloud', 'vcloud', 'googlevideo', 'gvideo']

        quality_map = {'4k': 0, '1080p': 1, '720p': 2, 'hd': 2, 'sd': 3, 'cam': 4, 'scr': 4}
        for s in sources:
            s['q_sort'] = quality_map.get(str(s.get('quality')).lower(), 3)
        sources.sort(key=lambda x: x['q_sort'])

        for s in sources:
            is_video = s.get('is_video')
            if is_video is None:
                is_video = s.get('direct', False)
                if not is_video:
                    url = s.get('url', '')
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
                s['is_video'] = is_video

            title_prefix = "[BROWSER] " if not is_video else ""
            display_sources.append({
                "title": f"{title_prefix}[{s.get('quality', 'SD')}] {s.get('source')} ({s.get('provider')})",
                "source_data": json.dumps(s)
            })
        
        s_inst._last_format_count = len(sources)
        s_inst._cached_display_sources = display_sources
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
        import tmdb.tmdb_utils as tmdb_utils
        res = tmdb_utils.search(query).get('results', [])

        filtered = []
        for item in res:
            media_type = item.get('media_type')
            if media_type in ['movie', 'tv']:
                title = item.get('title') or item.get('name')
                year = (item.get('release_date') or item.get('first_air_date') or '0000')[:4]
                filtered.append({
                    "title": f"{title} ({year})",
                    "id": item['id'],
                    "media_type": media_type,
                    "release_date": item.get('release_date') or item.get('first_air_date'),
                    "overview": item.get('overview'),
                    "poster_path": item.get('poster_path'),
                    "backdrop_path": item.get('backdrop_path'),
                    "vote_average": item.get('vote_average'),
                    "genre_ids": item.get('genre_ids', []),
                    "orig_title": title,
                    "year": year
                })
            elif media_type == 'person':
                filtered.append({
                    "title": item.get('name'),
                    "id": item['id'],
                    "media_type": "person",
                    "overview": f"Known for: {item.get('known_for_department')}",
                    "poster_path": item.get('profile_path'),
                    "orig_title": item.get('name'),
                    "year": ""
                })
        return json.dumps(filtered)
    except Exception as e:
        return json.dumps([{"title": f"Error: {str(e)}", "url": ""}])

def scrape(item_json, season=None, episode=None):
    global active_scraper
    stop_scrape()
    try:
        import tmdb.tmdb_utils as tmdb_utils
        item = json.loads(item_json)
        tmdb_id = str(item['id'])
        media_type = item.get('media_type', 'movie')
        
        ext_ids = tmdb_utils.get_external_ids(tmdb_id, media_type)
        imdb_id = ext_ids.get('imdb_id', '0')

        if not os.path.exists(SOURCES_PATH):
            return json.dumps([{"title": f"Error: SOURCES_PATH not found at {SOURCES_PATH}", "source_data": ""}])

        packs = [d for d in os.listdir(SOURCES_PATH) if os.path.isdir(os.path.join(SOURCES_PATH, d))]
        
        enabled_packs = GLOBAL_CONFIG.get("enabled_packs")
        if enabled_packs is None:
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

        display_sources = []
        video_extensions = ('.m3u8', '.mp4', '.mkv', '.ts', '.webm', '.mpd', '.avi', '.flv', '.mov')
        video_keywords = ['/embed/', '/player/', 'vidsrc', '2embed', 'vidlink', 'vidcloud', 'vcloud', 'googlevideo', 'gvideo']
        
        for s in sources:
            is_video = s.get('is_video')
            if is_video is None:
                is_video = s.get('direct', False)
                if not is_video:
                    url = s.get('url', '')
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
                s['is_video'] = is_video

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
        packs = [d for d in os.listdir(SOURCES_PATH) if os.path.isdir(os.path.join(SOURCES_PATH, d))]
        enabled_packs = GLOBAL_CONFIG.get("enabled_packs")
        if enabled_packs is None:
            enabled_packs = [p for p in packs if GLOBAL_CONFIG.get(f"pack_{p}", True)]
        
        scraper = UniversalScraper(enabled_packs)
        url, is_video = scraper.resolveSource(source_data)
        return json.dumps({"url": url if url else "", "is_video": is_video})
    except Exception as e:
        return json.dumps({"error": str(e)})
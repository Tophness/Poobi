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
import warnings
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse

warnings.filterwarnings("ignore", category=DeprecationWarning)

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
for path in [PROJECT_ROOT, MODULES_PATH, RESOLVEURL_PATH]:
    if path and path not in sys.path:
        sys.path.append(path)

# Alias cfscrape to cloudscraper for resolvers that expect it
try:
    import modules.cfscrape as cloudscraper
    sys.modules['cloudscraper'] = cloudscraper
except ImportError:
    pass

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
    'vidcloud9.com', 'vidembed.cc', 'voxzer.org', 'ronemo.com', 'streamembed.net',
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
            # We want the absolute maximum whitelist, so include everything
            resolvers = resolveurl.relevant_resolvers(include_popups=True, include_universal=True, order_matters=True)
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
        "autoplay_next_pref": "Closest Source",
        "flaresolverr_enabled": False,
        "flaresolverr_url": "http://localhost:8191",
        "allow_popups": True,
        "allow_universal": True,
        "auto_pick": True
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

# Sync config with control module and ResolveURL
try:
    import modules.control as control
    control._settings.update(GLOBAL_CONFIG)

    # Also sync to ResolveURL settings
    try:
        import resolveurl.lib.kodi as rk
        rk.set_setting('allow_popups', 'true' if GLOBAL_CONFIG.get('allow_popups', True) else 'false')
        rk.set_setting('allow_universal', 'true' if GLOBAL_CONFIG.get('allow_universal', True) else 'false')
    except: pass
except ImportError:
    pass

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

        # Subset of the whitelist that corresponds to provider-pack source domains
        provider_set = set(gather_provider_pack_hosts())
        self.provider_hosts = [h for h in self.hostDict if h in provider_set]

        # Get captcha hosts from resolveurl
        self.captcha_hosts = set(['flashx.tv', 'flashx.to', 'uptobox.com', 'uptostream.com', 'vshare.eu', 'rabbitstream.net', 'dokicloud.one', 'waaw.ac', 'waaw.tv', 'waaw.to', 'netu.ac', 'hqq.ac', 'brupload.net', 'dailyuploads.net', 'vshare.io'])
        if resolveurl and hasattr(resolveurl, 'relevant_resolvers'):
            try:
                resolvers = resolveurl.relevant_resolvers(include_popups=True, include_universal=True)
                for r in resolvers:
                    try:
                        # Check both class and instance for isPopup
                        is_popup = False
                        if hasattr(r, 'isPopup'):
                            if inspect.ismethod(r.isPopup) or inspect.isfunction(r.isPopup):
                                is_popup = r.isPopup()
                            else:
                                is_popup = r.isPopup

                        if is_popup:
                            for d in getattr(r, 'domains', []):
                                self.captcha_hosts.add(d.lower())
                    except: pass
            except: pass

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
                time.sleep(0.1)

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
                        res['provider_key'] = f"{pack_name}_{name}"
                    self.sources.extend(results)
            self.status["current"] += 1
        except Exception:
            self.status["current"] += 1

    def resolveSource(self, source_data):
        url = source_data.get('url')
        provider_key = source_data.get('provider_key')
        is_video = source_data.get('direct', False)
        print(f"[DEBUG] resolveSource: starting for {url[:100]} (Direct: {is_video})")

        if provider_key in self.provider_instances:
            provider = self.provider_instances[provider_key]
            if hasattr(provider, 'resolve'):
                try: 
                    new_url = provider.resolve(url)
                    if new_url:
                        url = new_url
                        is_video = True
                        print(f"[DEBUG] resolveSource: provider resolved to {url[:100]}")
                except Exception as e:
                    print(f"[DEBUG] resolveSource: provider resolve error: {e}")

        if not url: return None, False

        try:
            import modules.scrape_sources as scrape_sources
            url = scrape_sources.prepare_link(url)
        except Exception: pass

        if not url: return None, False

        if resolveurl and hasattr(resolveurl, 'HostedMediaFile'):
            try:
                print(f"[DEBUG] resolveSource: checking HostedMediaFile for {url[:100]}")
                hmf = resolveurl.HostedMediaFile(url)
                if hmf:
                    print(f"[DEBUG] resolveSource: HostedMediaFile MATCHED. Resolving...")
                    resolved = hmf.resolve()
                    if resolved:
                        print(f"[DEBUG] resolveSource: resolveurl success: {str(resolved)[:100]}")
                        return resolved, True
                    else:
                        print(f"[DEBUG] resolveSource: resolveurl returned None/False")
                else:
                    print(f"[DEBUG] resolveSource: HostedMediaFile did not match any resolvers")
            except Exception as e:
                print(f"[DEBUG] resolveSource: resolveurl error: {e}")
                traceback.print_exc()
            
        video_extensions = ('.m3u8', '.mp4', '.mkv', '.ts', '.webm', '.mpd', '.avi', '.flv', '.mov')
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
                resolvers = resolveurl.relevant_resolvers(include_popups=True, include_universal=True, order_matters=True)
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
        results = sub_manager.search_subtitles(imdb_id=imdb_id, title=title, year=year, season=season, episode=episode, tvshow=title if media_type == 'tv' else None, settings=GLOBAL_CONFIG)
        return json.dumps(results or [])
    except Exception:
        traceback.print_exc()
        return json.dumps([])

def get_subtitle_file(service_name, action_args_json):
    try:
        import subtitles.manager as sub_manager
        action_args = json.loads(action_args_json)
        filepath = sub_manager.download_subtitle(service_name, action_args, settings=GLOBAL_CONFIG)
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
        if getattr(s_inst, "_last_format_count", -1) == len(sources) and hasattr(s_inst, "_cached_display_sources"):
            res["sources"] = s_inst._cached_display_sources
            return json.dumps(res)

        display_sources = []
        video_extensions = ('.m3u8', '.mp4', '.mkv', '.ts', '.webm', '.mpd', '.avi', '.flv', '.mov')
        video_keywords = ['/embed/', '/player/', 'vidsrc', '2embed', 'vidlink', 'vidcloud', 'vcloud', 'googlevideo', 'gvideo']
        quality_map = {'4k': 0, '1080p': 1, '720p': 2, 'hd': 2, 'sd': 3, 'cam': 4, 'scr': 4}
        for s in sources:
            s['q_sort'] = quality_map.get(str(s.get('quality')).lower(), 3)
        sources.sort(key=lambda x: x['q_sort'])

        try:
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
                                if not hasattr(UniversalScraper, "_ui_resolvable_cache"):
                                    UniversalScraper._ui_resolvable_cache = {}
                                domain = urlparse(url).netloc.lower() if url else ''
                                if domain and domain in UniversalScraper._ui_resolvable_cache:
                                    is_video = UniversalScraper._ui_resolvable_cache[domain]
                                elif domain:
                                    if resolveurl.HostedMediaFile(url):
                                        is_video = True
                                    UniversalScraper._ui_resolvable_cache[domain] = is_video
                            except Exception as e: pass
                    s['is_video'] = is_video

                is_captcha = s.get('requires_captcha')
                if is_captcha is None:
                    url = s.get('url', '')
                    domain = urlparse(url).netloc.lower() if url else ''
                    source_name = s.get('source', '').lower()
                    is_captcha = False
                    for h in s_inst.captcha_hosts:
                        if h in domain or domain in h or h in source_name or source_name in h:
                            is_captcha = True
                            break
                    s['requires_captcha'] = is_captcha

                title_prefix = "[BROWSER] " if not is_video else ""
                captcha_prefix = "[CAPTCHA] " if is_captcha else ""
                display_sources.append({
                    "title": f"{title_prefix}{captcha_prefix}[{s.get('quality', 'SD')}] {s.get('source')} ({s.get('provider')})",
                    "source_data": json.dumps(s)
                })
        except Exception as e: pass
        s_inst._last_format_count = len(sources)
        s_inst._cached_display_sources = display_sources
        res["sources"] = display_sources
        return json.dumps(res)
    return json.dumps({"total": 0, "current": 0, "message": "No active scrape", "timeout": 0, "sources": []})

def stop_scrape():
    global active_scraper
    if active_scraper:
        active_scraper.stop_event.set()
        active_scraper.pause_event.set()
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
        api_key = "55d4ea0acb04a10053c2be637eb707a9"
        res = requests.get(f"https://api.themoviedb.org/3/search/multi?api_key={api_key}&query={query.replace(' ', '+')}").json().get('results', [])
        filtered = []
        for item in res:
            media_type = item.get('media_type')
            if media_type in ['movie', 'tv']:
                title = item.get('title') or item.get('name')
                year = (item.get('release_date') or item.get('first_air_date') or '0000')[:4]
                filtered.append({"title": f"{title} ({year})", "id": item['id'], "media_type": media_type, "release_date": item.get('release_date') or item.get('first_air_date'), "overview": item.get('overview'), "poster_path": item.get('poster_path'), "backdrop_path": item.get('backdrop_path'), "vote_average": item.get('vote_average'), "genre_ids": item.get('genre_ids', []), "orig_title": title, "year": year})
        return json.dumps(filtered)
    except Exception as e: return json.dumps([{"title": f"Error: {str(e)}", "url": ""}])

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
        if not os.path.exists(SOURCES_PATH): return json.dumps([{"title": "Error: SOURCES_PATH not found", "source_data": ""}])
        packs = [d for d in os.listdir(SOURCES_PATH) if os.path.isdir(os.path.join(SOURCES_PATH, d))]
        enabled_packs = GLOBAL_CONFIG.get("enabled_packs")
        if enabled_packs is None: enabled_packs = [p for p in packs if GLOBAL_CONFIG.get(f"pack_{p}", True)]
        active_scraper = UniversalScraper(enabled_packs)
        title = item.get('orig_title') or item.get('title')
        year = item.get('year') or '0000'
        sources = active_scraper.getSources(title=title, year=year, imdb=imdb_id, tmdb=tmdb_id, tvshowtitle=title if media_type == 'tv' else None, season=season, episode=episode)
        display_sources = []
        video_extensions = ('.m3u8', '.mp4', '.mkv', '.ts', '.webm', '.mpd', '.avi', '.flv', '.mov')
        video_keywords = ['/embed/', '/player/', 'vidsrc', '2embed', 'vidlink', 'vidcloud', 'vcloud', 'googlevideo', 'gvideo']
        try:
            for s in sources:
                is_video = s.get('is_video')
                if is_video is None:
                    is_video = s.get('direct', False)
                    if not is_video:
                        url = s.get('url', '')
                        url_lower = url.lower()
                        if any(url_lower.split('?')[0].endswith(ext) for ext in video_extensions) or '/hls/' in url_lower: is_video = True
                        elif any(k in url_lower for k in video_keywords): is_video = True
                        elif resolveurl and hasattr(resolveurl, 'HostedMediaFile'):
                            try:
                                if not hasattr(UniversalScraper, "_ui_resolvable_cache"): UniversalScraper._ui_resolvable_cache = {}
                                domain = urlparse(url).netloc.lower() if url else ''
                                if domain and domain in UniversalScraper._ui_resolvable_cache: is_video = UniversalScraper._ui_resolvable_cache[domain]
                                elif domain:
                                    if resolveurl.HostedMediaFile(url): is_video = True
                                    UniversalScraper._ui_resolvable_cache[domain] = is_video
                            except: pass
                    s['is_video'] = is_video
                is_captcha = s.get('requires_captcha')
                if is_captcha is None:
                    url = s.get('url', '')
                    domain = urlparse(url).netloc.lower() if url else ''
                    source_name = s.get('source', '').lower()
                    is_captcha = False
                    for h in active_scraper.captcha_hosts:
                        if h in domain or domain in h or h in source_name or source_name in h: is_captcha = True; break
                    s['requires_captcha'] = is_captcha
                title_prefix = "[BROWSER] " if not is_video else ""
                captcha_prefix = "[CAPTCHA] " if is_captcha else ""
                display_sources.append({"title": f"{title_prefix}{captcha_prefix}[{s.get('quality', 'SD')}] {s.get('source')} ({s.get('provider')})", "source_data": json.dumps(s)})
        except: pass
        return json.dumps(display_sources)
    except Exception as e: return json.dumps([{"title": f"Scrape Error: {str(e)}", "source_data": ""}])
    finally: active_scraper = None

def resolve(source_data_json):
    try:
        source_data = json.loads(source_data_json)
        scraper = UniversalScraper(GLOBAL_CONFIG.get("enabled_packs", []))
        url, is_video = scraper.resolveSource(source_data)
        return json.dumps({"url": url if url else "", "is_video": is_video})
    except Exception as e: return json.dumps({"error": str(e)})

# --- END ANDROID BRIDGE ---

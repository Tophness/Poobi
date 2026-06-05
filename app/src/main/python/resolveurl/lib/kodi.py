import os
import time
import json
import six
from six.moves import urllib_parse
from resolveurl.lib import strings

# Set up a generic path for ResolveURL to store settings
_BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_PROFILE_DIR = os.path.join(_BASE_DIR, 'userdata')
if not os.path.exists(_PROFILE_DIR):
    os.makedirs(_PROFILE_DIR, exist_ok=True)
_SETTINGS_FILE = os.path.join(_PROFILE_DIR, 'settings.json')

def get_path(): return _BASE_DIR
def get_profile(): return _PROFILE_DIR
def translate_path(path): return path.replace('special://profile', _PROFILE_DIR).replace('special://home', _BASE_DIR)

def _load_settings():
    if os.path.exists(_SETTINGS_FILE):
        try:
            with open(_SETTINGS_FILE, 'r') as f: return json.load(f)
        except: pass
    return {}

def get_setting(id): return str(_load_settings().get(id, ''))
def set_setting(id, value):
    settings = _load_settings()
    settings[id] = str(value)
    try:
        with open(_SETTINGS_FILE, 'w') as f: json.dump(settings, f)
    except Exception: pass

def get_version(): return "5.1.0"
def get_id(): return "script.module.resolveurl"
def get_name(): return "ResolveURL"
def kodi_version(): return 20.0
def supported_video_extensions(): return ['.mp4', '.mkv', '.avi', '.flv', '.wmv', '.mov']

def open_settings(): print("[ResolveURL] Open Settings Requested")
def get_keyboard(heading, default='', hide_input=False): return default
def i18n(string_id): return str(strings.STRINGS.get(string_id, string_id))
def get_plugin_url(queries): return ""
def end_of_directory(cache_to_disc=True): pass
def set_content(content): pass
def create_item(*args, **kwargs): pass
def add_item(*args, **kwargs): pass
def parse_query(query): return {'mode': 'main'}
def notify(header=None, msg='', duration=2000, sound=None): print(f"[ResolveURL] {header or 'Notify'}: {msg}")
def close_all(): pass
def get_current_view(): return ""
def yesnoDialog(heading='', line1='', line2='', line3='', nolabel='', yeslabel=''): return True
def has_addon(addon_id): return False
def sleep(ms): time.sleep(ms / 1000.0)

class WorkingDialog(object):
    def __enter__(self): return self
    def __exit__(self, type, value, traceback): pass

class ProgressDialog(object):
    def __init__(self, *args, **kwargs): pass
    def __enter__(self): return self
    def __exit__(self, type, value, traceback): pass
    def is_canceled(self): return False
    def update(self, *args, **kwargs): pass

class CountdownDialog(object):
    def __init__(self, *args, **kwargs): pass
    def __enter__(self): return self
    def __exit__(self, type, value, traceback): pass
    def start(self, func, args=None, kwargs=None): return func(*(args or []), **(kwargs or {}))
    def is_canceled(self): return False
    def update(self, *args, **kwargs): pass
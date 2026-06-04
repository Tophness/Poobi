# -*- coding: utf-8 -*-

import os
import json

# Setup local data directories
addonPath = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
dataPath = os.path.join(addonPath, 'userdata')
if not os.path.exists(dataPath): 
    os.makedirs(dataPath)
settingsFile = os.path.join(dataPath, 'settings.json')

_settings = {}
if os.path.exists(settingsFile):
    try:
        with open(settingsFile, 'r') as f: 
            _settings = json.load(f)
    except: 
        pass

def setting(id):
    if id in _settings:
        return str(_settings[id])
    
    # Default behavior for unconfigured settings
    if id.startswith('provider.') or id.startswith('scrape.'):
        return 'true'
    
    # Generic numeric/boolean fallbacks like in the older scripts
    if any(x in id for x in ['timeout', 'limit', 'count']):
        return '60'
    
    return '0'

def setSetting(id, val):
    _settings[id] = str(val)
    try:
        with open(settingsFile, 'w') as f: 
            json.dump(_settings, f)
    except: 
        pass

def jsonrpc(*args, **kwargs): 
    return "{}"

def makeFile(path): 
    os.makedirs(path, exist_ok=True)

def openFile(path, mode='r'): 
    return open(path, mode, encoding='utf-8')

def getKodiVersion(): return 20

# Database cache paths used by cache.py
providercacheFile = os.path.join(dataPath, 'providers.db')
cacheFile = os.path.join(dataPath, 'cache.db')
metacacheFile = os.path.join(dataPath, 'meta.db')
searchFile = os.path.join(dataPath, 'search.db')
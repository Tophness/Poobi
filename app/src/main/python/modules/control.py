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
    config_id = id.replace('.', '_')
    if config_id in _settings: return str(_settings[config_id])
    if id in _settings: return str(_settings[id])
    if id.startswith('provider.') or id.startswith('scrape.'): return 'true'
    if any(x in id for x in ['timeout', 'limit', 'count']): return '60'
    return ''

def setSetting(id, value=None, **kwargs):
    if value is None: value = kwargs.get('val') or kwargs.get('value')
    _settings[id] = str(value)
    try:
        with open(settingsFile, 'w') as f: json.dump(_settings, f)
    except: pass

_dialog_listener = None

def set_dialog_listener(listener):
    global _dialog_listener
    print(f"[DEBUG] control.py: setting dialog listener: {listener}")
    _dialog_listener = listener

def infoDialog(message, heading=None, sound=False, icon=None, time=None):
    if _dialog_listener: _dialog_listener.infoDialog(str(message), str(heading or ""), bool(sound), str(icon or ""))

infodialog = infoDialog

def okDialog(message, heading=None):
    if _dialog_listener: return _dialog_listener.okDialog(str(message), str(heading or ""))
    return True

okdialog = okDialog

def yesnoDialog(message, heading=None, nolabel=None, yeslabel=None):
    if _dialog_listener: return _dialog_listener.yesnoDialog(str(message), str(heading or ""), str(nolabel or ""), str(yeslabel or ""))
    return True

def selectDialog(options, heading=None):
    if _dialog_listener and hasattr(_dialog_listener, 'selectDialog'):
        return _dialog_listener.selectDialog(options, str(heading or "Select Option"))
    return -1

yesnodialog = yesnoDialog

def captchaDialog(image_bytes, heading=None):
    print(f"[DEBUG] control.captchaDialog: starting for {heading}")
    if _dialog_listener:
        try:
            res = _dialog_listener.captchaDialog(image_bytes, str(heading or "Captcha Required"))
            print(f"[DEBUG] control.captchaDialog: listener returned: {res}")
            return res
        except Exception as e:
            print(f"[DEBUG] control.captchaDialog: listener error: {e}")
    else:
        print("[DEBUG] control.captchaDialog: NO LISTENER SET")
    return None

def lang(id): return f"Lang_{id}"
def condVisibility(expr): return False
def sleep(ms):
    import time
    time.sleep(ms / 1000.0)

def item(label=None, path=None):
    class ListItem:
        def __init__(self, l, p):
            self.label = l; self.path = p; self.properties = {}
        def setProperty(self, k, v): self.properties[k] = v
        def setInfo(self, type, infoLabels): pass
    return ListItem(label, path)

def resolve(handle=None, succeeded=True, listitem=None): pass

class Player:
    def isPlayingVideo(self): return False
player = Player()
def getCurrentDialogId(): return 0
def execute(command): pass
def addon(id):
    class Addon:
        def getSetting(self, key): return "0"
    return Addon()
def infoLabel(id): return ""
def apiLanguage(): return {"tmdb": "en", "youtube": "en"}
def getKodiVersion(): return 20

providercacheFile = os.path.join(dataPath, 'providers.db')
cacheFile = os.path.join(dataPath, 'cache.db')
metacacheFile = os.path.join(dataPath, 'meta.db')
searchFile = os.path.join(dataPath, 'search.db')

# -*- coding: utf-8 -*-
# Module: default
# Author: jurialmunkey
# License: GPL v.3 https://www.gnu.org/copyleft/gpl.html
#<addon id="script.module.infotagger" name="InfoTagger" provider-name="jurialmunkey" version="0.0.7">
LOGINFO = 1
def kodi_log(msg, level=LOGINFO): print(msg)

class Actor:
    def __init__(self, **kwargs): pass
class VideoStreamDetail:
    def __init__(self, **kwargs): pass
class AudioStreamDetail:
    def __init__(self, **kwargs): pass
class SubtitleStreamDetail:
    def __init__(self, **kwargs): pass

def set_info_tag(*args, **kwargs): pass
def ListItemInfoTag(*args, **kwargs): 
    class DummyTag:
        def set_info(self, *a, **k): pass
    return DummyTag()
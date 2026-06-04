# -*- coding: utf-8 -*-

'''
 ***********************************************************
 * The Crew Add-on
 *
 *
 * @file crewruntime.py
 * @package script.module.thecrew
 *
 * @copyright (c) 2023, The Crew
 * @license GNU General Public License, version 3 (GPL-3.0)
 *
 ********************************************************cm*
'''


import os
import sys
import re
import platform
from datetime import datetime
from io import open
#import traceback
from inspect import getframeinfo, stack


#from resources.lib.modules import control


class CrewRuntime:
    transpath = lambda path: path.replace('special://logpath', os.path.dirname(os.path.abspath(__file__)))
    
    def __init__(self):
        self.name = "TheCrew"
        self.kodiversion = "20.0"
        self.pluginversion = "1.0"
        self.moduleversion = "1.0"
        self.platform = "Windows"
    def log(self, msg, trace=0): print(f"[CrewRuntime] {msg}")
    def get_setting(self, setting): return ""
    def set_setting(self, setting, val): pass
    def in_addon(self): return False
c = CrewRuntime()
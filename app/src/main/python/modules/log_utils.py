# -*- coding: utf-8 -*-

import logging

LOGDEBUG = logging.DEBUG
LOGINFO = logging.INFO
LOGNOTICE = logging.INFO
LOGWARNING = logging.WARNING
LOGERROR = logging.ERROR
LOGFATAL = logging.CRITICAL
LOGNONE = logging.NOTSET

logging.basicConfig(level=logging.DEBUG, format='[Scraper] %(message)s')

def log(msg, trace=0, level=LOGDEBUG):
    logging.log(level, str(msg))
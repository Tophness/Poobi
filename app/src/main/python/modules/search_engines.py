# -*- coding: utf-8 -*-

import re
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import cleantitle
from resources.lib.modules import log_utils

_search_session = requests.Session()
_adapter = HTTPAdapter(pool_connections=10, pool_maxsize=20, max_retries=Retry(total=2, backoff_factor=0.3))
_search_session.mount("https://", _adapter)
_search_session.mount("http://", _adapter)

def bing(search_query, parse=False):
    try:
        if not search_query:
            return
        search_headers = {'User-Agent': client.UserAgent, 'Referer': 'https://www.bing.com'}
        search_url = 'https://www.bing.com/search?q=%s' % search_query
        search_html = client.scrapePage(search_url, headers=search_headers).text
        if parse:
            results = client_utils.parseDOM(search_html, 'li', attrs={'class': 'b_algo'})
            results = [(client_utils.parseDOM(i, 'cite'), client_utils.parseDOM(i, 'a')) for i in results]
            results = [(i[0][0], i[1][0]) for i in results if len(i[0]) > 0 and len(i[1]) > 0]
            results = [(client_utils.remove_codes(i[0]), i[1]) for i in results]
            return results
        return search_html
    except:
        log_utils.log('bing', 1)
        return

def duckduckgo(search_query):
    try:
        if not search_query:
            return
        search_data = {'q': search_query}
        search_headers = {'User-Agent': client.UserAgent, 'Referer': 'https://duckduckgo.com'}
        search_url = 'https://html.duckduckgo.com/html/'
        search_html = _search_session.post(search_url, headers=search_headers, data=search_data, verify=False, timeout=10).content
        return search_html
    except:
        log_utils.log('duckduckgo', 1)
        return

def google(search_query):
    try:
        if not search_query:
            return
        search_headers = {'User-Agent': client.UserAgent, 'Referer': 'https://www.google.com'}
        search_url = 'https://www.google.com/search?q=%s' % search_query
        search_html = client.scrapePage(search_url, headers=search_headers).text
        return search_html
    except:
        log_utils.log('google', 1)
        return

def startpage(search_query):
    try:
        if not search_query:
            return
        search_headers = {'User-Agent': client.UserAgent, 'Referer': 'https://www.startpage.com'}
        search_url = 'https://www.startpage.com/do/search?q=%s' % search_query
        search_html = client.scrapePage(search_url, headers=search_headers).text
        return search_html
    except:
        log_utils.log('startpage', 1)
        return

def swisscows(search_query):
    try:
        if not search_query:
            return
        search_headers = {'User-Agent': client.UserAgent, 'Referer': 'https://swisscows.com'}
        search_url = 'https://swisscows.com/en/web?query=%s' % search_query
        search_html = client.scrapePage(search_url, headers=search_headers).text
        return search_html
    except:
        log_utils.log('swisscows', 1)
        return

def yahoo(search_query):
    try:
        if not search_query:
            return
        search_headers = {'User-Agent': client.UserAgent, 'Referer': 'https://search.yahoo.com'}
        search_url = 'https://search.yahoo.com/search?p=%s' % search_query
        search_html = client.scrapePage(search_url, headers=search_headers).text
        return search_html
    except:
        log_utils.log('yahoo', 1)
        return

def make_search_query(domain, title, imdb=None, year=None, season=None, episode=None, domainprefix='&sites='):
    try:
        if not domain:
            return
        search_title = imdb if not imdb == None else cleantitle.get_plus(title)
        search_term = '%s+%s' % (search_title, year) if not year == None else search_title
        if not (season and episode) == None:
            season = '%02d' % int(season)
            episode = '%02d' % int(episode)
            search_query = '%s+s%se%s+%s%s' % (search_term, str(season), str(episode), domainprefix, domain)
        elif not season == None:
            season = '%02d' % int(season)
            search_query = '%s+Season+%s+%s%s' % (search_term, str(season), domainprefix, domain)
        else:
            search_query = '%s+%s%s' % (search_term, domainprefix, domain)
        return search_query
    except:
        log_utils.log('make_search_query', 1)
        return
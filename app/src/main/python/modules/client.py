# -*- coding: utf-8 -*-

import re
import sys
import gzip
import random
import time

import requests
import simplejson as json
import six
from six.moves import range as x_range, urllib_parse

from resources.lib.modules import control
from resources.lib.modules import dom_parser
from resources.lib.modules import log_utils

try: # Py2
    from urlparse import urlparse, urljoin
    from urllib import quote, urlencode, quote_plus, addinfourl
    import cookielib
    import urllib2
    from cStringIO import StringIO
    from HTMLParser import HTMLParser
    unescape = HTMLParser().unescape
    HTTPError = urllib2.HTTPError
except ImportError: # Py3:
    from http import cookiejar as cookielib
    from html import unescape
    import urllib.request as urllib2
    from io import StringIO
    from urllib.parse import urlparse, urljoin, quote, urlencode, quote_plus
    from urllib.response import addinfourl
    from urllib.error import HTTPError
finally:
    urlopen = urllib2.urlopen
    Request = urllib2.Request

if six.PY2:
    _str = str
    str = unicode
    unicode = unicode
    basestring = basestring
    def bytes(b, encoding="ascii"):
        return _str(b)
elif six.PY3:
    bytes = bytes
    str = unicode = basestring = str


#CERT_FILE = control.transPath('special://xbmc/system/certs/cacert.pem')

_COOKIE_HEADER = "Cookie"
_HEADER_RE = re.compile(r"^([\w\d-]+?)=(.*?)$")

UserAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/114.0'
OldUserAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:97.0) Gecko/20100101 Firefox/97.0'
MobileUserAgent = 'Mozilla/5.0 (Android 10; Mobile; rv:83.0) Gecko/83.0 Firefox/83.0'

dnt_headers = {
    'User-Agent': UserAgent,
    'Accept': '*/*',
    'Accept-Encoding': 'identity;q=1, *;q=0',
    'Accept-Language': 'en-US,en;q=0.5',
    'Connection': 'keep-alive',
    'Pragma': 'no-cache',
    'Cache-Control': 'no-cache',
    'DNT': '1'
}


def _strip_url(url):
    if url.find('|') == -1:
        return (url, {})
    headers = url.split('|')
    target_url = headers.pop(0)
    out_headers = {}
    for h in headers:
        m = _HEADER_RE.findall(h)
        if not len(m):
            continue
        out_headers[m[0][0]] = urllib_parse.unquote_plus(m[0][1])
    return (target_url, out_headers)


def _url_with_headers(url, headers):
    if not len(headers.keys()):
        return url
    headers_arr = ["%s=%s" % (key, urllib_parse.quote_plus(value)) for key, value in six.iteritems(headers)]
    return "|".join([url] + headers_arr)


def strip_cookie_url(url):
    url, headers = _strip_url(url)
    if _COOKIE_HEADER in headers.keys():
        del headers[_COOKIE_HEADER]
    return _url_with_headers(url, headers)


def _add_request_header(_request, headers):
    try:
        if not headers:
            headers = {}
        if six.PY2:
            scheme = _request.get_type()
            host = _request.get_host()
        else:
            scheme = urllib_parse.urlparse(_request.get_full_url()).scheme
            host = _request.host
        referer = headers.get('Referer') if 'Referer' in headers else '%s://%s/' % (scheme, host)
        _request.add_unredirected_header('Host', host)
        _request.add_unredirected_header('Referer', referer)
        for key in headers:
            _request.add_header(key, headers[key])
    except:
        return


def _get_result(response, limit=None):
    if limit == '0':
        result = response.read(224 * 1024)
    elif limit:
        result = response.read(int(limit) * 1024)
    else:
        result = response.read(5242880)
    try:
        encoding = response.info().getheader('Content-Encoding')
    except:
        encoding = None
    if encoding == 'gzip':
        result = gzip.GzipFile(fileobj=StringIO(result)).read()
        #result = gzip.GzipFile(fileobj=six.BytesIO(result)).read() #ALT Way Saved.
    return result


def _basic_request(url, headers=None, post=None, timeout='10', limit=None):
    try:
        try:
            headers.update(headers)
        except:
            headers = {}
        if post is not None:
            post = post if six.PY2 else post.encode()
        request = Request(url, data=post)
        _add_request_header(request, headers)
        response = urlopen(request, timeout=int(timeout))
        return _get_result(response, limit)
    except:
        return


def request(url, close=True, redirect=True, error=False, verify=True, post=None, headers=None, mobile=False, XHR=False,
            limit=None, referer=None, cookie=None, compression=False, output='', timeout='10', as_bytes=False):
    try:
        url = six.ensure_text(url, errors='ignore')
    except Exception:
        pass
    if isinstance(post, dict):
        post = bytes(urlencode(post), encoding='utf-8')
    elif isinstance(post, str) and six.PY3:
        post = bytes(post, encoding='utf-8')
    try:
        handlers = []
        if output == 'cookie' or output == 'extended' or close is not True:
            cookies = cookielib.LWPCookieJar()
            handlers += [urllib2.HTTPHandler(), urllib2.HTTPSHandler(), urllib2.HTTPCookieProcessor(cookies)]
            opener = urllib2.build_opener(*handlers)
            urllib2.install_opener(opener)
        try:
            import platform
            is_XBOX = platform.uname()[1] == 'XboxOne'
        except Exception:
            is_XBOX = False
        if not verify and sys.version_info >= (2, 7, 12):
            try:
                import ssl
                ssl_context = ssl._create_unverified_context()
                handlers += [urllib2.HTTPSHandler(context=ssl_context)]
                opener = urllib2.build_opener(*handlers)
                urllib2.install_opener(opener)
            except Exception:
                pass
        elif verify and ((2, 7, 8) < sys.version_info < (2, 7, 12) or is_XBOX):
            try:
                import ssl
                try:
                    import _ssl
                    CERT_NONE = _ssl.CERT_NONE
                except Exception:
                    CERT_NONE = ssl.CERT_NONE
                ssl_context = ssl.create_default_context()
                ssl_context.check_hostname = False
                ssl_context.verify_mode = CERT_NONE
                handlers += [urllib2.HTTPSHandler(context=ssl_context)]
                opener = urllib2.build_opener(*handlers)
                urllib2.install_opener(opener)
            except Exception:
                pass
        try:
            headers.update(headers)
        except Exception:
            headers = {}
        if 'User-Agent' in headers:
            pass
        elif mobile is not True:
            headers['User-Agent'] = UserAgent
        else:
            headers['User-Agent'] = MobileUserAgent
        if 'Referer' in headers:
            pass
        elif referer is None:
            headers['Referer'] = '%s://%s/' % (urlparse(url).scheme, urlparse(url).netloc)
        else:
            headers['Referer'] = referer
        if not 'Accept-Language' in headers:
            headers['Accept-Language'] = 'en-US,en'
        if 'X-Requested-With' in headers:
            pass
        elif XHR is True:
            headers['X-Requested-With'] = 'XMLHttpRequest'
        if 'Cookie' in headers:
            pass
        elif cookie is not None:
            headers['Cookie'] = cookie
        if 'Accept-Encoding' in headers:
            pass
        elif compression and limit is None:
            headers['Accept-Encoding'] = 'gzip'
        if redirect is False:
            class NoRedirectHandler(urllib2.HTTPRedirectHandler):
                def http_error_302(self, reqst, fp, code, msg, head):
                    infourl = addinfourl(fp, head, reqst.get_full_url())
                    infourl.status = code
                    infourl.code = code
                    return infourl
                http_error_300 = http_error_302
                http_error_301 = http_error_302
                http_error_303 = http_error_302
                http_error_307 = http_error_302
            opener = urllib2.build_opener(NoRedirectHandler())
            urllib2.install_opener(opener)
            try:
                del headers['Referer']
            except Exception:
                pass
        req = urllib2.Request(url, data=post, headers=headers)
        try:
            response = urllib2.urlopen(req, timeout=int(timeout))
        except HTTPError as response:
            #log_utils.log('request-HTTPError (%s): %s' % (response.code, url))
            if response.code == 503:
                if 'cf-browser-verification' in response.read(5242880):
                    log_utils.log('client - url with cloudflare: ' + repr(url))
                    #log_utils.log('client - cfScrape Exception', 1)
                elif error is False:
                    return
            elif error is False:
                return
        if output == 'cookie':
            try:
                result = '; '.join(['%s=%s' % (i.name, i.value) for i in cookies])
            except Exception:
                pass
        elif output == 'response':
            if limit == '0':
                result = (str(response.code), response.read(224 * 1024))
            elif limit is not None:
                result = (str(response.code), response.read(int(limit) * 1024))
            else:
                result = (str(response.code), response.read(5242880))
        elif output == 'chunk':
            try:
                content = int(response.headers['Content-Length'])
            except Exception:
                content = (2049 * 1024)
            if content < (2048 * 1024):
                return
            result = response.read(16 * 1024)
        elif output == 'extended':
            try:
                cookie = '; '.join(['%s=%s' % (i.name, i.value) for i in cookies])
            except Exception:
                pass
            content = response.headers
            result_url = response.geturl()
            result = response.read(5242880)
            if not as_bytes:
                result = six.ensure_text(result, errors='ignore')
            return result, headers, content, cookie, result_url
        elif output == 'geturl':
            result = response.geturl()
        elif output == 'headers':
            content = response.headers
            if close:
                response.close()
            return content
        elif output == 'file_size':
            try:
                content = int(response.headers['Content-Length'])
            except Exception:
                content = '0'
            response.close()
            return content
        elif output == 'json':
            content = json.loads(response.read(5242880))
            response.close()
            return content
        else:
            if limit == '0':
                result = response.read(224 * 1024)
            elif limit is not None:
                if isinstance(limit, int):
                    result = response.read(limit * 1024)
                else:
                    result = response.read(int(limit) * 1024)
            else:
                result = response.read(5242880)
        if close is True:
            response.close()
        if not as_bytes:
            result = six.ensure_text(result, errors='ignore')
        return result
    except Exception as e:
        #log_utils.log('request-Error: (%s) => %s' % (str(e), url))
        #log_utils.log('request', 1)
        return


def scrapePage(url, referer=None, headers=None, post=None, cookie=None, timeout='10'):
    try:
        if not url:
            return
        url =  "https:" + url if url.startswith('//') else url
        with requests.Session() as session:
            if headers:
                session.headers.update(headers)
            if (referer and not 'Referer' in session.headers):
                session.headers.update({'Referer': referer})
            else:
                elements = urllib_parse.urlparse(url)
                base = '%s://%s' % (elements.scheme, (elements.netloc or elements.path))
                session.headers.update({'Referer': base})
            if (cookie and not 'Cookie' in session.headers): # not tested yet, just placed as a idea reminder.
                session.headers.update({'Cookie': cookie})
            if not 'User-Agent' in session.headers:
                session.headers.update({'User-Agent': UserAgent})
            if post:
                page = session.post(url, data=post, timeout=int(timeout))
            else:
                page = session.get(url, timeout=int(timeout))
            ###################################################################
            """## A ghetto fix for blockage that could probably be coded better.
            resp_code = str(page.status_code)
            resp_header = page.headers
            resp_server = resp_header['Server']
            if resp_code in ['403', '503'] and resp_server == 'cloudflare':
                #log_utils.log('scrapePage - url with cloudflare: ' + repr(url))
                corsproxy = 'https://corsproxy.io/?' + url
                #corsproxy = 'https://proxy.iamcdn.net/sub?url=' + url
                #corsproxy = 'https://api.allorigins.win/raw?url=' + url
                if post:
                    page = session.post(corsproxy, data=post, timeout=int(timeout))
                else:
                    page = session.get(corsproxy, timeout=int(timeout))
            """
            ###################################################################
            page.encoding = 'utf-8'
            #page.raise_for_status()  # Commented out to make trakt progress option work properly again lol
        return page
    except Exception as e:
        #log_utils.log('scrapePage-Error: (%s) => %s' % (str(e), url))
        #log_utils.log('scrapePage', 1)
        return


def url_ok(url): #  Old Code Saved.
    r = scrapePage(url)
    if r.status_code == 200 or r.status_code == 301:
        return True
    else:
        return False


def agent():
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/95.0.4638.69 Safari/537.36"



def randomagent():
    BR_VERS = [
        ['%s.0' % i for i in x_range(18, 50)],
        ['37.0.2062.103', '37.0.2062.120', '37.0.2062.124', '38.0.2125.101', '38.0.2125.104', '38.0.2125.111',
         '39.0.2171.71', '39.0.2171.95', '39.0.2171.99', '40.0.2214.93', '40.0.2214.111', '40.0.2214.115',
         '42.0.2311.90', '42.0.2311.135', '42.0.2311.152', '43.0.2357.81', '43.0.2357.124', '44.0.2403.155',
         '44.0.2403.157', '45.0.2454.101', '45.0.2454.85', '46.0.2490.71', '46.0.2490.80', '46.0.2490.86',
         '47.0.2526.73', '47.0.2526.80', '48.0.2564.116', '49.0.2623.112', '50.0.2661.86', '51.0.2704.103',
         '52.0.2743.116', '53.0.2785.143', '54.0.2840.71', '61.0.3163.100'],
        ['11.0'],
        ['8.0', '9.0', '10.0', '10.6']]
    WIN_VERS = ['Windows NT 10.0', 'Windows NT 7.0', 'Windows NT 6.3', 'Windows NT 6.2',
                'Windows NT 6.1', 'Windows NT 6.0', 'Windows NT 5.1', 'Windows NT 5.0']
    FEATURES = ['; WOW64', '; Win64; IA64', '; Win64; x64', '']
    RAND_UAS = ['Mozilla/5.0 ({win_ver}{feature}; rv:{br_ver}) Gecko/20100101 Firefox/{br_ver}',
                'Mozilla/5.0 ({win_ver}{feature}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/{br_ver} Safari/537.36',
                'Mozilla/5.0 ({win_ver}{feature}; Trident/7.0; rv:{br_ver}) like Gecko',
                'Mozilla/5.0 (compatible; MSIE {br_ver}; {win_ver}{feature}; Trident/6.0)']
    index = random.randrange(len(RAND_UAS))
    return RAND_UAS[index].format(
        win_ver=random.choice(WIN_VERS),
        feature=random.choice(FEATURES),
        br_ver=random.choice(BR_VERS[index]))
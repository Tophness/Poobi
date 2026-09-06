# -*- coding: utf-8 -*-
import logging

try:
    from curl_cffi import requests as curlm
    CURL_CFFI_AVAILABLE = True
except Exception as e:
    import requests as curlm
    CURL_CFFI_AVAILABLE = False
    logging.error(f"cloudscraper sub shim: curl_cffi fallback to requests. Error: {e}")

from .exceptions import (
    CloudflareLoopProtection,
    CloudflareCode1020,
    CloudflareIUAMError,
    CloudflareSolveError,
    CloudflareChallengeError,
    CloudflareReCaptchaError,
    CloudflareReCaptchaProvider
)

class CloudScraper(curlm.Session):
    def __init__(self, *args, **kwargs):
        for key in ['debug', 'delay', 'cipherSuite', 'ssl_context', 'interpreter',
                    'recaptcha', 'requestPreHook', 'requestPostHook', 'source_address',
                    'doubleDown', 'allow_brotli', 'browser', 'solveDepth']:
            kwargs.pop(key, None)

        if CURL_CFFI_AVAILABLE and 'impersonate' not in kwargs:
            kwargs['impersonate'] = 'chrome110'
        super(CloudScraper, self).__init__(*args, **kwargs)

    @classmethod
    def create_scraper(cls, sess=None, **kwargs):
        scraper = cls(**kwargs)
        if sess:
            if hasattr(sess, 'cookies'):
                try: scraper.cookies.update(sess.cookies)
                except: pass
            if hasattr(sess, 'headers'):
                scraper.headers.update(sess.headers)
        return scraper

    @classmethod
    def get_tokens(cls, url, **kwargs):
        scraper = cls.create_scraper(**kwargs)
        try:
            resp = scraper.get(url, timeout=kwargs.get('timeout', 10))
            if hasattr(resp, 'cookies') and hasattr(resp.cookies, 'get_dict'):
                return resp.cookies.get_dict(), scraper.headers.get("User-Agent")
            return {}, scraper.headers.get("User-Agent")
        except Exception as e:
            logging.error(f"cloudscraper sub shim get_tokens error: {e}")
            return {}, scraper.headers.get("User-Agent", "")

    @classmethod
    def get_cookie_string(cls, url, **kwargs):
        tokens, ua = cls.get_tokens(url, **kwargs)
        return "; ".join([f"{k}={v}" for k, v in tokens.items()]), ua

create_scraper = CloudScraper.create_scraper
get_tokens = CloudScraper.get_tokens
get_cookie_string = CloudScraper.get_cookie_string

# -*- coding: utf-8 -*-
import logging
from curl_cffi import requests as curlm

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
        # Strip out cfscrape/cloudscraper specific kwargs that curl_cffi doesn't support
        for key in ['debug', 'delay', 'cipherSuite', 'ssl_context', 'interpreter',
                    'recaptcha', 'requestPreHook', 'requestPostHook', 'source_address',
                    'doubleDown', 'allow_brotli', 'browser', 'solveDepth']:
            kwargs.pop(key, None)

        # Default to chrome impersonation if not specified
        if 'impersonate' not in kwargs:
            kwargs['impersonate'] = 'chrome110'

        super(CloudScraper, self).__init__(*args, **kwargs)

    @classmethod
    def create_scraper(cls, sess=None, **kwargs):
        scraper = cls(**kwargs)
        if sess:
            if hasattr(sess, 'cookies'):
                try:
                    scraper.cookies.update(sess.cookies)
                except:
                    pass
            if hasattr(sess, 'headers'):
                scraper.headers.update(sess.headers)
        return scraper

    @classmethod
    def get_tokens(cls, url, **kwargs):
        scraper = cls.create_scraper(**kwargs)
        try:
            resp = scraper.get(url, timeout=kwargs.get('timeout', 10))
            return resp.cookies.get_dict(), scraper.headers.get("User-Agent")
        except Exception as e:
            logging.error(f"cloudscraper shim error: {e}")
            return {}, scraper.headers.get("User-Agent", "")

    @classmethod
    def get_cookie_string(cls, url, **kwargs):
        tokens, ua = cls.get_tokens(url, **kwargs)
        return "; ".join([f"{k}={v}" for k, v in tokens.items()]), ua

create_scraper = CloudScraper.create_scraper
get_tokens = CloudScraper.get_tokens
get_cookie_string = CloudScraper.get_cookie_string

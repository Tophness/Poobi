# -*- coding: utf-8 -*-
"""
    resolveurl XBMC Addon
    Copyright (C) 2016 tknorris
    Derived from Shani's LPro Code (https://github.com/Shani-08/ShaniXBMCWork2/blob/master/plugin.video.live.streamspro/unCaptcha.py)

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.

    reusable captcha methods
"""
import re
import os
from resolveurl import common

from resources.lib.modules import control

logger = common.log_utils.Logger.get_logger(__name__)
logger.disable()


class cInputWindow(object):
    def __init__(self, *args, **kwargs):
        self.captcha_url = kwargs.get('captcha')
        self.msg = kwargs.get('msg')
        self.iteration = kwargs.get('iteration')

    def get(self):
        # captcha_url is a URL, we need to fetch it first to show in dialog
        try:
            image_bytes = common.Net().http_GET(self.captcha_url).nodecode(True).content
            res = control.captchaDialog(image_bytes, self.msg)
            if res:
                # Recaptcha fallback expects indices if it's a grid, but here we'll just return what user typed
                # or if they clicked, we might need to translate that to indices.
                # For simplicity, if they clicked, return COORD string.
                return res
        except:
            pass
        return []


class UnCaptchaReCaptcha:
    net = common.Net()

    def processCaptcha(self, key, lang):
        headers = {'Referer': 'https://www.google.com/recaptcha/api2/demo', 'Accept-Language': lang}
        html = self.net.http_GET('http://www.google.com/recaptcha/api/fallback?k=%s' % (key), headers=headers).content
        token = ''
        iteration = 0
        while True:
            payload = re.findall('"(/recaptcha/api2/payload[^"]+)', html)
            iteration += 1
            message = re.findall('<label[^>]+class="fbc-imageselect-message-text"[^>]*>(.*?)</label>', html)
            if not message:
                message = re.findall('<div[^>]+class="fbc-imageselect-message-error">(.*?)</div>', html)
            if not message:
                token = re.findall(r'"this\.select\(\)">(.*?)</textarea>', html)[0]
                if token:
                    logger.log_debug('Captcha Success: %s' % (token))
                else:
                    logger.log_debug('Captcha Failed: %s')
                break
            else:
                message = message[0]
                payload = payload[0]

            cval = re.findall(r'name="c"\s+value="([^"]+)', html)[0]
            captcha_imgurl = 'https://www.google.com%s' % (payload.replace('&amp;', '&'))
            message = re.sub('</?(div|strong)[^>]*>', '', message)
            oSolver = cInputWindow(captcha=captcha_imgurl, msg=message, iteration=iteration)
            captcha_response = oSolver.get()
            if not captcha_response:
                break

            data = {'c': cval, 'response': captcha_response}
            html = self.net.http_POST("http://www.google.com/recaptcha/api/fallback?k=%s" % (key), form_data=data, headers=headers).content
        return token

"""
    Copyright (C) 2023 MrDini123
    https://github.com/movieshark

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
"""

import os
from resolveurl import common
from resources.lib.modules import control

class CaptchaWindow(object):
    def __init__(self, image, width, height):
        self.image = image
        self.width = width
        self.height = height
        self.solution_x = 0
        self.solution_y = 0
        self.finished = False

    def close(self):
        pass

    def doModal(self):
        # image is binary data
        print(f"[DEBUG] CaptchaWindow.doModal: starting control.captchaDialog (image size: {len(self.image)})")
        res = control.captchaDialog(self.image, "Select point in image")
        print(f"[DEBUG] CaptchaWindow.doModal: control.captchaDialog returned: {res}")
        if res and res.startswith("COORD:"):
            coords = res[6:].split(",")
            self.solution_x = int(coords[0])
            self.solution_y = int(coords[1])
            self.finished = True
        elif res:
            # Maybe it returned text? Not for coordinate captcha though.
            print(f"[DEBUG] CaptchaWindow.doModal: unexpected response format: {res}")
            self.finished = False
        else:
            print(f"[DEBUG] CaptchaWindow.doModal: user cancelled or error occurred")
            self.finished = False

    def get(self): 
        # Since we have no UI, prompt in terminal
        return input("Captcha required. Enter solution: ")

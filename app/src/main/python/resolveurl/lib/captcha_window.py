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

class CaptchaWindow(object):
    def __init__(self, image, width, height): pass
    def close(self): pass
    def get(self): 
        # Since we have no UI, prompt in terminal
        return input("Captcha required. Enter solution: ")
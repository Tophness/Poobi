"""
    ResolveURL Addon for Kodi
    Copyright (C) 2016 t0mm0, tknorris

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
"""
import six
from resolveurl.lib import log_utils

logger = log_utils.Logger.get_logger(__name__)
DIALOG_XML = 'ProgressDialog.xml' if six.PY2 else 'ProgressDialog2.xml'

class ProgressDialog(object):
    dialog = None
    def get_path(self): return ""
    def create(self, heading, line1='', line2='', line3=''): print(f"[{heading}] {line1} {line2}")
    def update(self, percent, line1='', line2='', line3=''): pass
    def iscanceled(self): return False
    def close(self): pass
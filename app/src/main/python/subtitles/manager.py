"""
subtitles/manager.py
~~~~~~~~~~~~~~~~~~~~
Thin wrapper around the local a4kSubtitles package (subtitles/a4kSubtitles).
Handles search and download for all subtitle services.
"""
import os
import sys
import shutil
import time

# The a4kSubtitles package lives right next to this file.
SUBTITLES_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT  = os.path.dirname(SUBTITLES_DIR)

# Add the subtitles/ folder to sys.path so "import a4kSubtitles" works.
if SUBTITLES_DIR not in sys.path:
    sys.path.insert(0, SUBTITLES_DIR)

_api_instance = None

def _get_api():
    global _api_instance
    if _api_instance is None:
        from api import A4kSubtitlesApi
        _api_instance = A4kSubtitlesApi({'kodi': True})
    return _api_instance


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def search_subtitles(imdb_id, title, year, season=None, episode=None,
                     tvshow=None, settings=None):
    """
    Search for subtitles using a4kSubtitles.

    Parameters
    ----------
    imdb_id  : str   e.g. 'tt1375666'
    title    : str   Movie/episode title
    year     : int   Release year
    season   : int   Season number (TV shows)
    episode  : int   Episode number (TV shows)
    tvshow   : str   TV show title (TV shows)
    settings : dict  Keys: opensubtitles_username, opensubtitles_password,
                           opensubtitles_org_username, opensubtitles_org_password,
                           subdl_apikey, subsource_apikey,
                           subtitles_languages, subtitles_limit,
                           addic7ed_enabled, bsplayer_enabled,
                           opensubtitles_enabled, opensubtitles_org_enabled,
                           podnadpisi_enabled, subdl_enabled, subsource_enabled
    """
    api = _get_api()

    if settings is None:
        settings = {}

    # Build the settings dict the addon expects.
    sub_settings = {
        'addic7ed.enabled':           'true' if settings.get('addic7ed_enabled',           True) else 'false',
        'bsplayer.enabled':           'true' if settings.get('bsplayer_enabled',           False) else 'false',
        'opensubtitles.enabled':      'true' if settings.get('opensubtitles_enabled',      True)  else 'false',
        'opensubtitles_org.enabled':  'true' if settings.get('opensubtitles_org_enabled',  False) else 'false',
        'podnadpisi.enabled':         'true' if settings.get('podnadpisi_enabled',         False) else 'false',
        'subdl.enabled':              'true' if settings.get('subdl_enabled',              True)  else 'false',
        'subsource.enabled':          'true' if settings.get('subsource_enabled',          True)  else 'false',
        'opensubtitles.username':     settings.get('opensubtitles_username', ''),
        'opensubtitles.password':     settings.get('opensubtitles_password', ''),
        'opensubtitles_org.username': settings.get('opensubtitles_org_username', ''),
        'opensubtitles_org.password': settings.get('opensubtitles_org_password', ''),
        'subdl.apikey':               settings.get('subdl_apikey',    ''),
        'subsource.apikey':           settings.get('subsource_apikey', ''),
        'general.results_limit':      str(settings.get('subtitles_limit', 20)),
        'general.use_chardet':        'true',
    }

    # Languages
    languages_str = settings.get('subtitles_languages', 'English')
    languages = ','.join(
        lang.strip().capitalize()
        for lang in languages_str.split(',')
        if lang.strip()
    )
    if not languages:
        languages = 'English'

    params = {
        'languages':       languages,
        'preferredlanguage': languages.split(',')[0],
    }

    season_str  = str(season)  if season  is not None else ''
    episode_str = str(episode) if episode is not None else ''

    if season and episode:
        filename = f"{tvshow or title}.S{str(season).zfill(2)}E{str(episode).zfill(2)}.1080p.mkv"
    else:
        filename = f"{title}.{year}.1080p.mkv"

    video_meta = {
        'imdb_id':  imdb_id,
        'title':    title,
        'year':     str(year),
        'tvshow':   tvshow or '',
        'season':   season_str,
        'episode':  episode_str,
        'filename': filename,
        'filesize': '',
        'filehash': '',
    }

    return api.search(params, settings=sub_settings, video_meta=video_meta)


def download_subtitle(service_name, action_args, settings=None):
    """
    Download a subtitle file and copy it to userdata/subtitles/.

    Returns the local path to the downloaded subtitle file, or None.
    """
    api = _get_api()

    # Map display name back to internal service name if necessary
    # Search results often return the display name (e.g. 'OpenSubtitles [.com]')
    # but the downloader needs the internal key (e.g. 'opensubtitles')
    internal_name = service_name
    for key, service in api.core.services.items():
        if getattr(service, 'display_name', None) == service_name:
            internal_name = key
            break

    if settings is None:
        settings = {}

    sub_settings = {
        'opensubtitles.username':     settings.get('opensubtitles_username', ''),
        'opensubtitles.password':     settings.get('opensubtitles_password', ''),
        'opensubtitles_org.username': settings.get('opensubtitles_org_username', ''),
        'opensubtitles_org.password': settings.get('opensubtitles_org_password', ''),
        'subdl.apikey':               settings.get('subdl_apikey',    ''),
        'subsource.apikey':           settings.get('subsource_apikey', ''),
        'general.use_chardet':        'true',
    }

    download_params = {
        'service_name': internal_name,
        'action_args':  action_args,
    }

    temp_filepath = api.download(download_params, settings=sub_settings)
    if temp_filepath and os.path.exists(temp_filepath):
        userdata_sub_dir = os.path.join(PROJECT_ROOT, 'userdata', 'subtitles')
        os.makedirs(userdata_sub_dir, exist_ok=True)
        dest_filepath = os.path.join(userdata_sub_dir, os.path.basename(temp_filepath))
        shutil.copy2(temp_filepath, dest_filepath)
        return dest_filepath
    return None

def purge_old_subtitles(retention_days):
    """
    Remove subtitle files in userdata/subtitles older than retention_days.
    If retention_days is 0, all subtitles are purged.
    """
    userdata_sub_dir = os.path.join(PROJECT_ROOT, 'userdata', 'subtitles')
    if not os.path.exists(userdata_sub_dir):
        return

    now = time.time()
    for filename in os.listdir(userdata_sub_dir):
        filepath = os.path.join(userdata_sub_dir, filename)
        if not os.path.isfile(filepath):
            continue

        file_age_days = (now - os.path.getmtime(filepath)) / (24 * 3600)
        if file_age_days > retention_days:
            try:
                os.remove(filepath)
            except Exception:
                pass

# -*- coding: utf-8 -*-

__url = 'https://www.addic7ed.com'

def __search_show_id(core, meta):
    try:
        search_url = 'https://www.addic7ed.com/search.php'
        headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36'
        }
        req = {
            'method': 'GET',
            'url': search_url,
            'params': {'search': meta.tvshow, 'Submit': 'Search'},
            'headers': headers
        }
        response = core.request.execute(core, req, progress=False)
        if not response or response.status_code != 200:
            return ''

        redirected_url = response.url
        match = core.re.search(r'/show/(\d+)', redirected_url)
        if match:
            return match.group(1)

        html = response.text
        match_show = core.re.search(r'href=["\']/show/(\d+)["\']', html)
        if match_show:
            return match_show.group(1)

        match_ajax = core.re.search(r'show=(\d+)', html)
        if match_ajax:
            return match_ajax.group(1)

        links = core.re.findall(r'href=["\'](?:https://www.addic7ed.com)?/?show/(\d+)["\'][^>]*>(.*?)</a>', html, core.re.IGNORECASE)
        for show_id, link_text in links:
            clean_link_text = core.re.sub(r'<[^>]+>', '', link_text).strip().lower()
            if clean_link_text == meta.tvshow.lower():
                return show_id

        all_show_ids = core.re.findall(r'/show/(\d+)', html)
        if all_show_ids:
            return all_show_ids[0]

    except Exception as e:
        core.logger.error('addic7ed - fallback search failed: %s' % e)
    return ''

def __get_show_id(core, service_name, meta):
    service = core.services[service_name]
    tvshows = core.data[service_name].tvshows

    title = '%s (%s)' % (meta.tvshow, meta.tvshow_year)
    tvshow_id = tvshows.get(title, '')
    if tvshow_id == '':
        title = meta.tvshow
        tvshow_id = tvshows.get(meta.tvshow, '')

    if tvshow_id == '':
        core.logger.debug('addic7ed - show not found in tvshows.json, performing fallback search for: %s' % meta.tvshow)
        tvshow_id = __search_show_id(core, meta)
        if tvshow_id != '':
            core.logger.debug('addic7ed - successfully resolved show ID: %s' % tvshow_id)
            tvshows[title] = tvshow_id

    service.context.referer = '%s/serie/%s/%s/%s/%s' % (__url, title, meta.season, meta.episode, meta.title)
    service.context.referer = service.context.referer.replace(' ', '_')
    return tvshow_id

def __get_language_ids(core, service_name, meta):
    languages = core.data[service_name].languages

    lang_ids = []
    for lang in meta.languages:
        lang_id = languages.get(lang, '')
        if lang_id != '':
            lang_ids.append(lang_id)

    if len(lang_ids) == 0:
        lang_ids = '1'

    return '|'.join(lang_ids)

def build_search_requests(core, service_name, meta):
    if meta.is_movie:
        return []

    if meta.tvshow_year_thread:
        meta.tvshow_year_thread.join()
    if not meta.tvshow_year:
        return []

    tvshow_id = __get_show_id(core, service_name, meta)
    if tvshow_id == '':
        return []

    params = {
        'show': tvshow_id,
        'season': meta.season,
        'langs': '|%s|' % __get_language_ids(core, service_name, meta),
    }

    request = {
        'method': 'GET',
        'url': '%s/ajax_loadShow.php' % __url,
        'params': params
    }

    return [request]

def parse_search_response(core, service_name, meta, response):
    try:
        results = response.text.split('<tr')
    except:
        return []

    service = core.services[service_name]

    pattern = (
        r'<td>(\d+)</td>' +
        r'\s*?<td>(\d+)</td>' +
        r'\s*?<td><a[^>]*?>.*?</a></td>' +
        r'\s*?<td>([^<]+)</td>' +
        r'\s*?<td[^>]*?>([^<]*)</td>' +
        r'\s*?<td[^>]*?>[^<]*</td>' +
        r'\s*?<td[^>]*?>(.*?)</td>' +
        r'\s*?<td[^>]*?>.*?</td>' +
        r'\s*?<td[^>]*?>.*?</td>' +
        r'\s*?<td[^>]*?>\s*?<a[^>]*?href=\"(.*?)\"[^>]*?>.*?</a>\s*?</td>'
    )
    regex_pattern = core.re.compile(pattern, core.re.DOTALL)

    def map_result(result):
        match = core.re.search(regex_pattern, result)
        if not match:
            return None

        season = match.group(1)
        episode = match.group(2)

        if meta.season != season or meta.episode != episode:
            return None

        lang = core.utils.get_lang_id(match.group(3), core.kodi.xbmc.ENGLISH_NAME)
        if lang not in meta.languages:
            return None

        lang_code = core.utils.get_lang_id(lang, core.kodi.xbmc.ISO_639_1)

        release_id = match.group(4)
        name = '%s.S%sE%s.%s.srt' % (meta.tvshow, meta.season.zfill(2), meta.episode.zfill(2), release_id)
        hearing_impaired = match.group(5)
        url = __url + match.group(6)

        return {
            'service_name': service_name,
            'service': service.display_name,
            'lang': lang,
            'name': name,
            'rating': 0,
            'lang_code': lang_code,
            'sync': 'true' if release_id in meta.title else 'false',
            'impaired': 'true' if hearing_impaired != '' else 'false',
            'color': 'deepskyblue',
            'action_args': {
                'url': url,
                'lang': lang,
                'filename': name,
                'referer': service.context.referer,
                'raw': True
            }
        }

    return list(filter(lambda v: v, map(map_result, results)))

def build_download_request(core, service_name, args):
    request = {
        'method': 'GET',
        'url': args['url'],
        'headers': {
            'referer': args['referer']
        }
    }

    return request
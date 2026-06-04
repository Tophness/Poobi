# -*- coding: utf-8 -*-

try:
    import xmlrpc.client as xmlrpc
except ImportError:
    import xmlrpclib as xmlrpc

__api_url = 'https://api.opensubtitles.org/xml-rpc'
# Use the whitelisted XBMC Subtitles Unofficial User-Agent
__user_agent = 'XBMC_Subtitles_Unofficial_v1.0.1'
__date_format = '%Y-%m-%d %H:%M:%S'

def build_auth_request(core, service_name):
    cache = core.cache.get_tokens_cache()
    token_cache = cache.get(service_name, None)
    if token_cache is not None and 'ttl' in token_cache:
        token_ttl = core.datetime.fromtimestamp(core.time.mktime(core.time.strptime(token_cache['ttl'], __date_format)))
        if token_ttl > core.datetime.now():
            return

    cache.pop(service_name, None)
    core.cache.save_tokens_cache(cache)

    username = core.kodi.get_setting(service_name, 'username')
    password = core.kodi.get_setting(service_name, 'password')

    xml_data = xmlrpc.dumps((username, password, "en", __user_agent), methodname='LogIn')

    request = {
        'method': 'POST',
        'url': __api_url,
        'data': xml_data,
        'headers': {
            'Content-Type': 'text/xml',
            'User-Agent': __user_agent,
        }
    }

    return request

def parse_auth_response(core, service_name, response):
    if response.status_code != 200 or not response.text:
        return

    try:
        params, methodname = xmlrpc.loads(response.text)
        result = params[0]
        token = result.get('token')
        status = result.get('status')
    except Exception as exc:
        core.logger.error('%s - failed to parse auth: %s' % (service_name, exc))
        return

    # If login fails (due to wrong or .com credentials), fall back to anonymous session
    if not token or '200' not in status:
        core.logger.debug('%s - LogIn with credentials failed (%s). Retrying anonymously...' % (service_name, status))
        try:
            xml_data = xmlrpc.dumps(("", "", "en", __user_agent), methodname='LogIn')
            req = {
                'method': 'POST',
                'url': __api_url,
                'data': xml_data,
                'headers': {
                    'Content-Type': 'text/xml',
                    'User-Agent': __user_agent,
                }
            }
            anon_resp = core.request.execute(core, req, progress=False)
            if anon_resp and anon_resp.status_code == 200:
                params, methodname = xmlrpc.loads(anon_resp.text)
                result = params[0]
                token = result.get('token')
                status = result.get('status')
        except Exception as exc:
            core.logger.error('%s - anonymous login fallback failed: %s' % (service_name, exc))
            return

    if not token or '200' not in status:
        core.logger.error('%s - All LogIn attempts failed: %s' % (service_name, status))
        return

    token_cache = {
        'token': token,
        'ttl': (core.datetime.now() + core.timedelta(hours=2)).strftime(__date_format),
    }

    cache = core.cache.get_tokens_cache()
    cache[service_name] = token_cache
    core.cache.save_tokens_cache(cache)

def build_search_requests(core, service_name, meta):
    cache = core.cache.get_tokens_cache()
    token_cache = cache.get(service_name, None)
    token = token_cache['token'] if (token_cache and 'token' in token_cache) else ""

    sublanguageid = ",".join(core.utils.get_lang_ids(meta.languages, core.kodi.xbmc.ISO_639_2))

    searchlist = []

    # 1. Match by moviehash
    if meta.filehash and meta.filesize:
        searchlist.append({
            'sublanguageid': sublanguageid,
            'moviehash': meta.filehash,
            'moviebytesize': str(meta.filesize)
        })

    # 2. Match by imdbid
    if meta.imdb_id:
        imdb_id = meta.imdb_id.replace('tt', '')
        search_dict = {
            'sublanguageid': sublanguageid,
            'imdbid': imdb_id
        }
        if meta.is_tvshow:
            search_dict.update({
                'season': int(meta.season) if meta.season.isdigit() else 0,
                'episode': int(meta.episode) if meta.episode.isdigit() else 0
            })
        searchlist.append(search_dict)

    # 3. Match by query
    if meta.is_tvshow:
        query = "%s S%.2dE%.2d" % (meta.tvshow, int(meta.season) if meta.season.isdigit() else 0, int(meta.episode) if meta.episode.isdigit() else 0)
    else:
        query = "%s %s" % (meta.title, meta.year)

    searchlist.append({
        'sublanguageid': sublanguageid,
        'query': query
    })

    xml_data = xmlrpc.dumps((token, searchlist), methodname='SearchSubtitles')

    request = {
        'method': 'POST',
        'url': __api_url,
        'data': xml_data,
        'headers': {
            'Content-Type': 'text/xml',
            'User-Agent': __user_agent,
        }
    }

    return [request]

def parse_search_response(core, service_name, meta, response):
    if response.status_code != 200 or not response.text:
        return []

    try:
        params, methodname = xmlrpc.loads(response.text)
        results = params[0].get('data', [])
    except Exception as exc:
        core.logger.error('%s - failed to parse search results: %s' % (service_name, exc))
        return []

    if not results:
        return []

    service = core.services[service_name]

    mapped_results = []
    for result in results:
        language = result.get('LanguageName')
        if language == "Brazilian":
            language = "Portuguese (Brazil)"

        filename = result.get('SubFileName', '')
        if not filename:
            filename = result.get('MovieName', 'subtitle') + '.' + result.get('SubFormat', 'srt')

        mapped_results.append({
            'service_name': service_name,
            'service': service.display_name,
            'lang': language,
            'name': filename,
            'rating': int(round(float(result.get('SubRating', 0)) / 2)) if result.get('SubRating') else 0,
            'lang_code': result.get('ISO639'),
            'sync': 'true' if result.get('MatchedBy') == 'moviehash' else 'false',
            'impaired': 'true' if int(result.get('SubHearingImpaired', 0)) != 0 else 'false',
            'color': 'springgreen',
            'action_args': {
                'url': result.get('ZipDownloadLink'),
                'lang': language,
                'filename': filename,
            }
        })

    return mapped_results

def build_download_request(core, service_name, args):
    request = {
        'method': 'GET',
        'url': args['url'],
        'headers': {
            'User-Agent': __user_agent,
        }
    }

    return request
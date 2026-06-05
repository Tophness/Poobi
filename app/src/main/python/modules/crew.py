# -*- coding: utf-8 -*-

'''
 ***********************************************************
 * The Crew Add-on
 *
 * @package script.module.thecrew
 *
 * @copyright (c) 2023, The Crew
 * @license GNU General Public License, version 3 (GPL-3.0)
 *
 ********************************************************cm*
'''


import sys
from .crewruntime import c

def router(params):

    #c.log('\n------------------------------------------------------------------------\n \
    #    [cm inside router @ 23]\nparams = ' + repr(params) + '\n \
    #    ------------------------------------------------------------------------\n')

    action = params.get('action')
    mode = params.get('mode')
    subid = params.get('subid')
    name = params.get('name')
    title = params.get('title')
    year = params.get('year')
    imdb = params.get('imdb')
    tmdb = params.get('tmdb')
    season = params.get('season')
    episode = params.get('episode')
    tvshowtitle = params.get('tvshowtitle')
    premiered = params.get('premiered')
    url = params.get('url')
    tid = params.get('tid')
    image = params.get('image')
    meta = params.get('meta')
    select = params.get('select')
    query = params.get('query')
    source = params.get('source')
    content = params.get('content')

    docu_category = params.get('docuCat')
    docu_watch = params.get('docuPlay')

    windowedtrailer = params.get('windowedtrailer')
    windowedtrailer = int(windowedtrailer) if windowedtrailer in ("0", "1") else 0

    actionlist = {
        '247movies','247tvshows','iptv','yss','weak','daddylive',
        'sportsbay','sports24','gratis','base','waste','whitehat','arconai','iptv_lodge','stratus','distro',
        'xumo','bumble','pluto','tubi','spanish','spanish2','bp','arabic','arabic2','india','chile','colombia','argentina',
        'spain','iptv_git','cctv','titan','porn','faith','lust','greyhat','absolution','eyecandy','purplehat','retribution','kiddo',
        'redhat','yellowhat','blackhat','food','ncaa','ncaab','lfl','xfl','boxing','tennis','mlb','nfl','nhl','nba',
        'ufc','fifa','wwe','motogp','f1','pga','nascar','cricket','sports_channels', 'sreplays', 'greenhat'
    }



    if action == None:
        from resources.lib.indexers import navigator
        from resources.lib.modules import cache
        cache.cache_version_check()
        navigator.navigator().root()

    elif action in actionlist:
        from resources.lib.indexers import lists
        s = 'lists.indexer().root_{}()'.format(action)
        eval(s)

    elif action == 'greenhat':
        from resources.lib.indexers import lists
        lists.indexer().root_greenhat()

    elif action == 'gitNavigator':
        from resources.lib.indexers import lists
        lists.indexer().root_git()

    elif action == 'plist':
        from resources.lib.indexers import lists
        lists.indexer().root_personal()

    elif action == 'directory':
        from resources.lib.indexers import lists
        lists.indexer().get(url)

    elif action == 'qdirectory':
        from resources.lib.indexers import lists
        lists.indexer().getq(url)

    elif action == 'xdirectory':
        from resources.lib.indexers import lists
        lists.indexer().getx(url)

    elif action == 'developer':
        from resources.lib.indexers import lists
        lists.indexer().developer()

    elif action == 'tvtuner':
        from resources.lib.indexers import lists
        lists.indexer().tvtuner(url)

    elif 'youtube' in str(action):
        from resources.lib.indexers import lists
        lists.indexer().youtube(url, action)

    elif action == 'browser':
        from resources.lib.indexers import lists
        sports.resolver().browser(url)

    elif action == 'docuNavigator':
        from resources.lib.indexers import docu
        docu.documentary().root()

    elif action == 'docuHeaven':
        from resources.lib.indexers import docu
        if not docu_category == None:
            docu.documentary().docu_list(docu_category)
        elif not docu_watch == None:
            docu.documentary().docu_play(docu_watch)
            #docu.documentary().play_video(docu_watch)
        else:
            if not docu_category == None:
                docu.documentary().docu_list(docu_category)
            else:
                docu.documentary().root()

    elif action == "furkNavigator":
        from resources.lib.indexers import navigator
        navigator.navigator().furk()

    elif action == "furkMetaSearch":
        from resources.lib.indexers import furk
        furk.furk().furk_meta_search(url)

    elif action == "furkSearch":
        from resources.lib.indexers import furk
        furk.furk().search()

    elif action == "furkUserFiles":
        from resources.lib.indexers import furk
        furk.furk().user_files()

    elif action == "furkSearchNew":
        from resources.lib.indexers import furk
        furk.furk().search_new()

    elif action == 'bluehat':
        from resources.lib.indexers import navigator
        navigator.navigator().bluehat()

    elif action == 'whitehat':
        from resources.lib.indexers import navigator
        navigator.navigator().whitehat()

    elif action == 'movieNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().movies()

    elif action == 'movieliteNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().movies(lite=True)

    elif action == 'mymovieNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().mymovies()

    elif action == 'mymovieliteNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().mymovies(lite=True)

    elif action == 'nav_add_addons':
        from resources.lib.indexers import navigator
        navigator.navigator().add_addons()

    elif action == 'tvNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().tvshows()

    elif action == 'traktlist':
        from resources.lib.indexers import navigator
        navigator.navigator().traktlist()

    elif action == 'imdblist':
        from resources.lib.indexers import navigator
        navigator.navigator().imdblist()

    elif action == 'tmdbtvlist':
        from resources.lib.indexers import navigator
        navigator.navigator().tmdbtvlist()

    elif action == 'tmdbmovieslist':
        from resources.lib.indexers import navigator
        navigator.navigator().tmdbmovieslist()

    elif action == 'tvliteNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().tvshows(lite=True)

    elif action == 'mytvNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().mytvshows()

    elif action == 'mytvliteNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().mytvshows(lite=True)

    elif action == 'downloadNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().downloads()

    elif action == 'libraryNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().library()

    elif action == 'toolNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().tools()
    
    elif action == 'developers':
        from resources.lib.indexers import navigator
        navigator.navigator().developers()

    elif action == 'cachingTools':
        from resources.lib.indexers import navigator
        navigator.navigator().cachingTools()

    elif action == 'searchNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().search()

    elif action == 'viewsNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().views()

    elif action == 'clearCache':
        from resources.lib.indexers import navigator
        navigator.navigator().clearCache()

    elif action == 'clearAllCache':
        from resources.lib.indexers import navigator
        navigator.navigator().clearCacheAll()

    elif action == 'clearMetaCache':
        from resources.lib.indexers import navigator
        navigator.navigator().clearCacheMeta()

    elif action == 'clearCacheSearch':
        from resources.lib.indexers import navigator
        navigator.navigator().clearCacheSearch()

    #CM 2021/17/7 @todo obsolete part
    elif action == 'infoCheck':
        from resources.lib.indexers import navigator
        navigator.navigator().infoCheck('')

    elif action == 'movies':
        from resources.lib.indexers import movies
        if url  == 'tmdb_networks':
            movies.movies().get(url, tid)
        else:
            movies.movies().get(url)

    elif action == 'moviePage':
        from resources.lib.indexers import movies
        movies.movies().get(url)

    elif action == 'movieWidget':
        from resources.lib.indexers import movies
        movies.movies().widget()

    elif action == 'movieSearch':
        from resources.lib.indexers import movies
        movies.movies().search()

    elif action == 'movieSearchnew':
        from resources.lib.indexers import movies
        movies.movies().search_new()

    elif action == 'movieSearchterm':
        from resources.lib.indexers import movies
        movies.movies().search_term(name)

    elif action == 'moviePerson':
        from resources.lib.indexers import movies
        movies.movies().person()

    elif action == 'movieGenres':
        from resources.lib.indexers import movies
        movies.movies().genres()

    elif action == 'movieLanguages':
        from resources.lib.indexers import movies
        movies.movies().languages()

    elif action == 'movieCertificates':
        from resources.lib.indexers import movies
        movies.movies().certifications()

    elif action == 'movieYears':
        from resources.lib.indexers import movies
        movies.movies().years()

    elif action == 'moviePersons':
        from resources.lib.indexers import movies
        movies.movies().persons(url)

    elif action == 'movieUserlists':
        from resources.lib.indexers import movies
        movies.movies().userlists()

    elif action == 'channels':
        from resources.lib.indexers import channels
        channels.channels().get()

    elif action == 'tvshows':
        from resources.lib.indexers import tvshows
        if url  == 'tmdb_networks':
            tvshows.tvshows().get(url, tid)
        else:
            tvshows.tvshows().get(url)

    elif action == 'tvshowPage':
        from resources.lib.indexers import tvshows
        tvshows.tvshows().get(url)

    elif action == 'tvSearch':
        from resources.lib.indexers import tvshows
        tvshows.tvshows().search()

    elif action == 'tvSearchnew':
        from resources.lib.indexers import tvshows
        tvshows.tvshows().search_new()

    elif action == 'tvSearchterm':
        from resources.lib.indexers import tvshows
        tvshows.tvshows().search_term(name)

    elif action == 'tvPerson':
        from resources.lib.indexers import tvshows
        tvshows.tvshows().person()

    elif action == 'tvGenres':
        from resources.lib.indexers import tvshows
        tvshows.tvshows().genres()

    elif action == 'tvNetworks':
        from resources.lib.indexers import tvshows
        tvshows.tvshows().networks()

    elif action == 'tvLanguages':
        from resources.lib.indexers import tvshows
        tvshows.tvshows().languages()

    elif action == 'tvCertificates':
        from resources.lib.indexers import tvshows
        tvshows.tvshows().certifications()

    elif action == 'tvPersons':
        from resources.lib.indexers import tvshows
        tvshows.tvshows().persons(url)

    elif action == 'tvUserlists':
        from resources.lib.indexers import tvshows
        tvshows.tvshows().userlists()

    elif action == 'seasons':
        from resources.lib.indexers import episodes
        episodes.seasons().get(tvshowtitle, year, imdb, tmdb, meta)

    elif action == 'episodes':
        from resources.lib.indexers import episodes
        episodes.episodes().get(tvshowtitle, year, imdb, tmdb, meta, season, episode)

    elif action == 'calendar':
        from resources.lib.indexers import episodes
        episodes.episodes().calendar(url)

    elif action == 'tvWidget':
        from resources.lib.indexers import episodes
        episodes.episodes().widget()

    elif action == 'calendars':
        from resources.lib.indexers import episodes
        episodes.episodes().calendars()

    elif action == 'episodeUserlists':
        from resources.lib.indexers import episodes
        episodes.episodes().userlists()

    elif action == 'setfanartquality':
        from resources.lib.modules import control
        c.log('[CM Debug @ 397 in crew.py] hiero')
        control.setFanartQuality()

    elif action == 'refresh':
        from resources.lib.modules import control
        control.refresh()

    elif action == 'queueItem':
        from resources.lib.modules import control
        control.queueItem()

    elif action == 'openSettings':
        from resources.lib.modules import control
        control.openSettings(query)

    #elif action == 'openDebuglog':
        #from resources.lib.modules import control
        #control.openLogViewer()



    # developers menu

    elif action == 'startupMaintenance':
        from resources.lib.modules import control
        control.startupMaintenance()

    elif action == 'setSizes':
        from resources.lib.modules import control
        control.setSizes()

    elif action == 'updateSizes':
        from resources.lib.modules import control
        control.updateSizes()

    # end developers menu






    elif action == 'changelog':
        from resources.lib.modules import changelog
        changelog.get()

    elif action == 'artwork':
        from resources.lib.modules import control
        control.artwork()

    elif action == 'addView':
        from resources.lib.modules import views
        views.addView(content)

    elif action == 'moviePlaycount':
        from resources.lib.modules import playcount
        playcount.movies(imdb, query)

    elif action == 'episodePlaycount':
        from resources.lib.modules import playcount
        playcount.episodes(imdb, tmdb, season, episode, query)

    elif action == 'tvPlaycount':
        from resources.lib.modules import playcount
        playcount.tvshows(name, imdb, tmdb, season, query)

    elif action == 'trailer':
        from resources.lib.modules import trailer
        trailer.trailers().get(name, url, imdb, tmdb, windowedtrailer)
        #trailer.trailer().play(name, url, meta, windowedtrailer)

    elif action == 'traktManager':
        from resources.lib.modules import trakt
        trakt.manager(name, imdb, tmdb, content)

    elif action == 'authTrakt':
        from resources.lib.modules import trakt
        trakt.authTrakt()

    elif action == 'ResolveUrlTorrent':
        from resources.lib.modules import control
        control.openSettings(query, "script.module.resolveurl")

    elif action == 'download':
        import json
        from resources.lib.modules import sources
        from resources.lib.modules import downloader
        try:
            downloader.download(name, image, sources.sources().sourcesResolve(json.loads(source)[0], True))
        except:
            pass

    elif action == 'play':
        from resources.lib.indexers import lists
        try:
            if not content == None:
                lists.player().play(url, content)
            else:
                from resources.lib.modules import sources
                sources.sources().play(title, year, imdb, tmdb, season, episode, tvshowtitle, premiered, meta, select)
        except Exception as e:
            import traceback
            failure = traceback.format_exc()
            c.log('[CM @ 470 in crew]Traceback:: ' + str(failure))
            c.log('[CM @ 471 in crew]Error:: ' + str(e))
            pass

    elif action == 'play1':
        from resources.lib.indexers import lists
        if not content == None:
            lists.player().play(url, content)
        else:
            from resources.lib.modules import sources
            sources.sources().play(title, year, imdb, tmdb, season, episode, tvshowtitle, premiered, meta, select)

    elif action == 'addItem':
        from resources.lib.modules import sources
        sources.sources().addItem(title)

    elif action == 'playItem':
        from resources.lib.modules import sources
        sources.sources().playItem(title, source)

    elif action == 'alterSources':
        from resources.lib.modules import sources
        sources.sources().alterSources(url, meta)

    elif action == 'clearSources':
        from resources.lib.modules import sources
        sources.sources().clearSources()

    elif action == 'random':
        rtype = params.get('rtype')
        if rtype == 'movie':
            from resources.lib.indexers import movies
            rlist = movies.movies().get(url, create_directory=False)
            r = sys.argv[0]+"?action=play"
        elif rtype == 'episode':
            from resources.lib.indexers import episodes
            rlist = episodes.episodes().get(tvshowtitle, year, imdb, tmdb, meta, season, create_directory=False)
            r = sys.argv[0]+"?action=play"
        elif rtype == 'season':
            from resources.lib.indexers import episodes
            rlist = episodes.seasons().get(tvshowtitle, year, imdb, tmdb, meta, create_directory=False)
            r = sys.argv[0]+"?action=random&rtype=episode"
        elif rtype == 'show':
            from resources.lib.indexers import tvshows
            rlist = tvshows.tvshows().get(url, create_directory=False)
            r = sys.argv[0]+"?action=random&rtype=season"
        from resources.lib.modules import control
        from random import randint
        from urllib.parse import quote_plus
        import json
        try:
            rand = randint(1, len(rlist))-1
            for p in ['title', 'year', 'imdb', 'tmdb', 'season', 'episode', 'tvshowtitle', 'premiered', 'select']:
                if rtype == "show" and p == "tvshowtitle":
                    try:
                        r += '&'+p+'='+ urllib.parse.quote_plus(rlist[rand]['title'])
                    except:
                        pass
                else:
                    try:
                        r += '&'+p+'='+ urllib.parse.quote_plus(rlist[rand][p])
                    except:
                        pass
            try:
                r += '&meta='+ urllib.parse.quote_plus(json.dumps(rlist[rand]))
            except:
                r += '&meta='+ urllib.parse.quote_plus("{}")
            if rtype == "movie":
                try:
                    control.infoDialog(rlist[rand]['title'], control.lang(32536), time=30000)
                except:
                    pass
            elif rtype == "episode":
                try:
                    control.infoDialog(rlist[rand]['tvshowtitle']+" - Season "+ rlist[rand]['season'] + " - "+rlist[rand]['title'], control.lang(32536), time=30000)
                except:
                    pass
            control.execute('RunPlugin(%s)' % r)
        except:
            control.infoDialog(control.lang(32537), time=8000)

    elif action == 'movieToLibrary':
        from resources.lib.modules import libtools
        libtools.libmovies().add(name, title, year, imdb, tmdb)

    elif action == 'moviesToLibrary':
        from resources.lib.modules import libtools
        libtools.libmovies().range(url)

    elif action == 'moviesToLibrarySilent':
        from resources.lib.modules import libtools
        libtools.libmovies().silent(url)

    elif action == 'tvshowToLibrary':
        from resources.lib.modules import libtools
        libtools.libtvshows().add(tvshowtitle, year, imdb, tmdb)

    elif action == 'tvshowsToLibrary':
        from resources.lib.modules import libtools
        libtools.libtvshows().range(url)

    elif action == 'tvshowsToLibrarySilent':
        from resources.lib.modules import libtools
        libtools.libtvshows().silent(url)

    elif action == 'updateLibrary':
        from resources.lib.modules import libtools
        libtools.libepisodes().update(query)

    elif action == 'service':
        from resources.lib.modules import libtools
        libtools.libepisodes().service()

    elif action == 'urlResolver':
        try:
            import resolveurl
        except:
            pass
        resolveurl.display_settings()

    elif action == 'newsNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().news()

    elif action == 'collectionsNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().collections()

    elif action == 'collectionActors':
        from resources.lib.indexers import navigator
        navigator.navigator().collectionActors()

    elif action == 'collectionBoxset':
        from resources.lib.indexers import navigator
        navigator.navigator().collectionBoxset()

    elif action == 'collectionBoxsetKids':
        from resources.lib.indexers import navigator
        navigator.navigator().collectionBoxsetKids()

    elif action == 'collectionKids':
        from resources.lib.indexers import navigator
        navigator.navigator().collectionKids()

    elif action == 'collectionSuperhero':
        from resources.lib.indexers import navigator
        navigator.navigator().collectionSuperhero()

    elif action == 'collections':
        from resources.lib.indexers import collections
        collections.collections().get(url)

    elif action == 'holidaysNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().holidays()

    elif action == 'halloweenNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().halloween()

    #elif action == 'bugReports':
        #from resources.lib.reports import bugreports
        #bugreports.BugReporter()

    elif action == 'kidsgreyNavigator':
        from resources.lib.indexers import navigator
        navigator.navigator().kidsgrey()

    elif action == 'debridkids':
        from resources.lib.indexers import lists
        lists.indexer().root_debridkids()

    elif action == 'waltdisney':
        from resources.lib.indexers import lists
        lists.indexer().root_waltdisney()

    elif action == 'learning':
        from resources.lib.indexers import lists
        lists.indexer().root_learning()

    elif action == 'songs':
        from resources.lib.indexers import lists
        lists.indexer().root_songs()

# -*- coding: utf-8 -*-

from six.moves import urllib_parse
from six.moves.urllib_parse import parse_qs, urlencode

from resources.lib.modules import cleantitle
from resources.lib.modules import client
from resources.lib.modules import client_utils
from resources.lib.modules import scrape_sources

DOM = client_utils.parseDOM


class source:
    def __init__(self):
        self.results = []
        self.domains = ['tvseries.video']
        self.base_link = 'https://www.tvseries.video'
        self.search_link = '/search?q=%s'
        self.notes = 'Sources seem to be all holders like 2embed which fail to work on my end.'


    def movie(self, imdb, tmdb, title, localtitle, aliases, year):
        url = {'imdb': imdb, 'title': title, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def tvshow(self, imdb, tmdb, tvdb, tvshowtitle, localtvshowtitle, aliases, year):
        url = {'imdb': imdb, 'tvshowtitle': tvshowtitle, 'aliases': aliases, 'year': year}
        url = urlencode(url)
        return url


    def episode(self, url, imdb, tmdb, tvdb, title, premiered, season, episode):
        if not url:
            return
        url = parse_qs(url)
        url = dict([(i, url[i][0]) if url[i] else (i, '') for i in url])
        url['title'], url['premiered'], url['season'], url['episode'] = title, premiered, season, episode
        url = urlencode(url)
        return url


    def sources(self, url, hostDict):
        try:
            if not url:
                return self.results
            data = parse_qs(url)
            data = dict([(i, data[i][0]) if data[i] else (i, '') for i in data])
            aliases = eval(data.get('aliases', '[]'))
            title = data['tvshowtitle'] if 'tvshowtitle' in data else data['title']
            season, episode = (data['season'], data['episode']) if 'tvshowtitle' in data else ('0', '0')
            prem_year = data.get('premiered', '').split('-')[0]
            year = prem_year if (prem_year and prem_year != '0') else data.get('year', '')
            search_url = self.base_link + self.search_link % cleantitle.get_plus(title)
            type_check = 'Series' if 'tvshowtitle' in data else 'Movies'
            headers = {'User-Agent': client.UserAgent, 'Referer': self.base_link}  
            html = client.scrapePage(search_url, headers=headers).text
            r = DOM(html, 'div', attrs={'class': r'.+?content'})
            r = [(DOM(i, 'a', ret='href'), DOM(i, 'span', attrs={'class': 'card-title'}), DOM(i, 'span', attrs={'class': 'viddate'}), DOM(i, 'span', attrs={'class': 'vidtype'})) for i in r]
            r = [(i[0][0], i[1][0], i[2][0], i[3][0]) for i in r if len(i[0]) > 0 and len(i[1]) > 0 and len(i[2]) > 0 and len(i[3]) > 0]
            
            clean_target = cleantitle.get(title)
            target_aliases = [cleantitle.get(a) for a in aliases if a]
            target_aliases.append(clean_target)

            def is_title_match(card_title):
                clean_card = cleantitle.get(card_title)
                return clean_card in target_aliases

            matches = [i for i in r if is_title_match(i[1]) and i[3] == type_check]
            if year and year != '0':
                year_matches = [i for i in matches if cleantitle.match_year(i[2], year)]
                if year_matches:
                    matches = year_matches

            if not matches and 'tvshowtitle' in data:
                slug = cleantitle.geturl(title)
                direct_url = f"/series/{slug}"
                try:
                    test_resp = client.scrapePage(self.base_link + direct_url, headers=headers)
                    if test_resp and test_resp.status_code == 200 and 'eplist' in test_resp.text:
                        matches = [(direct_url, title, year, type_check)]
                except Exception:
                    pass

            if not matches:
                return self.results

            selected_card = matches[0]
            show_path = selected_card[0]
            if not show_path.startswith('/'):
                show_path = '/' + show_path
            url = self.base_link + show_path

            if 'tvshowtitle' in data:
                sepi = '/season-%s-episode-%s-' % (season, episode)
                r = client.scrapePage(url, headers=headers).text
                eplist_divs = DOM(r, 'div', attrs={'class': 'eplist'})
                if not eplist_divs:
                    return self.results
                ep_hrefs = DOM(eplist_divs[0], 'a', ret='href')
                matched_eps = [i for i in ep_hrefs if sepi in i]
                if not matched_eps:
                    sepi_alt = f"/season-{season}-episode-{episode}"
                    matched_eps = [i for i in ep_hrefs if sepi_alt in i]
                if not matched_eps:
                    return self.results
                ep_path = matched_eps[0]
                if not ep_path.startswith('/'):
                    ep_path = '/' + ep_path
                url = self.base_link + ep_path

            html = client.scrapePage(url, headers=headers).text
            links = []
            links += DOM(html, 'div', ret='data-vid')
            links += DOM(html, 'a', ret='data-vid1')
            for link in links:
                try:
                    if not link:
                        continue
                    clean_link = client_utils.replaceHTMLCodes(link).replace('&amp;', '&')
                    if clean_link.startswith('/vsp2/'):
                        continue
                    for source in scrape_sources.process(hostDict, clean_link):
                        if scrape_sources.check_host_limit(source['source'], self.results):
                            continue
                        self.results.append(source)
                except Exception:
                    pass
            return self.results
        except Exception:
            return self.results


    def resolve(self, url):
        return url
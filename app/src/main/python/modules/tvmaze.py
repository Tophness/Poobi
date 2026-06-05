# -*- coding: utf-8 -*-

from six.moves.urllib_parse import urlencode

from resources.lib.modules import client


class tvMaze:
    def __init__(self, show_id=None):
        self.api_url = 'https://api.tvmaze.com/%s%s'
        self.show_id = show_id


    def showID(self, show_id=None):
        if (show_id != None):
            self.show_id = show_id
            return show_id
        return self.show_id


    def request(self, endpoint, query=None):
        try:
            if (query != None):
                query = '?' + urlencode(query)
            else:
                query = ''
            request_url = self.api_url % (endpoint, query)
            response = client.scrapePage(request_url, timeout='30').json()
            return response
        except:
            pass
        return {}


    def showLookup(self, type, id):
        try:
            result = self.request('lookup/shows', {type: id})
            if ('id' in result):
                self.show_id = result['id']
            return result
        except:
            pass
        return {}


    def shows(self, show_id=None, embed=None):
        try:
            if (not self.showID(show_id)):
                raise Exception("showID Error.")
            result = self.request('shows/%d' % self.show_id)
            if ('id' in result):
                self.show_id = result['id']
            return result
        except:
            pass
        return {}


    def showSeasons(self, show_id=None):
        try:
            if (not self.showID(show_id)):
                raise Exception("showID Error.")
            result = self.request('shows/%d/seasons' % int(self.show_id))
            if (len(result) > 0 and 'id' in result[0]):
                return result
        except:
            pass
        return []


    def showEpisodeList(self, show_id=None, specials=False):
        try:
            if (not self.showID(show_id)):
                raise Exception("showID Error.")
            result = self.request('shows/%d/episodes' % int(self.show_id), 'specials=1' if specials else '')
            if (len(result) > 0 and 'id' in result[0]):
                return result
        except:
            pass
        return []



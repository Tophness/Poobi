import requests
import json
import re

class source:
    def __init__(self):
        self.priority = 1
        self.language = ['en']
        self.domains = ['torrentio.strem.fun']
        self.base_url = 'https://torrentio.strem.fun'

    def movie(self, imdb, title, localtitle, aliases, year):
        try:
            return imdb
        except:
            return None

    def tvshow(self, imdb, tvdb, title, localtitle, aliases, year):
        try:
            return imdb
        except:
            return None

    def episode(self, url, imdb, tvdb, title, premiered, season, episode):
        try:
            return f"{imdb}:{season}:{episode}"
        except:
            return None

    def sources(self, url, hostDict, hostprDict):
        sources = []
        try:
            # url is either imdbId or imdbId:season:episode
            if ':' in url:
                media_type = 'series'
            else:
                media_type = 'movie'

            # Build settings string if provided
            settings_path = ""
            if hostprDict and isinstance(hostprDict, dict):
                lang = hostprDict.get('torrent_language', '').lower()
                if lang:
                    settings_path = f"language={lang}/"

            api_url = f"{self.base_url}/{settings_path}stream/{media_type}/{url}.json"
            print(f"Torrentio: API URL: {api_url}")

            headers = {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                'Accept': 'application/json'
            }

            response = requests.get(api_url, headers=headers, timeout=10)
            if response.status_code != 200:
                print(f"Torrentio: Response code: {response.status_code}")
                return []

            data = response.json()
            for stream in data.get('streams', []):
                name = stream.get('name', 'Torrentio')
                torrent_info = stream.get('title', '')

                # Extract quality from name or title
                quality = 'SD'
                full_text = (name + " " + torrent_info).lower()
                if '4k' in full_text or '2160p' in full_text:
                    quality = '4K'
                elif '1080p' in full_text:
                    quality = '1080p'
                elif '720p' in full_text:
                    quality = '720p'

                # Torrentio title often has seeders/size on the 3rd line
                # e.g. "Rick and Morty...\n👤 6 💾 1.2 GB ⚙️ ThePirateBay"
                display_title = torrent_info.split('\n')[0]
                metadata = ""
                lines = torrent_info.split('\n')
                if len(lines) > 2:
                    metadata = " (" + lines[2].strip() + ")"

                # Torrentio usually provides direct links if configured with Debrid,
                # or infoHash if not.
                stream_url = stream.get('url')
                info_hash = stream.get('infoHash')

                if stream_url:
                    sources.append({
                        'source': name.replace('\n', ' '),
                        'quality': quality,
                        'language': 'en',
                        'url': stream_url,
                        'direct': True,
                        'provider': 'Torrentio',
                        'title': f"[{quality}] {display_title}{metadata}"
                    })
                elif info_hash:
                    # If it's just an infoHash, we mark it as magnet
                    magnet = f"magnet:?xt=urn:btih:{info_hash}"
                    sources.append({
                        'source': name.replace('\n', ' '),
                        'quality': quality,
                        'language': 'en',
                        'url': magnet,
                        'direct': False,
                        'provider': 'Torrentio',
                        'title': f"[{quality}] {display_title}{metadata} [P2P]"
                    })
        except Exception as e:
            print(f"Torrentio error: {e}")

        return sources

    def resolve(self, url):
        return url

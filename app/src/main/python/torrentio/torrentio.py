import requests
import json
import re

def detect_language(torrent_info):
    info_lower = torrent_info.lower()

    mapping = {
        'ru': ['🇷🇺', 'rus', 'russian', 'сыендук', 'mvo', 'vo'],
        'es': ['🇪🇸', '🇲🇽', 'spa', 'spanish', 'lat'],
        'pt': ['🇵🇹', '🇧🇷', 'por', 'portuguese'],
        'it': ['🇮🇹', 'ita', 'italian'],
        'fr': ['🇫🇷', 'fra', 'french'],
        'de': ['🇩🇪', 'ger', 'german'],
        'pl': ['🇵🇱', 'pol', 'polish'],
        'hi': ['🇮🇳', 'hin', 'hindi'],
        'uk': ['🇺🇦', 'ukr', 'ukrainian']
    }
    
    for lang_code, indicators in mapping.items():
        if any(ind in info_lower for ind in indicators):
            return lang_code
            
    return 'en'

def parse_languages_display(torrent_info):
    info_lower = torrent_info.lower()
    detected = []

    flag_to_lang = {
        "🇺🇸": "English", "🇬🇧": "English", "🇮🇹": "Italian", "🇷🇺": "Russian",
        "🇺🇦": "Ukrainian", "🇪🇸": "Spanish", "🇲🇽": "Spanish", "🇵🇹": "Portuguese",
        "🇧🇷": "Portuguese", "🇫🇷": "French", "🇩🇪": "German", "🇵🇱": "Polish",
        "🇮🇳": "Hindi"
    }
    lang_to_flag = {
        "English": "🇬🇧", "Italian": "🇮🇹", "Russian": "🇷🇺", "Ukrainian": "🇺🇦",
        "Spanish": "🇪🇸", "Portuguese": "🇵🇹", "French": "🇫🇷", "German": "🇩🇪",
        "Polish": "🇵🇱", "Hindi": "🇮🇳"
    }

    for flag, lang in flag_to_lang.items():
        if flag in torrent_info:
            display = f"{flag} {lang}"
            if display not in detected:
                detected.append(display)

    codes = {
        "eng": "English", "ita": "Italian", "rus": "Russian", "ukr": "Ukrainian",
        "spa": "Spanish", "por": "Portuguese", "fra": "French", "ger": "German",
        "pol": "Polish", "hin": "Hindi"
    }
    for code, lang in codes.items():
        if re.search(r'\b' + re.escape(code) + r'\b', info_lower):
            flag = lang_to_flag.get(lang, "")
            display = f"{flag} {lang}".strip()
            if display not in detected:
                detected.append(display)

    if not detected:
        has_cyrillic = any('\u0400' <= char <= '\u04FF' for char in torrent_info)
        if has_cyrillic:
            detected.append("🇷🇺 Russian")
        else:
            detected.append("🇬🇧 English")

    return " / ".join(detected)

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
            if ':' in url:
                media_type = 'series'
            else:
                media_type = 'movie'

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

            import urllib.parse
            data = response.json()
            for stream in data.get('streams', []):
                name = stream.get('name', 'Torrentio')
                torrent_info = stream.get('title', '')

                seeders = 0
                seeder_match = re.search(r'👤\s*(\d+)', torrent_info)
                if seeder_match:
                    seeders = int(seeder_match.group(1))

                quality = 'SD'
                full_text = (name + " " + torrent_info).lower()
                if '4k' in full_text:
                    quality = '4K'
                if '2160p' in full_text:
                    quality = '2K'
                elif '1080p' in full_text:
                    quality = '1080p'
                elif '720p' in full_text:
                    quality = '720p'

                size_str = ""
                size_bytes = 0
                size_match = re.search(r'💾\s*([\d\.,]+)\s*(GB|MB|KB)', torrent_info, re.IGNORECASE)
                if size_match:
                    val_str = size_match.group(1).replace(',', '')
                    val = float(val_str)
                    unit = size_match.group(2).upper()
                    size_str = f"{val_str} {unit}"
                    if unit == "GB":
                        size_bytes = int(val * 1024 * 1024 * 1024)
                    elif unit == "MB":
                        size_bytes = int(val * 1024 * 1024)
                    elif unit == "KB":
                        size_bytes = int(val * 1024)

                lines = [line.strip() for line in torrent_info.split('\n') if line.strip()]
                torrent_name = lines[0] if len(lines) > 0 else "Unknown Torrent"

                filename = stream.get('behaviorHints', {}).get('filename', '')
                if not filename and len(lines) > 1:
                    if not any(sym in lines[1] for sym in ['👤', '💾', '⚙️']):
                        filename = lines[1]
                if not filename:
                    filename = "video.mkv"

                stream_url = stream.get('url')
                info_hash = stream.get('infoHash')
                file_idx = stream.get('fileIdx', 0)

                if not stream_url and info_hash:
                    safe_filename = urllib.parse.quote(filename)
                    stream_url = f"http://localhost:11470/{info_hash}/{file_idx}/{safe_filename}"

                if stream_url:
                    sources.append({
                        'quality': quality,
                        'language': detect_language(torrent_info),
                        'languages_display': parse_languages_display(torrent_info),
                        'url': stream_url,
                        'infoHash': info_hash,
                        'fileIdx': file_idx,
                        'seeders': seeders,
                        'size': size_str,
                        'size_bytes': size_bytes,
                        'filename': filename,
                        'torrent_name': torrent_name
                    })
        except Exception as e:
            print(f"Torrentio error: {e}")

        return sources

    def resolve(self, url):
        return url
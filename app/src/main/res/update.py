import os
import re

RES_DIR = os.path.dirname(os.path.abspath(__file__))
TEMPLATE_FILE = os.path.join(RES_DIR, "values", "strings.xml")

NEW_TRANSLATIONS = {
    # Buttons
    "btn_load_all_tabs": {
        "values": "Load All Tabs",
        "values-es": "Cargar todas las pestañas",
        "values-de": "Alle Tabs laden",
        "values-fr": "Charger tous les onglets",
        "values-it": "Carica tutte le schede",
        "values-pt": "Carregar todas as abas",
        "values-ru": "Загрузить все вкладки",
        "values-tr": "Tüm Sekmeleri Yükle",
        "values-nl": "Alle tabbladen laden",
        "values-pl": "Załaduj wszystkie karty",
        "values-ar": "تحميل جميع علامات التبويب",
        "values-hi": "सभी टैब लोड करें",
        "values-ja": "すべてのタブを読み込む",
        "values-ko": "모든 탭 불러오기",
        "values-zh": "加载所有标签页",
    },
    "btn_restore_all_tabs": {
        "values": "Restore All Tabs",
        "values-es": "Restaurar todas las pestañas",
        "values-de": "Alle Tabs wiederherstellen",
        "values-fr": "Restaurer tous les onglets",
        "values-it": "Ripristina tutte le schede",
        "values-pt": "Restaurar todas as abas",
        "values-ru": "Восстановить все вкладки",
        "values-tr": "Tüm Sekmeleri Geri Yükle",
        "values-nl": "Alle tabbladen herstellen",
        "values-pl": "Przywróć wszystkie karty",
        "values-ar": "استعادة جميع علامات التبويب",
        "values-hi": "सभी टैब पुनर्स्थापित करें",
        "values-ja": "すべてのタブを復元",
        "values-ko": "모든 탭 복원",
        "values-zh": "恢复所有标签页",
    },
    "btn_clear_bookmarks": {
        "values": "Clear Bookmarks",
        "values-es": "Borrar marcadores",
        "values-de": "Lesezeichen löschen",
        "values-fr": "Effacer les favoris",
        "values-it": "Cancella segnalibri",
        "values-pt": "Limpar favoritos",
        "values-ru": "Очистить закладки",
        "values-tr": "Yer İmlerini Temizle",
        "values-nl": "Bladwijzers wissen",
        "values-pl": "Wyczyść zakładki",
        "values-ar": "مسح المفضلة",
        "values-hi": "बुकमार्क साफ़ करें",
        "values-ja": "ブックマークを消去",
        "values-ko": "북마크 지우기",
        "values-zh": "清除收藏书签",
    },
    "btn_clear_history": {
        "values": "Clear History",
        "values-es": "Borrar historial",
        "values-de": "Verlauf löschen",
        "values-fr": "Effacer l\'historique",
        "values-it": "Cancella cronologia",
        "values-pt": "Limpar histórico",
        "values-ru": "Очистить историю",
        "values-tr": "Geçmişi Temizle",
        "values-nl": "Geschiedenis wissen",
        "values-pl": "Wyczyść historię",
        "values-ar": "مسح السجل",
        "values-hi": "इतिहास साफ़ करें",
        "values-ja": "履歴を消去",
        "values-ko": "기록 지우기",
        "values-zh": "清除历史记录",
    },
    "btn_clear_downloads": {
        "values": "Clear Downloads",
        "values-es": "Borrar descargas",
        "values-de": "Downloads löschen",
        "values-fr": "Effacer les téléchargements",
        "values-it": "Cancella download",
        "values-pt": "Limpar downloads",
        "values-ru": "Очистить загрузки",
        "values-tr": "İndirilenleri Temizle",
        "values-nl": "Downloads wissen",
        "values-pl": "Wyczyść pobrane",
        "values-ar": "مسح التنزيلات",
        "values-hi": "डाउनलोड साफ़ करें",
        "values-ja": "ダウンロードを消去",
        "values-ko": "다운로드 기록 지우기",
        "values-zh": "清除下载记录",
    },
    "btn_prev": {
        "values": "Prev",
        "values-es": "Ant",
        "values-de": "Zurück",
        "values-fr": "Préc",
        "values-it": "Prec",
        "values-pt": "Ant",
        "values-ru": "Назад",
        "values-tr": "Önceki",
        "values-nl": "Vorige",
        "values-pl": "Poprz",
        "values-ar": "السابق",
        "values-hi": "पिछला",
        "values-ja": "前へ",
        "values-ko": "이전",
        "values-zh": "上一页",
    },
    "btn_next": {
        "values": "Next",
        "values-es": "Sig",
        "values-de": "Weiter",
        "values-fr": "Suiv",
        "values-it": "Succ",
        "values-pt": "Próx",
        "values-ru": "Далее",
        "values-tr": "Sonraki",
        "values-nl": "Volgende",
        "values-pl": "Dalej",
        "values-ar": "التالي",
        "values-hi": "अगला",
        "values-ja": "次へ",
        "values-ko": "다음",
        "values-zh": "下一页",
    },
    # Media Types & Details
    "media_type_movie": {
        "values": "Movie",
        "values-es": "Película",
        "values-de": "Film",
        "values-fr": "Film",
        "values-it": "Film",
        "values-pt": "Filme",
        "values-ru": "Фильм",
        "values-tr": "Film",
        "values-nl": "Film",
        "values-pl": "Film",
        "values-ar": "فيلم",
        "values-hi": "फ़िल्म",
        "values-ja": "映画",
        "values-ko": "영화",
        "values-zh": "电影",
    },
    "media_type_tv": {
        "values": "TV Series",
        "values-es": "Serie de TV",
        "values-de": "Serie",
        "values-fr": "Série TV",
        "values-it": "Serie TV",
        "values-pt": "Série de TV",
        "values-ru": "Сериал",
        "values-tr": "Dizi",
        "values-nl": "Serie",
        "values-pl": "Serial",
        "values-ar": "مسلسل تلفزيوني",
        "values-hi": "टीवी सीरीज़",
        "values-ja": "テレビ番組",
        "values-ko": "TV 시리즈",
        "values-zh": "电视剧",
    },
    "known_for_acting": {
        "values": "Acting",
        "values-es": "Interpretación",
        "values-de": "Schauspiel",
        "values-fr": "Interprétation",
        "values-it": "Recitazione",
        "values-pt": "Atuação",
        "values-ru": "Актерское искусство",
        "values-tr": "Oyunculuk",
        "values-nl": "Acteren",
        "values-pl": "Aktorstwo",
        "values-ar": "تمثيل",
        "values-hi": "अभिनय",
        "values-ja": "演技",
        "values-ko": "연기",
        "values-zh": "演艺",
    },
    # Statuses
    "status_missing": {
        "values": "Missing",
        "values-es": "No encontrado",
        "values-de": "Fehlt",
        "values-fr": "Manquant",
        "values-it": "Mancante",
        "values-pt": "Ausente",
        "values-ru": "Отсутствует",
        "values-tr": "Eksik",
        "values-nl": "Ontbreekt",
        "values-pl": "Brak",
        "values-ar": "مفقود",
        "values-hi": "अनुपलब्ध",
        "values-ja": "見つかりません",
        "values-ko": "파일 없음",
        "values-zh": "文件丢失",
    }
}

def escape_for_android_xml(text):
    if not text:
        return ""
    text = re.sub(r'&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)', '&amp;', text)
    text = re.sub(r"(?<!\\)'", r"\'", text)
    return text

def parse_translations_raw(file_path):
    translations = {}
    pattern = re.compile(r'<string\s+name="(?P<name>[^"]+)"[^>]*>(?P<content>.*?)</string>', re.DOTALL)
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            data = f.read()
        for match in pattern.finditer(data):
            name = match.group("name")
            translations[name] = escape_for_android_xml(match.group("content"))
    except Exception as e:
        print(f"Error reading {file_path}: {e}")
    return translations

def insert_key_after(content, anchor_key, new_key, value):
    if f'name="{new_key}"' in content:
        return content
    pattern = rf'(<string\s+name="{anchor_key}"[^>]*>.*?</string>)'
    replacement = rf'\1\n    <string name="{new_key}">{escape_for_android_xml(value)}</string>'
    return re.sub(pattern, replacement, content, count=1)

def ensure_structured_template():
    with open(TEMPLATE_FILE, "r", encoding="utf-8") as f:
        content = f.read()

    # Place buttons under Common Actions
    content = insert_key_after(content, "btn_zoom_in", "btn_load_all_tabs", NEW_TRANSLATIONS["btn_load_all_tabs"]["values"])
    content = insert_key_after(content, "btn_load_all_tabs", "btn_restore_all_tabs", NEW_TRANSLATIONS["btn_restore_all_tabs"]["values"])
    content = insert_key_after(content, "btn_restore_all_tabs", "btn_clear_bookmarks", NEW_TRANSLATIONS["btn_clear_bookmarks"]["values"])
    content = insert_key_after(content, "btn_clear_bookmarks", "btn_clear_history", NEW_TRANSLATIONS["btn_clear_history"]["values"])
    content = insert_key_after(content, "btn_clear_history", "btn_clear_downloads", NEW_TRANSLATIONS["btn_clear_downloads"]["values"])
    content = insert_key_after(content, "btn_clear_downloads", "btn_prev", NEW_TRANSLATIONS["btn_prev"]["values"])
    content = insert_key_after(content, "btn_prev", "btn_next", NEW_TRANSLATIONS["btn_next"]["values"])

    # Place media types under Media Details
    content = insert_key_after(content, "media_aired_prefix", "media_type_movie", NEW_TRANSLATIONS["media_type_movie"]["values"])
    content = insert_key_after(content, "media_type_movie", "media_type_tv", NEW_TRANSLATIONS["media_type_tv"]["values"])
    content = insert_key_after(content, "media_type_tv", "known_for_acting", NEW_TRANSLATIONS["known_for_acting"]["values"])

    # Place status under Web Dialogs
    content = insert_key_after(content, "prompt_search_google_url", "status_missing", NEW_TRANSLATIONS["status_missing"]["values"])

    with open(TEMPLATE_FILE, "w", encoding="utf-8") as f:
        f.write(content)

def realign_file(target_file, folder_name, translations, template_lines):
    output_lines = []
    string_pattern = re.compile(r'^(?P<indent>\s*)<string\s+name="(?P<name>[^"]+)"[^>]*>(?P<content>.*?)</string>(?P<tail>.*)$')

    for line in template_lines:
        match = string_pattern.match(line)
        if match:
            indent = match.group("indent")
            name = match.group("name")
            tail = match.group("tail")

            if name in translations:
                content = translations[name]
            elif name in NEW_TRANSLATIONS and folder_name in NEW_TRANSLATIONS[name]:
                content = escape_for_android_xml(NEW_TRANSLATIONS[name][folder_name])
            else:
                content = escape_for_android_xml(match.group("content"))

            output_lines.append(f'{indent}<string name="{name}">{content}</string>{tail}\n')
        else:
            output_lines.append(line)

    with open(target_file, "w", encoding="utf-8") as f:
        f.writelines(output_lines)

def main():
    if not os.path.exists(TEMPLATE_FILE):
        print(f"Error: {TEMPLATE_FILE} not found!")
        return

    ensure_structured_template()

    with open(TEMPLATE_FILE, "r", encoding="utf-8") as f:
        template_lines = f.readlines()

    for folder in sorted(os.listdir(RES_DIR)):
        if folder.startswith("values-") and os.path.isdir(os.path.join(RES_DIR, folder)):
            target_path = os.path.join(RES_DIR, folder, "strings.xml")
            if os.path.exists(target_path):
                translations = parse_translations_raw(target_path)
                realign_file(target_path, folder, translations, template_lines)
                print(f"Synced & aligned: {folder}/strings.xml")

    print("\nAll strings.xml files have been updated and structured!")

if __name__ == "__main__":
    main()
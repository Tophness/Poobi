package com.poobi.tvbrowser.shared

object LanguageHelper {
    val flagToLang = mapOf(
        "🇺🇸" to "English", "🇬🇧" to "English", "🇮🇹" to "Italian", "🇷🇺" to "Russian",
        "🇺🇦" to "Ukrainian", "🇪🇸" to "Spanish", "🇲🇽" to "Spanish", "🇵🇹" to "Portuguese",
        "🇧🇷" to "Portuguese", "🇫🇷" to "French", "🇩🇪" to "German", "🇵🇱" to "Polish",
        "🇮🇳" to "Hindi"
    )

    val codeToLang = mapOf(
        "eng" to "English", "ita" to "Italian", "rus" to "Russian", "ukr" to "Ukrainian",
        "spa" to "Spanish", "por" to "Portuguese", "fra" to "French", "ger" to "German",
        "pol" to "Polish", "hin" to "Hindi", "multi" to "Multi", "dual" to "Dual Audio",
        "mvo" to "MVO", "dvo" to "DVO"
    )

    val langToFlag = mapOf(
        "English" to "🇬🇧",
        "Italian" to "🇮🇹",
        "Russian" to "🇷🇺",
        "Ukrainian" to "🇺🇦",
        "Spanish" to "🇪🇸",
        "Portuguese" to "🇵🇹",
        "French" to "🇫🇷",
        "German" to "🇩🇪",
        "Polish" to "🇵🇱",
        "Hindi" to "🇮🇳"
    )

    fun getPrioritizationKeywords(languageName: String): List<String> {
        val list = mutableListOf<String>()
        
        // Match flags associated with this language
        flagToLang.filterValues { it == languageName }.keys.forEach { list.add(it) }
        
        // Match three-letter text codes associated with this language
        codeToLang.filterValues { it == languageName }.keys.forEach { list.add(it.uppercase()) }

        // Specific custom additions to match legacy mappings
        when (languageName) {
            "English" -> list.add("Original")
            "Spanish" -> list.add("Lat")
        }

        return list.distinct()
    }

    fun parseLanguages(title: String): String {
        if (title.isEmpty()) return ""

        val detectedLanguages = mutableSetOf<String>()

        val flagsRegex = """\uD83C[\uDDE6-\uDDFF]\uD83C[\uDDE6-\uDDFF]""".toRegex()
        flagsRegex.findAll(title).forEach { match ->
            val flag = match.value
            val lang = flagToLang[flag]
            if (lang != null) {
                detectedLanguages.add(lang)
            }
        }

        val wordRegex = """\b(eng|ita|rus|ukr|spa|por|fra|ger|pol|hin|multi|dual|mvo|dvo)\b""".toRegex(RegexOption.IGNORE_CASE)
        wordRegex.findAll(title).forEach { match ->
            val code = match.value.lowercase()
            val lang = codeToLang[code]
            if (lang != null) {
                detectedLanguages.add(lang)
            }
        }

        if (detectedLanguages.isEmpty()) {
            val hasCyrillic = title.any { it in '\u0400'..'\u04FF' }
            if (hasCyrillic) {
                detectedLanguages.add("Russian")
            } else {
                detectedLanguages.add("English")
            }
        }

        return detectedLanguages.map { lang ->
            val flag = langToFlag[lang]
            if (flag != null) "$flag $lang" else lang
        }.joinToString(" / ")
    }
}
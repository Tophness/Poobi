package com.poobi.tvbrowser.shared

object LanguageHelper {
    private val flagToLang = mapOf(
        "🇺🇸" to "English", "🇬🇧" to "English", "🇮🇹" to "Italian", "🇷🇺" to "Russian",
        "🇺🇦" to "Ukrainian", "🇪🇸" to "Spanish", "🇲🇽" to "Spanish", "🇵🇹" to "Portuguese",
        "🇧🇷" to "Portuguese", "🇫🇷" to "French", "🇩🇪" to "German", "🇵🇱" to "Polish",
        "🇮🇳" to "Hindi"
    )

    private val codeToLang = mapOf(
        "eng" to "English", "ita" to "Italian", "rus" to "Russian", "ukr" to "Ukrainian",
        "spa" to "Spanish", "por" to "Portuguese", "fra" to "French", "ger" to "German",
        "pol" to "Polish", "hin" to "Hindi", "multi" to "Multi", "dual" to "Dual Audio",
        "mvo" to "MVO", "dvo" to "DVO"
    )

    fun getPrioritizationKeywords(languageName: String): List<String> {
        val list = mutableListOf<String>()
        flagToLang.filterValues { it == languageName }.keys.forEach { list.add(it) }
        codeToLang.filterValues { it == languageName }.keys.forEach { list.add(it.uppercase()) }
        when (languageName) {
            "English" -> list.add("Original")
            "Spanish" -> list.add("Lat")
        }

        return list.distinct()
    }
}
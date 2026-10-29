package com.poobi.tvbrowser.shared

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class SupportedLanguage(
    val code: String,
    val iso3: String,
    val englishName: String,
    val nativeName: String,
    val flag: String = "",
    val altIso3: List<String> = emptyList()
)

object LanguageHelper {

    @Volatile
    private var _applicationContext: Context? = null

    var applicationContext: Context?
        get() = _applicationContext ?: try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val method = activityThreadClass.getMethod("currentApplication")
            method.invoke(null) as? Context
        } catch (_: Exception) {
            null
        }
        set(value) {
            _applicationContext = value
        }

    @JvmName("resolveApplicationContext")
    fun getApplicationContext(): Context? = applicationContext

    val ENGLISH = SupportedLanguage("en", "eng", "English", "English", "🇺🇸", listOf("en"))

    val supportedLanguages = listOf(
        ENGLISH,
        SupportedLanguage("es", "spa", "Spanish", "Español", "🇪🇸"),
        SupportedLanguage("fr", "fra", "French", "Français", "🇫🇷", listOf("fre")),
        SupportedLanguage("de", "deu", "German", "Deutsch", "🇩🇪", listOf("ger")),
        SupportedLanguage("it", "ita", "Italian", "Italiano", "🇮🇹"),
        SupportedLanguage("pt", "por", "Portuguese", "Português", "🇵🇹"),
        SupportedLanguage("ru", "rus", "Russian", "Русский", "🇷🇺"),
        SupportedLanguage("ja", "jpn", "Japanese", "日本語", "🇯🇵"),
        SupportedLanguage("ko", "kor", "Korean", "한국어", "🇰🇷"),
        SupportedLanguage("zh", "zho", "Chinese", "中文", "🇨🇳", listOf("chi")),
        SupportedLanguage("ar", "ara", "Arabic", "العربية", "🇸🇦"),
        SupportedLanguage("hi", "hin", "Hindi", "हिन्दी", "🇮🇳"),
        SupportedLanguage("bn", "ben", "Bengali", "বাংলা", "🇧🇩"),
        SupportedLanguage("tr", "tur", "Turkish", "Türkçe", "🇹🇷"),
        SupportedLanguage("vi", "vie", "Vietnamese", "Tiếng Việt", "🇻🇳"),
        SupportedLanguage("pl", "pol", "Polish", "Polski", "🇵🇱"),
        SupportedLanguage("nl", "nld", "Dutch", "Nederlands", "🇳🇱", listOf("dut")),
        SupportedLanguage("el", "ell", "Greek", "Ελληνικά", "🇬🇷", listOf("gre")),
        SupportedLanguage("sv", "swe", "Swedish", "Svenska", "🇸🇪"),
        SupportedLanguage("da", "dan", "Danish", "Dansk", "🇩🇰"),
        SupportedLanguage("no", "nor", "Norwegian", "Norsk", "🇳🇴"),
        SupportedLanguage("fi", "fin", "Finnish", "Suomi", "🇫🇮"),
        SupportedLanguage("cs", "ces", "Czech", "Čeština", "🇨🇿", listOf("cze")),
        SupportedLanguage("hu", "hun", "Hungarian", "Magyar", "🇭🇺"),
        SupportedLanguage("ro", "ron", "Romanian", "Română", "🇷🇴", listOf("rum")),
        SupportedLanguage("uk", "ukr", "Ukrainian", "Українська", "🇺🇦"),
        SupportedLanguage("th", "tha", "Thai", "ไทย", "🇹🇭"),
        SupportedLanguage("id", "ind", "Indonesian", "Bahasa Indonesia", "🇮🇩"),
        SupportedLanguage("ms", "msa", "Malay", "Bahasa Melayu", "🇲🇾", listOf("may")),
        SupportedLanguage("he", "heb", "Hebrew", "עברית", "🇮🇱"),
        SupportedLanguage("tl", "fil", "Filipino", "Filipino", "🇵🇭", listOf("tgl")),
        SupportedLanguage("fa", "fas", "Persian", "فارسی", "🇮🇷", listOf("per")),
        SupportedLanguage("ta", "tam", "Tamil", "தமிழ்", "🇮🇳"),
        SupportedLanguage("te", "tel", "Telugu", "తెలుగు", "🇮🇳"),
        SupportedLanguage("mr", "mar", "Marathi", "मराठी", "🇮🇳"),
        SupportedLanguage("ur", "urd", "Urdu", "اردو", "🇵🇰"),
        SupportedLanguage("sk", "slk", "Slovak", "Slovenčina", "🇸🇰", listOf("slo")),
        SupportedLanguage("bg", "bul", "Bulgarian", "Български", "🇧🇬"),
        SupportedLanguage("hr", "hrv", "Croatian", "Hrvatski", "🇭🇷"),
        SupportedLanguage("sr", "srp", "Serbian", "Српски", "🇷🇸"),
        SupportedLanguage("sl", "slv", "Slovenian", "Slovenščina", "🇸🇮"),
        SupportedLanguage("lt", "lit", "Lithuanian", "Lietuvių", "🇱🇹"),
        SupportedLanguage("lv", "lav", "Latvian", "Latviešu", "🇱🇻"),
        SupportedLanguage("et", "est", "Estonian", "Eesti", "🇪🇪"),
        SupportedLanguage("ca", "cat", "Catalan", "Català", "🇪🇸"),
        SupportedLanguage("eu", "eus", "Basque", "Euskara", "🇪🇸", listOf("baq")),
        SupportedLanguage("gl", "glg", "Galician", "Galego", "🇪🇸"),
        SupportedLanguage("is", "isl", "Icelandic", "Íslenska", "🇮🇸", listOf("ice")),
        SupportedLanguage("ga", "gle", "Irish", "Gaeilge", "🇮🇪"),
        SupportedLanguage("cy", "cym", "Welsh", "Cymraeg", "🇬🇧", listOf("wel")),
        SupportedLanguage("sw", "swa", "Swahili", "Kiswahili", "🇰🇪"),
        SupportedLanguage("af", "afr", "Afrikaans", "Afrikaans", "🇿🇦")
    )

    private val languageMapByCode: Map<String, SupportedLanguage> by lazy {
        val map = mutableMapOf<String, SupportedLanguage>()
        supportedLanguages.forEach { lang ->
            map[lang.code.lowercase()] = lang
            map[lang.iso3.lowercase()] = lang
            map[lang.englishName.lowercase()] = lang
            lang.altIso3.forEach { alt -> map[alt.lowercase()] = lang }
        }
        map
    }

    @Volatile
    var cachedDateFormatPref: String = "system"

    fun getSystemLanguage(): SupportedLanguage {
        val sysLang = Locale.getDefault().language
        return findLanguage(sysLang)
    }

    fun findLanguage(query: String?): SupportedLanguage {
        if (query.isNullOrBlank()) return ENGLISH
        return languageMapByCode[query.trim().lowercase()] ?: ENGLISH
    }

    fun getAppLanguage(context: Context): SupportedLanguage {
        applicationContext = context.applicationContext
        val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val code = prefs.getString("app_language", "system") ?: "system"
        return if (code.equals("system", ignoreCase = true)) {
            getSystemLanguage()
        } else {
            findLanguage(code)
        }
    }

    fun setAppLanguage(context: Context, langCode: String) {
        applicationContext = context.applicationContext
        val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val isSystem = langCode.equals("system", ignoreCase = true)
        val resolvedLang = if (isSystem) getSystemLanguage() else findLanguage(langCode)

        prefs.edit().apply {
            putString("app_language", if (isSystem) "system" else resolvedLang.code)
            putString("torrent_language", resolvedLang.englishName)
            val subLangList = if (resolvedLang.code == "en") "English" else "${resolvedLang.englishName}, English"
            putString("subtitles_languages", subLangList)
        }.apply()

        applyAppLanguage(context, if (isSystem) "system" else resolvedLang.code)
        AppCacheManager.clearAllTmdbCaches(context)
    }

    fun applyAppLanguage(context: Context, langCode: String) {
        applicationContext = context.applicationContext
        val effectiveCode = if (langCode.equals("system", ignoreCase = true)) {
            getSystemLanguage().code
        } else {
            langCode
        }
        val locale = Locale(effectiveCode)
        Locale.setDefault(locale)
        val appLocales = if (langCode.equals("system", ignoreCase = true)) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(effectiveCode)
        }
        AppCompatDelegate.setApplicationLocales(appLocales)
    }

    fun wrapContext(context: Context): Context {
        applicationContext = context.applicationContext
        val lang = getAppLanguage(context)
        val locale = Locale(lang.code)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
            return context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            return context
        }
    }

    fun formatDate(context: Context?, dateStr: String?): String {
        if (dateStr.isNullOrEmpty() || dateStr == "null" || dateStr == "0000") return dateStr ?: ""
        val clean = dateStr.trim()

        val parsedDate: Date = try {
            when {
                clean.contains("T") -> {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.parse(clean.replace("Z", "").split(".")[0])
                }
                clean.length >= 10 && clean.contains("-") -> {
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(clean)
                }
                clean.length >= 4 && clean.all { it.isDigit() } -> {
                    return clean.take(4)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        } ?: return clean

        val pref = if (context != null) {
            val p = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
            val v = p.getString("date_format_pref", "system") ?: "system"
            cachedDateFormatPref = v
            v
        } else {
            cachedDateFormatPref
        }

        return when (pref) {
            "DMY" -> SimpleDateFormat("d/M/yyyy", Locale.getDefault()).format(parsedDate)
            "MDY" -> SimpleDateFormat("M/d/yyyy", Locale.getDefault()).format(parsedDate)
            "YMD" -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(parsedDate)
            else -> {
                if (context != null) {
                    android.text.format.DateFormat.getDateFormat(context).format(parsedDate)
                } else {
                    SimpleDateFormat("d/M/yyyy", Locale.getDefault()).format(parsedDate)
                }
            }
        }
    }

    fun formatBirthAndAge(context: Context?, birthdayStr: String?, deathdayStr: String?): String {
        if (birthdayStr.isNullOrEmpty() || birthdayStr == "null") return ""
        try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val birthDate = parser.parse(birthdayStr) ?: return ""
            val birthCal = Calendar.getInstance().apply { time = birthDate }

            val endCal = Calendar.getInstance()
            val isDeceased = !deathdayStr.isNullOrEmpty() && deathdayStr != "null"
            if (isDeceased) {
                val deathDate = parser.parse(deathdayStr)
                if (deathDate != null) endCal.time = deathDate
            }

            var age = endCal.get(Calendar.YEAR) - birthCal.get(Calendar.YEAR)
            if (endCal.get(Calendar.DAY_OF_YEAR) < birthCal.get(Calendar.DAY_OF_YEAR)) {
                age--
            }

            val formattedBirth = formatDate(context, birthdayStr)
            return if (isDeceased) {
                val formattedDeath = formatDate(context, deathdayStr)
                "Died: $formattedDeath (Age $age) • Born: $formattedBirth"
            } else {
                "Age $age • Born: $formattedBirth"
            }
        } catch (_: Exception) {
            return "Born: ${formatDate(context, birthdayStr)}"
        }
    }

    fun formatDateTime(context: Context, dateTimeStr: String?): String {
        if (dateTimeStr.isNullOrEmpty()) return ""
        try {
            val parser = if (dateTimeStr.contains("T")) {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
            } else {
                SimpleDateFormat("yyyy-MM-dd", Locale.US)
            }
            val clean = dateTimeStr.replace("Z", "").split(".")[0]
            val date = parser.parse(clean) ?: return dateTimeStr

            val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
            val pref = prefs.getString("date_format_pref", "system") ?: "system"

            val timeFormatter = android.text.format.DateFormat.getTimeFormat(context)
            val formattedTime = timeFormatter.format(date)

            val formattedDate = when (pref) {
                "DMY" -> SimpleDateFormat("d MMMM", Locale.getDefault()).format(date)
                "MDY" -> SimpleDateFormat("MMMM d", Locale.getDefault()).format(date)
                "YMD" -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
                else -> android.text.format.DateFormat.getMediumDateFormat(context).format(date)
            }
            return "$formattedDate $formattedTime"
        } catch (_: Exception) {
            return dateTimeStr
        }
    }

    fun isMatchingAudioLanguage(
        trackLang: String?,
        trackLabel: String?,
        trackId: String?,
        targetLang: SupportedLanguage
    ): Boolean {
        val l = trackLang?.lowercase()?.trim() ?: ""
        if (l == targetLang.code || l == targetLang.iso3 || targetLang.altIso3.contains(l)) {
            return true
        }
        if (l.startsWith("${targetLang.code}-") || l.startsWith("${targetLang.code}_")) {
            return true
        }

        val namePattern = Regex("(?i)(?:^|[^a-zA-Z])(${targetLang.code}|${targetLang.iso3}|${targetLang.englishName}|${targetLang.nativeName})(?:[^a-zA-Z]|$)")

        val label = trackLabel ?: ""
        if (label.isNotEmpty() && namePattern.containsMatchIn(label)) {
            return true
        }

        val id = trackId ?: ""
        if (id.isNotEmpty() && namePattern.containsMatchIn(id)) {
            return true
        }

        return false
    }

    fun getAudioTrackScore(
        language: String?,
        label: String?,
        id: String?,
        channelCount: Int,
        bitrate: Int,
        targetLang: SupportedLanguage
    ): Int {
        val isTarget = isMatchingAudioLanguage(language, label, id, targetLang)
        val isEnglish = !isTarget && targetLang.code != "en" && isMatchingAudioLanguage(language, label, id, ENGLISH)

        if (!isTarget && !isEnglish) return -1

        var score = if (isTarget) 1000 else 200

        val cleanLabel = (label ?: "").lowercase()
        val cleanId = (id ?: "").lowercase()

        if (cleanLabel.contains("commentary") || cleanId.contains("commentary") ||
            cleanLabel.contains("director") || cleanId.contains("director")) {
            score -= 500
        }
        if (cleanLabel.contains("descriptive") || cleanLabel.contains("description") || cleanLabel.contains("audio desc") ||
            cleanId.contains("descriptive") || cleanId.contains("description")) {
            score -= 300
        }

        if (cleanLabel.contains("original") || cleanId.contains("original")) {
            score += 100
        }

        if (channelCount > 0) {
            score += channelCount * 10
        }
        if (bitrate > 0) {
            score += (bitrate / 10000).coerceAtMost(50)
        }

        return score
    }

    fun getLanguageInfo(url: String): Pair<String, String> {
        val uri = Uri.parse(url)
        val fileName = uri.lastPathSegment?.lowercase() ?: ""

        for (lang in supportedLanguages) {
            val code = lang.code
            val iso3 = lang.iso3
            val name = lang.englishName.lowercase()

            val matches = fileName.contains(name) ||
                    fileName.contains(".$code.") || fileName.contains("-$code.") || fileName.contains("_$code.") || fileName.startsWith("$code.") ||
                    fileName.contains(".$iso3.") || fileName.contains("-$iso3.") || fileName.contains("_$iso3.") || fileName.startsWith("$iso3.")

            if (matches) {
                var label = lang.englishName
                if (fileName.contains("sdh")) label += " (SDH)"
                if (fileName.contains("forced")) label += " (Forced)"
                return Pair(code, label)
            }
        }

        val cleanName = fileName.substringBeforeLast(".").replace(Regex("[-_]"), " ").trim()
        if (cleanName.length > 2 && !cleanName.all { it.isDigit() || it == ' ' }) {
            return Pair("und", cleanName.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } })
        }
        return Pair("und", "Unknown")
    }

    fun getPrioritizationKeywords(languageName: String): List<String> {
        val lang = findLanguage(languageName)
        val list = mutableListOf<String>()

        if (lang.flag.isNotEmpty()) list.add(lang.flag)
        list.add(lang.englishName)
        list.add(lang.nativeName)
        list.add(lang.iso3.uppercase())
        lang.altIso3.forEach { list.add(it.uppercase()) }

        when (lang.code) {
            "en" -> list.add("Original")
            "es" -> {
                list.add("Lat")
                list.add("Castellano")
                list.add("Latino")
            }
            "pt" -> list.add("Dublado")
            "ru" -> {
                list.add("DVO")
                list.add("MVO")
                list.add("Дубляж")
            }
        }
        return list.distinct()
    }
}
package com.poobi.tvbrowser.shared

import android.net.Uri
import java.net.URLDecoder

fun parseKodiUrl(rawUrl: String): Pair<String, Map<String, String>> {
    if (!rawUrl.contains("|")) {
        return Pair(rawUrl, emptyMap())
    }
    val index = rawUrl.indexOf("|")
    if (index == -1) return Pair(rawUrl, emptyMap())
    
    val url = rawUrl.substring(0, index)
    val headerPart = rawUrl.substring(index + 1)

    if (headerPart.startsWith("http://") || headerPart.startsWith("https://")) {
        return Pair(rawUrl, emptyMap())
    }
    
    if (headerPart.contains("=")) {
        val headers = mutableMapOf<String, String>()
        val pairs = headerPart.split("&")
        for (pair in pairs) {
            val kv = pair.split("=")
            if (kv.size == 2) {
                try {
                    val key = URLDecoder.decode(kv[0], "UTF-8")
                    val value = URLDecoder.decode(kv[1], "UTF-8")
                    headers[key] = value
                } catch (e: Exception) {
                    val key = Uri.decode(kv[0])
                    val value = Uri.decode(kv[1])
                    headers[key] = value
                }
            }
        }
        return Pair(url, headers)
    }
    
    return Pair(rawUrl, emptyMap())
}
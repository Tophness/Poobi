package com.poobi.tvbrowser.shared

interface PythonDialogListener {
    fun infoDialog(message: String, heading: String, sound: Boolean, icon: String)
    fun okDialog(message: String, heading: String): Boolean
    fun yesnoDialog(message: String, heading: String, nolabel: String, yeslabel: String): Boolean
    fun selectDialog(options: List<String>, heading: String): Int
    fun captchaDialog(imageBytes: ByteArray, heading: String): String?
}

fun String.cleanKodiText(): String = this.replace("[CR]", "\n").replace("[B]", "").replace("[/B]", "")

data class SubtitleData(val url: String, val label: String, val lang: String)

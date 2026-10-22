package com.poobi.tvbrowser.browser

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brave.adblock.AdBlockClient
import com.poobi.tvbrowser.shared.TvFocusableBox
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

object AdBlockManager {
    private const val TAG = "AdBlockManager"
    private const val PREFS_KEY_RULE_COUNT = "adblock_cached_rule_count"
    private const val DEFAULT_EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
    private const val DEFAULT_DNS_FILTER_URL = "https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_15_DnsFilter/filter.txt"

    private var client: AdBlockClient? = null
    private val clientLock = ReentrantReadWriteLock()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val _ruleCount = MutableStateFlow(0)
    val ruleCount: StateFlow<Int> = _ruleCount.asStateFlow()

    val showSetupDialog = MutableStateFlow(false)
    val setupProgress = MutableStateFlow(0f)
    val setupStatus = MutableStateFlow("Starting...")

    private var setupDownloadJob: Job? = null
    private var hasDismissedThisSession = false

    private val domainCosmeticMap = ConcurrentHashMap<String, MutableList<String>>()

    private val BASE_COSMETIC_CSS = """
        .adbox, .banner_ads, .adsbox, .textads, #tester-banners, .ad-banner,
        [id*="tester-banner"], [class*="tester-banner"],
        ins.adsbygoogle, div[id^="div-gpt-ad"], div[id^="google_ads_"],
        [class*="google-auto-placed"], iframe[src*="googlesyndication.com"],
        iframe[src*="doubleclick.net"], [data-ad-client], [data-ad-slot],
        .ad-container, .ad-wrapper, .ad-placement, .ad-slot, .ad_unit,
        .ad-banner, .banner-ad, .sidebar-ads, .header-ad, .footer-ad,
        .ad-holder, .ad-box, .ads-wrapper, .ad_wrapper, .ads-container,
        [class*="advertisement"], [id*="advertisement"],
        .sponsored-post, .sponsor-container, [data-ad-unit],
        .popunder, .pop-under, [class*="popunder"], [id*="popunder"],
        [class*="popup-ad"], [id*="popup-ad"], .floating-ad,
        .floating-banner, [class*="floating-ad"], [id*="floating-ad"],
        .video-ads, .ytp-ad-module, .ytp-ad-overlay-container, .vjs-ad-control
        { display: none !important; visibility: hidden !important; width: 0 !important; height: 0 !important; max-height: 0 !important; opacity: 0 !important; pointer-events: none !important; }
    """.trimIndent()

    private val SEED_RULES = """
        ! Core Ad & Tracker Blocklist
        ||google-analytics.com^${'$'}third-party
        ||googletagmanager.com^${'$'}third-party
        ||googlesyndication.com^
        ||googleadservices.com^
        ||pagead2.googlesyndication.com^
        ||securepubads.g.doubleclick.net^
        ||doubleclick.net^
        ||adservice.google.com^
        ||adnxs.com^
        ||criteo.com^
        ||criteo.net^
        ||taboola.com^
        ||outbrain.com^
        ||popads.net^
        ||popcash.net^
        ||propellerads.com^
        ||adcash.com^
        ||adsterra.com^
        ||exoclick.com^
        ||juicyads.com^
        ||trafficjunky.com^
        ||hotjar.com^${'$'}third-party
        ||facebook.com/tr^
        ||connect.facebook.net/*/fbevents.js^
        ||clarity.ms^
        /pagead.js${'$'}script
        /ads.js${'$'}script
        /adframe.js${'$'}script
        /banner.js${'$'}script
    """.trimIndent()

    private val TRANSPARENT_1X1_PNG = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
        Base64.DEFAULT
    )

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        _ruleCount.value = prefs.getInt(PREFS_KEY_RULE_COUNT, 0)

        CoroutineScope(Dispatchers.IO).launch {
            clientLock.writeLock().lock()
            try {
                if (client == null) {
                    client = AdBlockClient().apply {
                        parse(SEED_RULES)
                    }
                }
            } finally {
                clientLock.writeLock().unlock()
            }

            loadAllRules(context)
        }
    }

    fun checkAndPromptInitialSetup(context: Context) {
        if (showSetupDialog.value || _isUpdating.value || hasDismissedThisSession) return

        val adblockFile = File(context.filesDir, "adblock_rules.txt")
        val datFile = File(context.filesDir, "adblock_compiled.dat")
        val needsRules = (!adblockFile.exists() || adblockFile.length() < 1000) && (!datFile.exists() || datFile.length() < 1000)

        if (needsRules) {
            val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
            val customUrl = prefs.getString("custom_adblock_url", DEFAULT_EASYLIST_URL) ?: DEFAULT_EASYLIST_URL
            startInitialDownloadFlow(context, customUrl)
        }
    }

    private fun startInitialDownloadFlow(context: Context, easyListUrl: String) {
        showSetupDialog.value = true
        setupProgress.value = 0.05f
        setupStatus.value = "Connecting to filter servers..."

        setupDownloadJob?.cancel()
        setupDownloadJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val okEasyList = downloadRuleFileWithProgress(
                    context = context,
                    urlString = easyListUrl,
                    fileName = "adblock_rules.txt",
                    startProgress = 0.05f,
                    endProgress = 0.60f,
                    statusLabel = "Downloading EasyList (1/2)"
                )

                if (!isActive) return@launch

                val okDns = downloadRuleFileWithProgress(
                    context = context,
                    urlString = DEFAULT_DNS_FILTER_URL,
                    fileName = "dns_rules.txt",
                    startProgress = 0.60f,
                    endProgress = 0.90f,
                    statusLabel = "Downloading DNS & Tracker filters (2/2)"
                )

                if (!isActive) return@launch

                setupProgress.value = 0.95f
                setupStatus.value = "Compiling & activating rules..."

                if (okEasyList || okDns) {
                    compileAndSaveRules(context)
                    setupProgress.value = 1.0f
                    setupStatus.value = "Done! ${_ruleCount.value} rules loaded."
                    delay(800)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "AdBlocker ready: ${_ruleCount.value} rules active", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    setupStatus.value = "Download failed (offline). Using base protection."
                    delay(1500)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Initial download flow failed", e)
                    setupStatus.value = "Setup failed. Continuing with basic filters."
                    delay(1200)
                }
            } finally {
                showSetupDialog.value = false
            }
        }
    }

    private suspend fun downloadRuleFileWithProgress(
        context: Context,
        urlString: String,
        fileName: String,
        startProgress: Float,
        endProgress: Float,
        statusLabel: String
    ): Boolean = withContext(Dispatchers.IO) {
        val tempFile = File(context.filesDir, "$fileName.tmp")
        try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 25000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = conn.responseCode
            if (code !in 200..299) return@withContext false

            val totalBytes = conn.contentLength.toLong()

            var downloadedBytes = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (!currentCoroutineContext().isActive) {
                            tempFile.delete()
                            return@withContext false
                        }
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        if (totalBytes > 0) {
                            val fraction = downloadedBytes.toFloat() / totalBytes.toFloat()
                            val currentProg = (startProgress + fraction * (endProgress - startProgress)).coerceIn(0f, 1f)
                            setupProgress.value = currentProg
                            val mbDownloaded = downloadedBytes / (1024f * 1024f)
                            val mbTotal = totalBytes / (1024f * 1024f)
                            setupStatus.value = "$statusLabel: %.1f / %.1f MB".format(mbDownloaded, mbTotal)
                        } else {
                            val mbDownloaded = downloadedBytes / (1024f * 1024f)
                            setupStatus.value = "$statusLabel: %.1f MB".format(mbDownloaded)
                        }
                    }
                }
            }

            if (tempFile.length() > 500) {
                val finalFile = File(context.filesDir, fileName)
                if (finalFile.exists()) finalFile.delete()
                tempFile.renameTo(finalFile)
                true
            } else {
                tempFile.delete()
                false
            }
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            if (e is CancellationException) throw e
            Log.e(TAG, "Error downloading $fileName", e)
            false
        }
    }

    fun cancelSetup() {
        hasDismissedThisSession = true
        setupDownloadJob?.cancel()
        setupDownloadJob = null
        showSetupDialog.value = false
    }

    fun dismissSetupDialog() {
        showSetupDialog.value = false
    }

    fun updateAllRules(context: Context, customEasyListUrl: String = DEFAULT_EASYLIST_URL, onComplete: ((Boolean) -> Unit)? = null) {
        if (_isUpdating.value) return
        _isUpdating.value = true

        CoroutineScope(Dispatchers.IO).launch {
            var success = false
            try {
                val okEasyList = fetchAndSaveRuleFile(context, customEasyListUrl, "adblock_rules.txt")
                val okDns = fetchAndSaveRuleFile(context, DEFAULT_DNS_FILTER_URL, "dns_rules.txt")
                success = okEasyList || okDns
                if (success) {
                    compileAndSaveRules(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating all filter lists", e)
            } finally {
                _isUpdating.value = false
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(success)
                }
            }
        }
    }

    private fun fetchAndSaveRuleFile(context: Context, urlString: String, fileName: String): Boolean {
        return try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode in 200..299) {
                val content = conn.inputStream.bufferedReader().use { it.readText() }
                if (content.length > 500) {
                    val file = File(context.filesDir, fileName)
                    file.writeText(content)
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading filter: $fileName from $urlString", e)
            false
        }
    }

    private fun loadAllRules(context: Context) {
        val datFile = File(context.filesDir, "adblock_compiled.dat")
        val cosmeticFile = File(context.filesDir, "cosmetic_rules.txt")
        val adblockFile = File(context.filesDir, "adblock_rules.txt")

        if (datFile.exists() && datFile.length() > 5000) {
            val cachedClient = AdBlockClient()
            if (cachedClient.deserialize(datFile.absolutePath)) {
                loadCosmeticCache(cosmeticFile)
                val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
                val savedCount = prefs.getInt(PREFS_KEY_RULE_COUNT, 205500)
                clientLock.writeLock().lock()
                try {
                    client = cachedClient
                    _ruleCount.value = savedCount
                } finally {
                    clientLock.writeLock().unlock()
                }
                return
            }
        }

        if (adblockFile.exists() && adblockFile.length() > 1000) {
            compileAndSaveRules(context)
        }
    }

    private fun compileAndSaveRules(context: Context) {
        val adblockFile = File(context.filesDir, "adblock_rules.txt")
        val dnsFile = File(context.filesDir, "dns_rules.txt")
        val datFile = File(context.filesDir, "adblock_compiled.dat")
        val cosmeticFile = File(context.filesDir, "cosmetic_rules.txt")

        val newClient = AdBlockClient().apply {
            parse(SEED_RULES)
        }

        domainCosmeticMap.clear()
        var totalLoaded = 30

        if (adblockFile.exists() && adblockFile.length() > 100) {
            newClient.parseFile(adblockFile.absolutePath)
            totalLoaded += parseAndSaveCosmeticRules(adblockFile, cosmeticFile)
        }
        if (dnsFile.exists() && dnsFile.length() > 100) {
            newClient.parseFile(dnsFile.absolutePath)
            totalLoaded += (dnsFile.length() / 40).toInt().coerceAtLeast(100)
        }

        try {
            newClient.serialize(datFile.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to serialize binary adblock rules snapshot", e)
        }

        clientLock.writeLock().lock()
        try {
            client = newClient
            _ruleCount.value = totalLoaded
            val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
            prefs.edit().putInt(PREFS_KEY_RULE_COUNT, totalLoaded).apply()
        } finally {
            clientLock.writeLock().unlock()
        }
    }

    private fun loadCosmeticCache(file: File) {
        domainCosmeticMap.clear()
        if (!file.exists()) return
        try {
            file.forEachLine { line ->
                val eqIdx = line.indexOf('=')
                if (eqIdx > 0) {
                    val domain = line.substring(0, eqIdx).trim()
                    val selector = line.substring(eqIdx + 1).trim()
                    if (domain.isNotEmpty() && selector.isNotEmpty()) {
                        domainCosmeticMap.getOrPut(domain) { mutableListOf() }.add(selector)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun parseAndSaveCosmeticRules(sourceFile: File, outputFile: File): Int {
        if (!sourceFile.exists() || sourceFile.length() < 100) return 0
        var linesCount = 0
        val sb = StringBuilder()
        try {
            sourceFile.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("!")) return@forEachLine
                linesCount++

                if (trimmed.startsWith("##") || !trimmed.contains("##")) return@forEachLine

                val parts = trimmed.split("##", limit = 2)
                if (parts.size == 2) {
                    val domainsPart = parts[0].trim()
                    val selector = parts[1].trim()
                    if (domainsPart.isNotEmpty() && !domainsPart.contains("~") && selector.isNotEmpty()) {
                        val domains = domainsPart.split(",")
                        for (d in domains) {
                            val cleanD = d.trim()
                            if (cleanD.isNotEmpty()) {
                                domainCosmeticMap.getOrPut(cleanD) { mutableListOf() }.add(selector)
                                sb.append(cleanD).append('=').append(selector).append('\n')
                            }
                        }
                    }
                }
            }
            outputFile.writeText(sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing cosmetic rules", e)
        }
        return linesCount
    }

    suspend fun updateRules(context: Context, urlString: String, fileName: String = "adblock_rules.txt"): Boolean = withContext(Dispatchers.IO) {
        val ok = fetchAndSaveRuleFile(context, urlString, fileName)
        if (ok) {
            compileAndSaveRules(context)
        }
        ok
    }

    fun shouldBlock(url: String, option: AdBlockClient.FilterOption, host: String): Boolean {
        clientLock.readLock().lock()
        try {
            return client?.matches(url, option, host) == true
        } catch (_: Exception) {
            return false
        } finally {
            clientLock.readLock().unlock()
        }
    }

    fun getCosmeticCss(domain: String): String {
        val domainSelectors = mutableListOf<String>()
        if (domain.isNotEmpty()) {
            domainCosmeticMap[domain]?.let { domainSelectors.addAll(it) }
            val dotIdx = domain.indexOf('.')
            if (dotIdx != -1 && dotIdx < domain.length - 1) {
                val rootDomain = domain.substring(dotIdx + 1)
                domainCosmeticMap[rootDomain]?.let { domainSelectors.addAll(it) }
            }
        }

        return if (domainSelectors.isEmpty()) {
            BASE_COSMETIC_CSS
        } else {
            val customRules = domainSelectors.joinToString(", ") + " { display: none !important; visibility: hidden !important; height: 0 !important; width: 0 !important; max-height: 0 !important; opacity: 0 !important; pointer-events: none !important; }"
            "$BASE_COSMETIC_CSS\n$customRules"
        }
    }

    fun injectCosmeticCss(view: WebView?, domain: String) {
        if (view == null) return
        val css = getCosmeticCss(domain).replace("\n", " ").replace("'", "\\'")
        val js = """
            (function() {
                var elId = 'poobi-cosmetic-adblock';
                var style = document.getElementById(elId);
                if (!style) {
                    style = document.createElement('style');
                    style.id = elId;
                    (document.head || document.documentElement).appendChild(style);
                }
                style.textContent = '$css';
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    fun getSurrogateScript(): String {
        return """
            (function() {
                if (window.__poobiDefusersLoaded) return;
                window.__poobiDefusersLoaded = true;
                window.canRunAds = true;
                window.isAdBlockActive = false;
                if (!window.adsbygoogle) {
                    window.adsbygoogle = {
                        push: function() { return true; },
                        loaded: true
                    };
                }
                if (!window.ga) {
                    window.ga = function() {};
                    window.ga.q = [];
                    window.ga.l = Date.now();
                }
                if (!window.gtag) {
                    window.gtag = function() {};
                }
            })();
        """.trimIndent()
    }

    fun createBlockedResponse(option: AdBlockClient.FilterOption): WebResourceResponse {
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Cache-Control" to "no-store"
        )
        return when (option) {
            AdBlockClient.FilterOption.IMAGE -> {
                WebResourceResponse("image/png", "utf-8", 200, "OK", headers, ByteArrayInputStream(TRANSPARENT_1X1_PNG))
            }
            AdBlockClient.FilterOption.SCRIPT -> {
                WebResourceResponse("application/javascript", "utf-8", 200, "OK", headers, ByteArrayInputStream("/* blocked by adblock */".toByteArray()))
            }
            AdBlockClient.FilterOption.CSS -> {
                WebResourceResponse("text/css", "utf-8", 200, "OK", headers, ByteArrayInputStream("/* blocked by adblock */".toByteArray()))
            }
            AdBlockClient.FilterOption.SUBDOCUMENT, AdBlockClient.FilterOption.DOCUMENT -> {
                WebResourceResponse("text/html", "utf-8", 200, "OK", headers, ByteArrayInputStream("<!DOCTYPE html><html><head></head><body></body></html>".toByteArray()))
            }
            else -> {
                WebResourceResponse("text/plain", "utf-8", 200, "OK", headers, ByteArrayInputStream("".toByteArray()))
            }
        }
    }
}

@Composable
fun AdBlockSetupDialog() {
    val showDialog by AdBlockManager.showSetupDialog.collectAsState()
    val progress by AdBlockManager.setupProgress.collectAsState()
    val status by AdBlockManager.setupStatus.collectAsState()

    if (showDialog) {
        val cancelFocusRequester = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            delay(150)
            try {
                cancelFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }

        AlertDialog(
            onDismissRequest = { AdBlockManager.cancelSetup() },
            title = {
                Text(
                    text = "Setting up AdBlock Engine",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            containerColor = Color(0xFF222225),
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Downloading the latest adblock rules. This only happens once.",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = Color(0xFF00BCD4),
                        trackColor = Color(0xFF333338)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = status,
                            color = Color(0xFF00BCD4),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TvFocusableBox(
                        modifier = Modifier
                            .height(38.dp)
                            .focusRequester(cancelFocusRequester),
                        defaultColor = Color(0xFFE53935),
                        shape = RoundedCornerShape(19.dp),
                        onClick = { AdBlockManager.cancelSetup() }
                    ) { isFocused ->
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Cancel",
                                color = if (isFocused) Color.Black else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    TvFocusableBox(
                        modifier = Modifier.height(38.dp),
                        defaultColor = Color(0xFF333338),
                        shape = RoundedCornerShape(19.dp),
                        onClick = { AdBlockManager.dismissSetupDialog() }
                    ) { isFocused ->
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (progress >= 1f) "Done" else "Run in Background",
                                color = if (isFocused) Color.Black else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        )
    }
}
package com.poobi.tvbrowser.browser

import android.content.Context
import com.brave.adblock.AdBlockClient
import kotlinx.coroutines.*
import java.io.File
import java.net.URL
import java.util.concurrent.locks.ReentrantReadWriteLock

object AdBlockManager {
    private var client: AdBlockClient? = null
    private val clientLock = ReentrantReadWriteLock()

    fun init(context: Context) {
        // Initial client creation
        clientLock.writeLock().lock()
        try {
            if (client == null) {
                client = AdBlockClient()
            }
        } finally {
            clientLock.writeLock().unlock()
        }

        // Load rules in background to avoid blocking Main Thread (the "halt")
        CoroutineScope(Dispatchers.IO).launch {
            loadAllRules(context)
            
            // Check for DNS filter if missing
            val dnsFile = File(context.filesDir, "dns_rules.txt")
            if (!dnsFile.exists()) {
                updateRules(context, "https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_15_DnsFilter/filter.txt", "dns_rules.txt")
            }
        }
    }

    private fun loadAllRules(context: Context) {
        val adblockFile = File(context.filesDir, "adblock_rules.txt")
        val dnsFile = File(context.filesDir, "dns_rules.txt")

        // We use a write lock when parsing files to ensure no one is matching during this time
        // However, parseFile itself is usually slow. 
        // A better way is to parse into a NEW client and then swap.
        val newClient = AdBlockClient()
        if (adblockFile.exists()) {
            newClient.parseFile(adblockFile.absolutePath)
        }
        if (dnsFile.exists()) {
            newClient.parseFile(dnsFile.absolutePath)
        }

        clientLock.writeLock().lock()
        try {
            client = newClient
            // The old client will be cleaned up by GC/finalize
        } finally {
            clientLock.writeLock().unlock()
        }
    }

    // Fetches rules on a background thread
    suspend fun updateRules(context: Context, urlString: String, fileName: String = "adblock_rules.txt"): Boolean = withContext(Dispatchers.IO) {
        try {
            val rules = URL(urlString).readText()
            val file = File(context.filesDir, fileName)
            file.writeText(rules)
            
            // Reload all rules into a new client instance
            loadAllRules(context)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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
}

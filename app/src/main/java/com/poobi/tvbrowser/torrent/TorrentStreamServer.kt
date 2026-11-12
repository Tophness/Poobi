package com.poobi.tvbrowser.torrent

import android.content.Context
import android.net.Uri
import android.util.Log
import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.swig.settings_pack
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.RandomAccessFile
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Filter
import java.util.logging.Logger

data class TorrentCacheItem(val name: String, val size: Long, val path: String)

class TorrentStreamServer private constructor(
    private val context: Context,
    port: Int = 11470
) : NanoHTTPD("127.0.0.1", port) {

    private val sessionManager = SessionManager()
    private val torrentStorageDir: File = File(context.cacheDir, "torrent_temp").apply { mkdirs() }
    private var activeTorrentHandle: TorrentHandle? = null
    private val torrentLock = Any()

    @Volatile
    var forcePlayTriggered: Boolean = false

    companion object {
        private const val TAG = "TorrentServer"
        
        init {
            try {
                val nanoLogger = Logger.getLogger(NanoHTTPD::class.java.name)
                nanoLogger.filter = Filter { record ->
                    val thrown = record.thrown
                    if (thrown is SocketException || thrown?.cause is SocketException) {
                        return@Filter false
                    }
                    true
                }
            } catch (_: Throwable) {}

            try {
                System.loadLibrary("jlibtorrent")
            } catch (_: Throwable) {
                try {
                    System.loadLibrary("jlibtorrent-2.0.12.9")
                } catch (_: Throwable) {}
            }
        }

        val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://tracker.bittor.pw:1337/announce",
            "udp://public.popcorn-tracker.org:6969/announce",
            "udp://tracker.dler.org:6969/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://explodie.org:6969/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "http://tracker.openbittorrent.com:80/announce"
        )

        @Volatile
        private var instance: TorrentStreamServer? = null

        fun getInstance(context: Context): TorrentStreamServer {
            return instance ?: synchronized(this) {
                instance ?: TorrentStreamServer(context.applicationContext).also { instance = it }
            }
        }

        fun stopInstance() {
            instance?.stopServer()
            instance = null
        }
    }

    init {
        try {
            val sp = SettingsPack()
            sp.listenInterfaces("0.0.0.0:6881")
            sp.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            sp.setString(
                settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
                "router.bittorrent.com:6881,dht.transmissionbt.com:6881,router.utorrent.com:6881,dht.libtorrent.org:25401"
            )
            sp.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
            sp.connectionsLimit(250)
            sp.activeDownloads(30)
            sp.activeLimit(50)
            sp.setInteger(settings_pack.int_types.checking_mem_usage.swigValue(), 1024)

            val params = SessionParams(sp)
            sessionManager.start(params)
            sessionManager.startDht()

            sessionManager.addListener(object : AlertListener {
                override fun types(): IntArray? = intArrayOf(
                    AlertType.SAVE_RESUME_DATA.swig()
                )
                override fun alert(alert: Alert<*>?) {
                    if (alert?.type() == AlertType.SAVE_RESUME_DATA) {
                        try {
                            val sAlert = alert as? com.frostwire.jlibtorrent.alerts.SaveResumeDataAlert
                            val p = sAlert?.params()
                            val h = sAlert?.handle()
                            val hash = h?.infoHash()?.toString()?.lowercase() ?: return
                            if (p != null) {
                                val entry = com.frostwire.jlibtorrent.AddTorrentParams.writeResumeData(p)
                                val resumeFile = File(torrentStorageDir, "$hash.resume")
                                resumeFile.writeBytes(entry.bencode())
                            }
                        } catch (_: Throwable) {}
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SessionManager settings", e)
            try {
                sessionManager.start()
            } catch (_: Exception) {}
        }
    }

    fun stopServer() {
        try {
            stop()
            stopActiveStreams()
            sessionManager.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    fun stopActiveStreams() {
        synchronized(torrentLock) {
            activeTorrentHandle?.let { handle ->
                try {
                    if (handle.isValid) {
                        saveTorrentMetadataAndResume(handle)
                        handle.pause()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Exception pausing active handle", e)
                }
            }
        }
    }

    fun isTorrentReady(infoHash: String, fileIdx: Int, prebufferPiecesLimit: Int): Boolean {
        val cleanHash = infoHash.lowercase().trim()
        val handle = synchronized(torrentLock) {
            if (activeTorrentHandle?.infoHash()?.toString()?.lowercase() == cleanHash && activeTorrentHandle?.isValid == true) {
                activeTorrentHandle
            } else {
                sessionManager.find(Sha1Hash(cleanHash)) ?: getOrAddTorrent(cleanHash)
            }
        } ?: return false

        if (!handle.isValid) return false
        val tInfo = handle.torrentFile() ?: return false
        if (fileIdx >= tInfo.files().numFiles()) return false

        val fileOffset = tInfo.files().fileOffset(fileIdx)
        val fileSize = tInfo.files().fileSize(fileIdx)
        val pieceLength = tInfo.pieceLength()
        if (pieceLength <= 0) return false

        val startPiece = (fileOffset / pieceLength).toInt()
        val endPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt().coerceAtLeast(startPiece)

        val minPiecesFor2Mb = (2L * 1024L * 1024L / pieceLength.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val prebufferCount = maxOf(prebufferPiecesLimit, minPiecesFor2Mb).coerceIn(1, 8)
        val startPiecesToBuffer = (startPiece..minOf(startPiece + (prebufferCount - 1), endPiece)).toList()
        val requiredPieces = (startPiecesToBuffer + if (endPiece > startPiecesToBuffer.last()) listOf(endPiece) else emptyList()).distinct()

        return requiredPieces.all { handle.havePiece(it) }
    }

    fun resumeTorrent(infoHash: String) {
        val cleanHash = infoHash.lowercase().trim()
        synchronized(torrentLock) {
            val handle = if (activeTorrentHandle?.infoHash()?.toString()?.lowercase() == cleanHash && activeTorrentHandle?.isValid == true) {
                activeTorrentHandle
            } else {
                sessionManager.find(Sha1Hash(cleanHash)) ?: getOrAddTorrent(cleanHash)
            }
            if (handle != null && handle.isValid) {
                try {
                    handle.resume()
                } catch (_: Exception) {}
                activeTorrentHandle = handle
            }
        }
    }

    private fun saveTorrentMetadataAndResume(handle: TorrentHandle) {
        try {
            val hash = handle.infoHash()?.toString()?.lowercase() ?: return
            val tFile = handle.torrentFile()
            if (tFile != null) {
                val metaFile = File(torrentStorageDir, "$hash.torrent")
                if (!metaFile.exists()) {
                    metaFile.writeBytes(tFile.bencode())
                }
            }
            try {
                handle.saveResumeData()
            } catch (_: Throwable) {}
        } catch (_: Exception) {}
    }

    fun getCacheSize(): Long {
        return getFolderSize(torrentStorageDir)
    }

    fun getCacheItems(): List<TorrentCacheItem> {
        val list = mutableListOf<TorrentCacheItem>()
        val files = torrentStorageDir.listFiles() ?: return list
        for (file in files) {
            list.add(TorrentCacheItem(
                name = file.name,
                size = getFolderSize(file),
                path = file.absolutePath
            ))
        }
        return list
    }

    fun deleteCacheItem(path: String) {
        val file = File(path)
        if (file.exists()) {
            file.deleteRecursively()
        }
    }

    fun deleteTorrent(infoHash: String) {
        val cleanHash = infoHash.lowercase().trim()
        synchronized(torrentLock) {
            var handleToRemove: TorrentHandle? = null
            if (activeTorrentHandle?.infoHash()?.toString()?.lowercase() == cleanHash) {
                handleToRemove = activeTorrentHandle
                activeTorrentHandle = null
            } else {
                handleToRemove = sessionManager.find(Sha1Hash(cleanHash))
            }

            var filesToDelete = listOf<String>()
            if (handleToRemove != null && handleToRemove.isValid) {
                try {
                    val tFile = handleToRemove.torrentFile()
                    if (tFile != null) {
                        val numFiles = tFile.files().numFiles()
                        filesToDelete = (0 until numFiles).map { tFile.files().filePath(it) }
                    }
                    sessionManager.remove(handleToRemove)
                } catch (_: Exception) {}
            }

            val metaFile = File(torrentStorageDir, "$cleanHash.torrent")
            if (filesToDelete.isEmpty() && metaFile.exists()) {
                try {
                    val tInfo = com.frostwire.jlibtorrent.TorrentInfo(metaFile)
                    val numFiles = tInfo.files().numFiles()
                    filesToDelete = (0 until numFiles).map { tInfo.files().filePath(it) }
                } catch (_: Exception) {}
            }

            for (relPath in filesToDelete) {
                try {
                    val f = File(torrentStorageDir, relPath)
                    if (f.exists()) {
                        f.delete()
                        f.parentFile?.let { parent ->
                            if (parent != torrentStorageDir && parent.listFiles()?.isEmpty() == true) {
                                parent.delete()
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            File(torrentStorageDir, "$cleanHash.torrent").delete()
            File(torrentStorageDir, "$cleanHash.resume").delete()
            File(torrentStorageDir, ".$cleanHash.parts").delete()
        }
    }

    fun clearAllCache() {
        synchronized(torrentLock) {
            activeTorrentHandle?.let { handle ->
                try {
                    if (handle.isValid) {
                        sessionManager.remove(handle)
                    }
                } catch (_: Exception) {}
            }
            activeTorrentHandle = null
        }
        if (torrentStorageDir.exists()) {
            torrentStorageDir.deleteRecursively()
            torrentStorageDir.mkdirs()
        }
    }

    private fun getActualFileSize(file: File): Long {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                val stat = android.system.Os.lstat(file.absolutePath)
                stat.st_blocks * 512L
            } else {
                file.length()
            }
        } catch (e: Exception) {
            file.length()
        }
    }

    private fun getFolderSize(file: File): Long {
        if (file.isFile) return getActualFileSize(file)
        var size = 0L
        val files = file.listFiles() ?: return 0L
        for (f in files) {
            size += getFolderSize(f)
        }
        return size
    }

    fun checkAndCleanPeriodicCache(ctx: Context) {
        val prefs = ctx.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
        val cleanMode = prefs.getInt("torrent_cache_clean_mode", 0)
        if (cleanMode == 3) {
            val days = prefs.getInt("torrent_cache_clean_days", 0)
            if (days > 0) {
                val lastClean = prefs.getLong("torrent_cache_last_clean_time", 0L)
                val now = System.currentTimeMillis()
                val diffMs = now - lastClean
                val daysInMs = days * 24L * 60L * 60L * 1000L
                if (lastClean == 0L || diffMs >= daysInMs) {
                    clearAllCache()
                    prefs.edit().putLong("torrent_cache_last_clean_time", now).apply()
                }
            }
        }
    }

    fun prepareTorrent(
        infoHash: String,
        fileIdx: Int,
        prebufferPiecesLimit: Int,
        trackers: List<String> = emptyList(),
        onStatusUpdate: (status: String, progress: Float, seeders: Int) -> Unit,
        onReady: () -> Unit,
        onError: (String) -> Unit,
        cancellationToken: CountDownLatch
    ) {
        try {
            forcePlayTriggered = false

            val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
            val cleanMode = prefs.getInt("torrent_cache_clean_mode", 0)
            val cleanHash = infoHash.lowercase().trim()

            if (cleanMode == 1) {
                val isSameTorrent = File(torrentStorageDir, "$cleanHash.torrent").exists() ||
                        File(torrentStorageDir, "$cleanHash.resume").exists()
                if (!isSameTorrent) {
                    clearAllCache()
                }
            } else if (cleanMode == 3) {
                checkAndCleanPeriodicCache(context)
            }

            onStatusUpdate("Locating metadata & swarm peers...", 0f, 0)
            val handle = getOrAddTorrent(infoHash, trackers, cancellationToken)
            if (cancellationToken.count == 0L) return
            if (handle == null || !handle.isValid) {
                onError("Failed to fetch metadata. Check peer connections.")
                return
            }

            var torrentInfo = handle.torrentFile()
            var metaAttempts = 0
            while (torrentInfo == null && metaAttempts < 125) {
                if (cancellationToken.count == 0L) return
                Thread.sleep(150)
                torrentInfo = handle.torrentFile()
                metaAttempts++
            }

            if (torrentInfo == null) {
                onError("Torrent metadata parse error.")
                return
            }

            val numFiles = torrentInfo.files().numFiles()
            if (fileIdx >= numFiles) {
                onError("File index out of bounds.")
                return
            }

            val fileOffset = torrentInfo.files().fileOffset(fileIdx)
            val fileSize = torrentInfo.files().fileSize(fileIdx)
            val pieceLength = torrentInfo.pieceLength()

            val filePriorities = Array(numFiles) { Priority.IGNORE }
            filePriorities[fileIdx] = Priority.FOUR
            handle.prioritizeFiles(filePriorities)

            try {
                handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            } catch (e: Exception) {
                Log.w(TAG, "Failed setting sequential download flag", e)
            }

            var checkWaitCount = 0
            while (handle.isValid && handle.status()?.state() == com.frostwire.jlibtorrent.TorrentStatus.State.CHECKING_FILES && checkWaitCount < 1200) {
                if (cancellationToken.count == 0L || forcePlayTriggered) break
                val progress = handle.status()?.progress() ?: 0f
                onStatusUpdate("Checking existing downloaded files (%.0f%%)...".format(progress * 100f), progress, handle.status()?.numSeeds() ?: 0)
                Thread.sleep(100)
                checkWaitCount++
            }

            val startPiece = (fileOffset / pieceLength).toInt()
            val endPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt().coerceAtLeast(startPiece)

            val minPiecesFor2Mb = (2L * 1024L * 1024L / pieceLength.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            val prebufferCount = maxOf(prebufferPiecesLimit, minPiecesFor2Mb).coerceIn(1, 8)
            val startPiecesToBuffer = (startPiece..minOf(startPiece + (prebufferCount - 1), endPiece)).toList()

            val requiredPieces = (startPiecesToBuffer + if (endPiece > startPiecesToBuffer.last()) listOf(endPiece) else emptyList()).distinct()
            val totalRequiredPieces = requiredPieces.size
            val targetBytes = totalRequiredPieces * pieceLength.toLong()

            if (requiredPieces.all { handle.havePiece(it) }) {
                onReady()
                return
            }

            for ((idx, piece) in startPiecesToBuffer.withIndex()) {
                if (handle.isValid) {
                    handle.piecePriority(piece, Priority.SEVEN)
                    try {
                        handle.setPieceDeadline(piece, idx * 50)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed setting piece deadline for $piece", e)
                    }
                }
            }

            if (endPiece > startPiecesToBuffer.last() && handle.isValid) {
                handle.piecePriority(endPiece, Priority.SEVEN)
                try {
                    handle.setPieceDeadline(endPiece, 0)
                } catch (_: Exception) {}
            }

            val initialPayload = handle.status()?.totalPayloadDownload() ?: 0L

            var isFinished = false
            while (!isFinished) {
                if (cancellationToken.count == 0L) return
                if (forcePlayTriggered) break

                if (!handle.isValid) {
                    onError("Torrent handle invalidated.")
                    return
                }

                val completedRequiredCount = requiredPieces.count { handle.havePiece(it) }
                val allRequiredDone = completedRequiredCount == totalRequiredPieces

                val status = handle.status()
                val numSeeds = status?.numSeeds() ?: 0
                val downloadSpeed = (status?.downloadPayloadRate() ?: 0) / (1024f * 1024f)

                if (allRequiredDone) {
                    isFinished = true
                    onStatusUpdate("Buffering complete! Starting playback...", 1.0f, numSeeds)
                } else {
                    val pieceRatio = if (totalRequiredPieces > 0) completedRequiredCount.toFloat() / totalRequiredPieces.toFloat() else 0f
                    val currentPayload = status?.totalPayloadDownload() ?: initialPayload
                    val downloadedBytes = (currentPayload - initialPayload).coerceAtLeast(0L)
                    val payloadRatio = if (targetBytes > 0) (downloadedBytes.toFloat() / targetBytes.toFloat()).coerceIn(0f, 0.99f) else 0f
                    val byteProgress = maxOf(pieceRatio, payloadRatio).coerceIn(0f, 0.99f)

                    val downloadedMb = (byteProgress * targetBytes) / (1024f * 1024f)
                    val targetMb = targetBytes / (1024f * 1024f)

                    val statusMsg = if (numSeeds > 0 || downloadSpeed > 0f) {
                        "Buffering: %.1f / %.1f MB (%.0f%%, Speed: %.2f MB/s)".format(
                            downloadedMb, targetMb, byteProgress * 100f, downloadSpeed
                        )
                    } else {
                        "Connecting to swarm peers... (0 seeds found yet)"
                    }
                    onStatusUpdate(statusMsg, byteProgress, numSeeds)
                    Thread.sleep(150)
                }
            }

            onReady()
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing torrent", e)
            onError(e.message ?: "Torrent pre-buffering error")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        val rangeHeader = session.headers["range"]

        val parts = uri.split("/").filter { it.isNotEmpty() }
        if (parts.size < 2) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid Path")
        }

        val infoHash = parts[0].lowercase()
        val fileIdx = parts[1].toIntOrNull() ?: 0

        val handle = getOrAddTorrent(infoHash)
        if (handle == null || !handle.isValid) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Torrent handle is invalid")
        }

        var torrentInfo = handle.torrentFile()
        var metaAttempts = 0
        while (torrentInfo == null && metaAttempts < 125) {
            Thread.sleep(150)
            torrentInfo = handle.torrentFile()
            metaAttempts++
        }

        if (torrentInfo == null) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Metadata unavailable")
        }

        val numFiles = torrentInfo.files().numFiles()
        if (fileIdx >= numFiles) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file index")
        }

        val filePath = torrentInfo.files().filePath(fileIdx)
        val fileOffset = torrentInfo.files().fileOffset(fileIdx)
        val fileSize = torrentInfo.files().fileSize(fileIdx)
        val pieceLength = torrentInfo.pieceLength()

        try {
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        } catch (_: Exception) {}

        val targetFile = File(torrentStorageDir, filePath)
        var randomAccessFile: RandomAccessFile? = null
        var fileOpenAttempts = 0
        while (randomAccessFile == null && fileOpenAttempts < 25) {
            try {
                if (targetFile.exists()) {
                    randomAccessFile = RandomAccessFile(targetFile, "r")
                } else {
                    Thread.sleep(100)
                }
            } catch (_: Exception) {
                Thread.sleep(100)
            }
            fileOpenAttempts++
        }

        if (randomAccessFile == null) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "File allocation on-disk failed")
        }

        var rangeStart = 0L
        var rangeEnd = fileSize - 1

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val ranges = rangeHeader.substring(6).split("-")
            try {
                if (ranges[0].isNotEmpty()) rangeStart = ranges[0].toLong()
                if (ranges.size > 1 && ranges[1].isNotEmpty()) rangeEnd = ranges[1].toLong()
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Range parsing error: $rangeHeader", e)
            }
        }

        val contentLength = rangeEnd - rangeStart + 1
        val raf = randomAccessFile

        val inputStream = object : java.io.InputStream() {
            private var remainingBytes = contentLength
            private var currentFilePos = fileOffset + rangeStart
            private val singleBuffer = ByteArray(1)

            override fun read(): Int {
                val r = read(singleBuffer, 0, 1)
                return if (r == 1) singleBuffer[0].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remainingBytes <= 0) return -1
                val maxToRead = minOf(len.toLong(), remainingBytes).toInt()
                return try {
                    if (!waitForBytes(handle, currentFilePos, maxToRead.toLong(), pieceLength)) return -1
                    synchronized(raf) {
                        raf.seek(currentFilePos - fileOffset)
                        val bytesRead = raf.read(b, off, maxToRead)
                        if (bytesRead > 0) {
                            remainingBytes -= bytesRead
                            currentFilePos += bytesRead
                        }
                        bytesRead
                    }
                } catch (e: Exception) {
                    -1
                }
            }

            override fun close() {
                try {
                    synchronized(raf) {
                        raf.close()
                    }
                } catch (_: Exception) {}
            }
        }

        val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val response = newFixedLengthResponse(status, "video/mp4", inputStream, contentLength)
        if (rangeHeader != null) {
            response.addHeader("Content-Range", "bytes $rangeStart-$rangeEnd/$fileSize")
        }
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    private fun getOrAddTorrent(
        infoHash: String,
        customTrackers: List<String> = emptyList(),
        cancellationToken: CountDownLatch? = null
    ): TorrentHandle? {
        val cleanHash = infoHash.lowercase().trim()
        synchronized(torrentLock) {
            activeTorrentHandle?.let {
                val existingHash = it.infoHash()?.toString()?.lowercase()
                if (existingHash == cleanHash && it.isValid) {
                    try {
                        it.resume()
                    } catch (_: Exception) {}
                    return it
                }
                try {
                    saveTorrentMetadataAndResume(it)
                    sessionManager.remove(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed removing previous handle", e)
                }
            }

            val sha1 = Sha1Hash(cleanHash)
            var handle = sessionManager.find(sha1)
            if (handle != null && handle.isValid && handle.torrentFile() != null) {
                try {
                    handle.resume()
                } catch (_: Exception) {}
                activeTorrentHandle = handle
                return handle
            }

            val savedMeta = File(torrentStorageDir, "$cleanHash.torrent")
            val savedResume = File(torrentStorageDir, "$cleanHash.resume")

            if (savedMeta.exists() && savedMeta.length() > 0) {
                try {
                    val tInfo = com.frostwire.jlibtorrent.TorrentInfo(savedMeta)
                    if (savedResume.exists() && savedResume.length() > 0) {
                        sessionManager.download(tInfo, torrentStorageDir, savedResume, null, null, TorrentFlags.AUTO_MANAGED)
                    } else {
                        sessionManager.download(tInfo, torrentStorageDir)
                    }
                    for (attempt in 0..10) {
                        handle = sessionManager.find(sha1)
                        if (handle != null && handle.isValid) break
                        try { Thread.sleep(50) } catch (_: Exception) {}
                    }
                    if (handle != null && handle.isValid) {
                        activeTorrentHandle = handle
                        return handle
                    }
                } catch (_: Throwable) {}
            }

            val allTrackers = (customTrackers + DEFAULT_TRACKERS).distinct()
            val magnetBuilder = StringBuilder("magnet:?xt=urn:btih:").append(cleanHash)
            for (tr in allTrackers) {
                val cleanTr = tr.trim().removePrefix("tracker:")
                if (cleanTr.isNotEmpty()) {
                    magnetBuilder.append("&tr=").append(Uri.encode(cleanTr))
                }
            }
            val magnetUri = magnetBuilder.toString()

            val latch = CountDownLatch(1)
            val listener = object : AlertListener {
                override fun types(): IntArray? = intArrayOf(AlertType.METADATA_RECEIVED.swig())
                override fun alert(alert: Alert<*>?) {
                    if (alert?.type() == AlertType.METADATA_RECEIVED) {
                        latch.countDown()
                    }
                }
            }

            sessionManager.addListener(listener)

            if (handle == null || !handle.isValid) {
                sessionManager.download(magnetUri, torrentStorageDir, TorrentFlags.AUTO_MANAGED)
                for (attempt in 0..10) {
                    handle = sessionManager.find(sha1)
                    if (handle != null && handle.isValid) break
                    try { Thread.sleep(50) } catch (_: Exception) {}
                }
                activeTorrentHandle = handle
            }

            val maxWaitMs = 30000L
            val startWait = System.currentTimeMillis()
            while (latch.count > 0L && (System.currentTimeMillis() - startWait < maxWaitMs)) {
                if (cancellationToken != null && cancellationToken.count == 0L) {
                    sessionManager.removeListener(listener)
                    return null
                }
                if (handle != null && handle.isValid && handle.torrentFile() != null) {
                    break
                }
                latch.await(150, TimeUnit.MILLISECONDS)
            }

            sessionManager.removeListener(listener)
            handle?.let { saveTorrentMetadataAndResume(it) }
            return handle
        }
    }

    private fun waitForBytes(handle: TorrentHandle, absoluteOffset: Long, length: Long, pieceSize: Int): Boolean {
        if (!handle.isValid) return false
        val startPiece = (absoluteOffset / pieceSize).toInt()
        val endPiece = ((absoluteOffset + length - 1) / pieceSize).toInt().coerceAtLeast(startPiece)

        var checkingIterations = 0
        while (handle.isValid && handle.status()?.state() == com.frostwire.jlibtorrent.TorrentStatus.State.CHECKING_FILES && checkingIterations < 1200) {
            Thread.sleep(50)
            checkingIterations++
        }

        if (!handle.isValid) return false

        try {
            for (piece in startPiece..endPiece) {
                if (handle.piecePriority(piece) != Priority.SEVEN) {
                    handle.piecePriority(piece, Priority.SEVEN)
                }
                try {
                    handle.setPieceDeadline(piece, 0)
                } catch (_: Exception) {}
            }

            val totalPieces = handle.torrentFile()?.numPieces() ?: 0
            for (ahead in 1..8) {
                val nextPiece = endPiece + ahead
                if (nextPiece < totalPieces && handle.isValid && !handle.havePiece(nextPiece)) {
                    handle.piecePriority(nextPiece, Priority.SIX)
                }
            }
        } catch (_: Exception) {
            return false
        }

        var iterations = 0
        val maxIterations = 1600
        while (iterations < maxIterations) {
            if (!handle.isValid) return false

            var allReady = true
            for (p in startPiece..endPiece) {
                if (!handle.havePiece(p)) {
                    allReady = false
                    break
                }
            }
            if (allReady) return true

            iterations++
            try {
                Thread.sleep(25)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }
}
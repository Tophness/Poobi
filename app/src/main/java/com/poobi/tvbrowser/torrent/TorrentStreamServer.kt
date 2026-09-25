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

    @Volatile
    var forcePlayTriggered: Boolean = false

    companion object {
        private const val TAG = "TorrentServer"
        
        init {
            try {
                val nanoLogger = Logger.getLogger(NanoHTTPD::class.java.name)
                nanoLogger.filter = Filter { record ->
                    val thrown = record.thrown
                    if (thrown is java.net.SocketException || thrown?.cause is java.net.SocketException) {
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

            val params = SessionParams(sp)
            sessionManager.start(params)
            sessionManager.startDht()
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
        activeTorrentHandle?.let { handle ->
            try {
                if (handle.isValid) {
                    sessionManager.remove(handle)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception removing active handle", e)
            }
        }
        activeTorrentHandle = null
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

    fun clearAllCache() {
        stopActiveStreams()
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
            if (cleanMode == 1) {
                clearAllCache()
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

            val torrentInfo = handle.torrentFile()
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

            val startPiece = (fileOffset / pieceLength).toInt()
            val endPiece = ((fileOffset + fileSize - 1) / pieceLength).toInt().coerceAtLeast(startPiece)

            val minPiecesFor2Mb = (2L * 1024L * 1024L / pieceLength.coerceAtLeast(1)).toInt().coerceAtLeast(1)
            val prebufferCount = maxOf(prebufferPiecesLimit, minPiecesFor2Mb).coerceIn(1, 8)
            val startPiecesToBuffer = (startPiece..minOf(startPiece + (prebufferCount - 1), endPiece)).toList()

            val requiredPieces = (startPiecesToBuffer + if (endPiece > startPiecesToBuffer.last()) listOf(endPiece) else emptyList()).distinct()
            val totalRequiredPieces = requiredPieces.size
            val targetBytes = totalRequiredPieces * pieceLength.toLong()

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
                    val currentPayload = status?.totalPayloadDownload() ?: initialPayload
                    val downloadedBytes = (currentPayload - initialPayload).coerceAtLeast(0L)

                    val displayDownloadedBytes = if (allRequiredDone) targetBytes else minOf(downloadedBytes, (targetBytes * 0.95).toLong())
                    val byteProgress = if (targetBytes > 0) {
                        (displayDownloadedBytes.toFloat() / targetBytes.toFloat()).coerceIn(0f, 0.99f)
                    } else 0f

                    val downloadedMb = displayDownloadedBytes / (1024f * 1024f)
                    val targetMb = targetBytes / (1024f * 1024f)

                    val statusMsg = if (numSeeds > 0 || downloadSpeed > 0f) {
                        "Buffering: %.1f / %.1f MB (%.0f%%, Speed: %.2f MB/s)".format(
                            downloadedMb, targetMb, byteProgress * 100f, downloadSpeed
                        )
                    } else {
                        "Connecting to swarm peers... (0 seeds found yet)"
                    }
                    onStatusUpdate(statusMsg, byteProgress, numSeeds)
                    Thread.sleep(200)
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
        val parts = uri.split("/").filter { it.isNotEmpty() }
        if (parts.size < 2) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid Path")
        }

        val infoHash = parts[0].lowercase()
        val fileIdx = parts[1].toIntOrNull() ?: 0

        try {
            val handle = getOrAddTorrent(infoHash) 
            if (handle == null || !handle.isValid) {
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Torrent handle is invalid"
                )
            }

            val torrentInfo = handle.torrentFile() 
            if (torrentInfo == null) {
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Metadata unavailable"
                )
            }

            val numFiles = torrentInfo.files().numFiles()
            if (fileIdx >= numFiles) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file index")
            }

            val filePath = torrentInfo.files().filePath(fileIdx)
            val fileOffset = torrentInfo.files().fileOffset(fileIdx)
            val fileSize = torrentInfo.files().fileSize(fileIdx)

            val filePriorities = Array(numFiles) { Priority.IGNORE }
            filePriorities[fileIdx] = Priority.FOUR
            handle.prioritizeFiles(filePriorities)
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD, TorrentFlags.SEQUENTIAL_DOWNLOAD)

            val targetFile = File(torrentStorageDir, filePath)

            var randomAccessFile: RandomAccessFile? = null
            var fileOpenAttempts = 0
            while (randomAccessFile == null && fileOpenAttempts < 20) {
                try {
                    if (targetFile.exists()) {
                        randomAccessFile = RandomAccessFile(targetFile, "r")
                    } else {
                        Thread.sleep(100)
                    }
                } catch (e: Exception) {
                    Thread.sleep(100)
                }
                fileOpenAttempts++
            }

            if (randomAccessFile == null) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "File allocation on-disk failed")
            }

            val rangeHeader = session.headers["range"]
            var rangeStart = 0L
            var rangeEnd = fileSize - 1

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val ranges = rangeHeader.substring(6).split("-")
                try {
                    if (ranges[0].isNotEmpty()) rangeStart = ranges[0].toLong()
                    if (ranges.size > 1 && ranges[1].isNotEmpty()) rangeEnd = ranges[1].toLong()
                } catch (e: NumberFormatException) {
                    Log.e(TAG, "Range parsing error from string: $rangeHeader", e)
                }
            }

            val contentLength = rangeEnd - rangeStart + 1
            
            val inputStream = object : java.io.InputStream() {
                private var remainingBytes = contentLength
                private var currentFilePos = fileOffset + rangeStart

                override fun read(): Int {
                    if (remainingBytes <= 0) return -1
                    try {
                        waitForBytes(handle, currentFilePos, 1, torrentInfo.pieceLength())
                        randomAccessFile.seek(currentFilePos - fileOffset)
                        val b = randomAccessFile.read()
                        if (b != -1) {
                            remainingBytes--
                            currentFilePos++
                        }
                        return b
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading stream byte. Pos: $currentFilePos", e)
                        return -1
                    }
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remainingBytes <= 0) return -1
                    val maxToRead = if (len.toLong() < remainingBytes) len else remainingBytes.toInt()
                    try {
                        waitForBytes(handle, currentFilePos, maxToRead.toLong(), torrentInfo.pieceLength())
                        randomAccessFile.seek(currentFilePos - fileOffset)
                        val bytesRead = randomAccessFile.read(b, off, maxToRead)
                        if (bytesRead > 0) {
                            remainingBytes -= bytesRead
                            currentFilePos += bytesRead
                        }
                        return bytesRead
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading stream block. Pos: $currentFilePos, Len: $maxToRead", e)
                        return -1
                    }
                }

                override fun close() {
                    try {
                        randomAccessFile.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Exception during close", e)
                    }
                }
            }

            val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            val response = newFixedLengthResponse(status, "video/mp4", inputStream, contentLength)
            if (rangeHeader != null) {
                response.addHeader("Content-Range", "bytes $rangeStart-$rangeEnd/$fileSize")
            }
            response.addHeader("Accept-Ranges", "bytes")
            return response

        } catch (e: Exception) {
            Log.e(TAG, "Failed to stream torrent", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun getOrAddTorrent(
        infoHash: String,
        customTrackers: List<String> = emptyList(),
        cancellationToken: CountDownLatch? = null
    ): TorrentHandle? {
        val cleanHash = infoHash.lowercase().trim()
        activeTorrentHandle?.let {
            val existingHash = it.infoHash()?.toString()?.lowercase()
            val isValid = it.isValid
            if (existingHash == cleanHash && isValid) {
                return it
            }
            try {
                sessionManager.remove(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed removing previous handle", e)
            }
        }

        val sha1 = Sha1Hash(cleanHash)
        var handle = sessionManager.find(sha1)
        if (handle != null && handle.isValid && handle.torrentFile() != null) {
            activeTorrentHandle = handle
            return handle
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
        } else {
            for (tr in allTrackers) {
                val cleanTr = tr.trim().removePrefix("tracker:")
                if (cleanTr.isNotEmpty()) {
                    try {
                        handle.addTracker(com.frostwire.jlibtorrent.AnnounceEntry(cleanTr))
                    } catch (_: Exception) {}
                }
            }
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
            latch.await(200, TimeUnit.MILLISECONDS)
        }

        sessionManager.removeListener(listener)
        return handle
    }

    private fun waitForBytes(handle: TorrentHandle, absoluteOffset: Long, length: Long, pieceSize: Int) {
        val startPiece = (absoluteOffset / pieceSize).toInt()
        val endPiece = ((absoluteOffset + length - 1) / pieceSize).toInt().coerceAtLeast(startPiece)

        try {
            if (!handle.isValid) return
            for (piece in startPiece..endPiece) {
                if (handle.piecePriority(piece) != Priority.SEVEN) {
                    handle.piecePriority(piece, Priority.SEVEN)
                }
                try {
                    handle.setPieceDeadline(piece, 0)
                } catch (_: Exception) {}
            }

            val totalPieces = handle.torrentFile()?.numPieces() ?: 0
            for (ahead in 1..10) {
                val nextPiece = endPiece + ahead
                if (nextPiece < totalPieces && handle.isValid && !handle.havePiece(nextPiece)) {
                    handle.piecePriority(nextPiece, Priority.SEVEN)
                    try {
                        handle.setPieceDeadline(nextPiece, ahead * 1000)
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception setting piece priorities/deadlines", e)
            return
        }

        for (piece in startPiece..endPiece) {
            var iterations = 0
            while (true) {
                try {
                    if (!handle.isValid) return
                    if (handle.havePiece(piece)) break

                    iterations++
                    if (iterations % 20 == 0) {
                        val status = handle.status()
                        val speed = (status?.downloadPayloadRate() ?: 0) / (1024f * 1024f)
                        Log.d(TAG, "Waiting on piece $piece ($iterations iterations). Speed: %.2f MB/s, Seeds: ${status?.numSeeds() ?: 0}".format(speed))
                        
                        if (handle.isValid) {
                            handle.piecePriority(piece, Priority.SEVEN)
                            try {
                                handle.setPieceDeadline(piece, 0)
                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception inside piece check loop", e)
                    return
                }
                Thread.sleep(50)
            }
        }
    }
}
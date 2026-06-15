package com.poobi.tvbrowser.torrent

import android.content.Context
import android.util.Log
import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        sessionManager.start()
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

            onStatusUpdate("Locating torrent metadata...", 0f, 0)
            val handle = getOrAddTorrent(infoHash)
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

            prioritizeFile(handle, fileIdx, numFiles)
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD, TorrentFlags.SEQUENTIAL_DOWNLOAD)

            val startPiece = (fileOffset / pieceLength).toInt()
            val endPiece = ((fileOffset + fileSize) / pieceLength).toInt()
            val piecesToBuffer = startPiece..minOf(startPiece + (prebufferPiecesLimit - 1), endPiece)
            val totalPiecesToBuffer = piecesToBuffer.count()
            
            for (piece in piecesToBuffer) {
                if (handle.isValid) {
                    handle.piecePriority(piece, Priority.SEVEN)
                }
            }

            var isFinished = false
            while (!isFinished) {
                if (cancellationToken.count == 0L) return
                if (forcePlayTriggered) break

                try {
                    if (!handle.isValid) {
                        onError("Torrent handle invalidated.")
                        return
                    }

                    var completedPieces = 0
                    for (piece in piecesToBuffer) {
                        if (handle.havePiece(piece)) {
                            completedPieces++
                        }
                    }

                    val progress = if (totalPiecesToBuffer > 0) completedPieces.toFloat() / totalPiecesToBuffer.toFloat() else 1f
                    val status = handle.status()
                    val numSeeds = status.numSeeds()
                    val downloadSpeed = status.downloadPayloadRate() / (1024f * 1024f)
                    
                    val statusMsg = "Buffering: %d/%d pieces (%.1f%%, Speed: %.2f MB/s)".format(
                        completedPieces, totalPiecesToBuffer, progress * 100f, downloadSpeed
                    )
                    onStatusUpdate(statusMsg, progress, numSeeds)

                    if (completedPieces >= totalPiecesToBuffer) {
                        isFinished = true
                    } else {
                        Thread.sleep(800)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in buffering loop", e)
                    onError("Buffering error: ${e.message}")
                    return
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

            prioritizeFile(handle, fileIdx, numFiles)
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD, TorrentFlags.SEQUENTIAL_DOWNLOAD)

            val targetFile = File(torrentStorageDir, filePath)

            var randomAccessFile: RandomAccessFile? = null
            var fileOpenAttempts = 0
            while (randomAccessFile == null && fileOpenAttempts < 15) {
                try {
                    if (targetFile.exists()) {
                        randomAccessFile = RandomAccessFile(targetFile, "r")
                    } else {
                        Thread.sleep(150)
                    }
                } catch (e: Exception) {
                    Thread.sleep(150)
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

    private fun getOrAddTorrent(infoHash: String): TorrentHandle? {
        activeTorrentHandle?.let {
            val existingHash = it.infoHash()?.toString()?.lowercase()
            val isValid = it.isValid
            if (existingHash == infoHash) {
                if (isValid) {
                    return it
                }
            }
            try {
                sessionManager.remove(it)
            } catch (e: Exception) {}
        }

        val magnetUri = "magnet:?xt=urn:btih:$infoHash"
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
        sessionManager.download(magnetUri, torrentStorageDir, TorrentFlags.AUTO_MANAGED)
        val handle = sessionManager.find(Sha1Hash(infoHash))
        activeTorrentHandle = handle

        latch.await(30, TimeUnit.SECONDS)
        sessionManager.removeListener(listener)

        return handle
    }

    private fun prioritizeFile(handle: TorrentHandle, targetIdx: Int, numFiles: Int) {
        if (handle.isValid) {
            try {
                val priorities = Array(numFiles) { Priority.IGNORE }
                priorities[targetIdx] = Priority.SEVEN
                handle.prioritizeFiles(priorities)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set priorities on target handle", e)
            }
        }
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
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception setting initial piece priorities", e)
            return
        }

        for (piece in startPiece..endPiece) {
            var iterations = 0
            while (true) {
                try {
                    if (!handle.isValid) return
                    if (handle.havePiece(piece)) break

                    iterations++
                    if (iterations % 50 == 0) {
                        val status = handle.status()
                        val speed = (status?.downloadPayloadRate() ?: 0) / (1024f * 1024f)
                        Log.w(TAG, "Long wait on piece $piece ($iterations iterations). Speed: %.2f MB/s, Progress: ${status?.progress() ?: 0f}".format(speed))
                        
                        if (handle.isValid && handle.piecePriority(piece) != Priority.SEVEN) {
                            handle.piecePriority(piece, Priority.SEVEN)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception inside piece check loop", e)
                    return
                }
                Thread.sleep(100)
            }
        }
    }
}
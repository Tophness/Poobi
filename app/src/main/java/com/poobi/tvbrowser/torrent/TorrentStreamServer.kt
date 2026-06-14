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
            Log.e("TorrentServer", "Error stopping server", e)
        }
    }

    fun stopActiveStreams() {
        activeTorrentHandle?.let { handle ->
            sessionManager.remove(handle)
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
                    Log.i("TorrentServer", "Periodic cache cleaning executed ($days days passed).")
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
            if (handle == null) {
                onError("Failed to fetch metadata. Check peer connections.")
                return
            }

            val torrentInfo = handle.torrentFile()
            if (torrentInfo == null) {
                onError("Torrent metadata parse error.")
                return
            }

            val fileOffset = torrentInfo.files().fileOffset(fileIdx)
            val fileSize = torrentInfo.files().fileSize(fileIdx)
            val pieceLength = torrentInfo.pieceLength()

            prioritizeFile(handle, fileIdx, torrentInfo.files().numFiles())
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

            val startPiece = (fileOffset / pieceLength).toInt()
            val endPiece = ((fileOffset + fileSize) / pieceLength).toInt()

            val piecesToBuffer = startPiece..minOf(startPiece + (prebufferPiecesLimit - 1), endPiece)
            for (piece in piecesToBuffer) {
                handle.piecePriority(piece, Priority.SEVEN)
            }

            val targetBufferBytes = (prebufferPiecesLimit * pieceLength).toLong().coerceAtMost(fileSize)
            var isFinished = false

            while (!isFinished) {
                if (cancellationToken.count == 0L) return
                if (forcePlayTriggered) break

                val progressList = handle.fileProgress()
                val fileDownloadedBytes = if (fileIdx < progressList.size) progressList[fileIdx] else 0L
                val progress = if (targetBufferBytes > 0L) (fileDownloadedBytes.toFloat() / targetBufferBytes.toFloat()).coerceIn(0f, 1f) else 0f
                
                val status = handle.status()
                val numSeeds = status.numSeeds()
                val downloadSpeed = status.downloadPayloadRate() / (1024f * 1024f) // Convert to MB/s
                
                val statusMsg = "Buffering: %.1f%% (Speed: %.2f MB/s)".format(progress * 100f, downloadSpeed)
                onStatusUpdate(statusMsg, progress, numSeeds)

                if (fileDownloadedBytes >= targetBufferBytes) {
                    isFinished = true
                } else {
                    Thread.sleep(800)
                }
            }

            onReady()
        } catch (e: Exception) {
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
            val handle = getOrAddTorrent(infoHash) ?: return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to load torrent metadata"
            )

            val torrentInfo = handle.torrentFile() ?: return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Metadata unavailable"
            )

            if (fileIdx >= torrentInfo.files().numFiles()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file index")
            }

            val filePath = torrentInfo.files().filePath(fileIdx)
            val fileOffset = torrentInfo.files().fileOffset(fileIdx)
            val fileSize = torrentInfo.files().fileSize(fileIdx)

            prioritizeFile(handle, fileIdx, torrentInfo.files().numFiles())
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

            val targetFile = File(torrentStorageDir, filePath)

            val rangeHeader = session.headers["range"]
            var rangeStart = 0L
            var rangeEnd = fileSize - 1

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                val ranges = rangeHeader.substring(6).split("-")
                try {
                    if (ranges[0].isNotEmpty()) rangeStart = ranges[0].toLong()
                    if (ranges.size > 1 && ranges[1].isNotEmpty()) rangeEnd = ranges[1].toLong()
                } catch (e: NumberFormatException) {
                    Log.e("TorrentServer", "Range parsing error", e)
                }
            }

            val contentLength = rangeEnd - rangeStart + 1
            waitForBytes(handle, fileOffset + rangeStart, contentLength, torrentInfo.pieceLength())

            val randomAccessFile = RandomAccessFile(targetFile, "r")
            randomAccessFile.seek(rangeStart)

            val inputStream = object : java.io.InputStream() {
                private var remainingBytes = contentLength

                override fun read(): Int {
                    if (remainingBytes <= 0) return -1
                    remainingBytes--
                    return randomAccessFile.read()
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remainingBytes <= 0) return -1
                    val maxToRead = if (len.toLong() < remainingBytes) len else remainingBytes.toInt()
                    val bytesRead = randomAccessFile.read(b, off, maxToRead)
                    if (bytesRead > 0) {
                        remainingBytes -= bytesRead
                    }
                    return bytesRead
                }

                override fun close() {
                    randomAccessFile.close()
                }
            }

            val response = newChunkedResponse(Response.Status.PARTIAL_CONTENT, "video/mp4", inputStream)
            response.addHeader("Content-Range", "bytes $rangeStart-$rangeEnd/$fileSize")
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Length", "$contentLength")
            return response

        } catch (e: Exception) {
            Log.e("TorrentServer", "Failed to stream torrent", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun getOrAddTorrent(infoHash: String): TorrentHandle? {
        activeTorrentHandle?.let {
            if (it.infoHash().toString().lowercase() == infoHash) {
                return it
            }
            sessionManager.remove(it)
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
        val priorities = Array(numFiles) { Priority.IGNORE }
        priorities[targetIdx] = Priority.SEVEN
        handle.prioritizeFiles(priorities)
    }

    private fun waitForBytes(handle: TorrentHandle, absoluteOffset: Long, length: Long, pieceSize: Int) {
        val startPiece = (absoluteOffset / pieceSize).toInt()
        val endPiece = ((absoluteOffset + length) / pieceSize).toInt()

        for (piece in startPiece..endPiece) {
            while (!handle.havePiece(piece)) {
                handle.piecePriority(piece, Priority.SEVEN)
                Thread.sleep(100)
            }
        }
    }
}
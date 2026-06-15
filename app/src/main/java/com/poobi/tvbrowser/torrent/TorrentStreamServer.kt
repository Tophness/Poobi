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
            Log.d(TAG, "[Companion] getInstance called. Current instance exists: ${instance != null}")
            return instance ?: synchronized(this) {
                instance ?: TorrentStreamServer(context.applicationContext).also { 
                    instance = it 
                    Log.i(TAG, "[Companion] Created new TorrentStreamServer instance.")
                }
            }
        }

        fun stopInstance() {
            instance?.stopServer()
            instance = null
        }
    }

    init {
        Log.i(TAG, "[Init] Starting jLibtorrent SessionManager. Storage directory: ${torrentStorageDir.absolutePath}")
        sessionManager.start()
        Log.i(TAG, "[Init] SessionManager started successfully.")
    }

    fun stopServer() {
        Log.i(TAG, "[stopServer] Thread: ${Thread.currentThread().name} (ID: ${Thread.currentThread().id}) - Stopping server.")
        try {
            stop()
            Log.d(TAG, "[stopServer] NanoHTTPD stopped.")
            stopActiveStreams()
            Log.d(TAG, "[stopServer] Active streams stopped.")
            sessionManager.stop()
            Log.i(TAG, "[stopServer] SessionManager stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "[stopServer] Error stopping server", e)
        }
    }

    fun stopActiveStreams() {
        Log.i(TAG, "[stopActiveStreams] Thread: ${Thread.currentThread().name} (ID: ${Thread.currentThread().id}) - Stopping active streams.")
        activeTorrentHandle?.let { handle ->
            try {
                val hash = handle.infoHash()?.toString()
                val isValid = handle.isValid
                Log.d(TAG, "[stopActiveStreams] Removing active handle. Hash: $hash, IsValid: $isValid")
                if (isValid) {
                    sessionManager.remove(handle)
                }
            } catch (e: Exception) {
                Log.w(TAG, "[stopActiveStreams] Exception while removing active handle from session", e)
            }
        }
        activeTorrentHandle = null
        Log.d(TAG, "[stopActiveStreams] activeTorrentHandle reference set to null.")
    }

    fun getCacheSize(): Long {
        val size = getFolderSize(torrentStorageDir)
        Log.d(TAG, "[getCacheSize] Current cached P2P storage size: $size bytes")
        return size
    }

    fun getCacheItems(): List<TorrentCacheItem> {
        Log.d(TAG, "[getCacheItems] Querying cached directory sub-folders.")
        val list = mutableListOf<TorrentCacheItem>()
        val files = torrentStorageDir.listFiles() ?: return list
        for (file in files) {
            val folderSize = getFolderSize(file)
            list.add(TorrentCacheItem(
                name = file.name,
                size = folderSize,
                path = file.absolutePath
            ))
            Log.v(TAG, "[getCacheItems] Found cache sub-folder: ${file.name} (Size: $folderSize bytes)")
        }
        return list
    }

    fun deleteCacheItem(path: String) {
        Log.i(TAG, "[deleteCacheItem] Request to delete: $path")
        val file = File(path)
        if (file.exists()) {
            val result = file.deleteRecursively()
            Log.d(TAG, "[deleteCacheItem] Recursively deleted: $path, Success: $result")
        } else {
            Log.w(TAG, "[deleteCacheItem] Path does not exist: $path")
        }
    }

    fun clearAllCache() {
        Log.i(TAG, "[clearAllCache] Clearing all cached P2P files.")
        stopActiveStreams()
        if (torrentStorageDir.exists()) {
            val deleted = torrentStorageDir.deleteRecursively()
            Log.d(TAG, "[clearAllCache] Delete cache storage: $deleted")
            val recreated = torrentStorageDir.mkdirs()
            Log.d(TAG, "[clearAllCache] Recreate cache directory: $recreated")
        }
    }

    private fun getActualFileSize(file: File): Long {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                val stat = android.system.Os.lstat(file.absolutePath)
                val blocksSize = stat.st_blocks * 512L
                Log.v(TAG, "[getActualFileSize] File ${file.name} actual block allocation size: $blocksSize")
                blocksSize
            } else {
                file.length()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[getActualFileSize] Fallback to file.length() for ${file.name}", e)
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
        Log.d(TAG, "[checkAndCleanPeriodicCache] Evaluated cleanMode: $cleanMode")
        if (cleanMode == 3) {
            val days = prefs.getInt("torrent_cache_clean_days", 0)
            if (days > 0) {
                val lastClean = prefs.getLong("torrent_cache_last_clean_time", 0L)
                val now = System.currentTimeMillis()
                val diffMs = now - lastClean
                val daysInMs = days * 24L * 60L * 60L * 1000L
                Log.d(TAG, "[checkAndCleanPeriodicCache] Time since last clean: $diffMs ms. Interval threshold: $daysInMs ms")
                if (lastClean == 0L || diffMs >= daysInMs) {
                    Log.i(TAG, "[checkAndCleanPeriodicCache] Executing periodic cache deletion ($days days elapsed).")
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
        Log.i(TAG, "[prepareTorrent] Thread: ${Thread.currentThread().name} - Starting preparation for Hash: $infoHash, FileIndex: $fileIdx, Limit: $prebufferPiecesLimit")
        try {
            forcePlayTriggered = false

            val prefs = context.getSharedPreferences("BrowserSettings", Context.MODE_PRIVATE)
            val cleanMode = prefs.getInt("torrent_cache_clean_mode", 0)
            Log.d(TAG, "[prepareTorrent] Cache clean mode configuration evaluated: $cleanMode")
            if (cleanMode == 1) {
                Log.d(TAG, "[prepareTorrent] Clean mode 1. Clearing cache before starting.")
                clearAllCache()
            } else if (cleanMode == 3) {
                checkAndCleanPeriodicCache(context)
            }

            onStatusUpdate("Locating torrent metadata...", 0f, 0)
            Log.d(TAG, "[prepareTorrent] Requesting torrent handle for Hash: $infoHash")
            val handle = getOrAddTorrent(infoHash)
            if (handle == null) {
                Log.e(TAG, "[prepareTorrent] Failed to retrieve torrent handle for Hash: $infoHash")
                onError("Failed to fetch metadata. Check peer connections.")
                return
            }

            val handleValidBeforeInfo = handle.isValid
            Log.d(TAG, "[prepareTorrent] Torrent handle obtained. IsValid: $handleValidBeforeInfo")
            if (!handleValidBeforeInfo) {
                onError("Retrieved torrent handle is invalid.")
                return
            }

            val torrentInfo = handle.torrentFile()
            if (torrentInfo == null) {
                Log.e(TAG, "[prepareTorrent] Torrent info metadata parsing failed. Handle IsValid: ${handle.isValid}")
                onError("Torrent metadata parse error.")
                return
            }

            val numFiles = torrentInfo.files().numFiles()
            Log.d(TAG, "[prepareTorrent] Metadata retrieved. Total files in torrent: $numFiles")
            if (fileIdx >= numFiles) {
                Log.e(TAG, "[prepareTorrent] Request index $fileIdx is out of bounds (numFiles: $numFiles)")
                onError("File index out of bounds.")
                return
            }

            val fileOffset = torrentInfo.files().fileOffset(fileIdx)
            val fileSize = torrentInfo.files().fileSize(fileIdx)
            val pieceLength = torrentInfo.pieceLength()
            Log.d(TAG, "[prepareTorrent] File properties: Offset: $fileOffset, Size: $fileSize, PieceLength: $pieceLength")

            Log.d(TAG, "[prepareTorrent] Prioritizing file index: $fileIdx")
            prioritizeFile(handle, fileIdx, numFiles)
            
            // Set sequential download bitmask non-destructively
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            Log.d(TAG, "[prepareTorrent] SEQUENTIAL_DOWNLOAD flag bitmask applied.")

            val startPiece = (fileOffset / pieceLength).toInt()
            val endPiece = ((fileOffset + fileSize) / pieceLength).toInt()
            val piecesToBuffer = startPiece..minOf(startPiece + (prebufferPiecesLimit - 1), endPiece)
            val totalPiecesToBuffer = piecesToBuffer.count()
            
            Log.d(TAG, "[prepareTorrent] Prebuffering piece range: $piecesToBuffer")
            for (piece in piecesToBuffer) {
                if (handle.isValid) {
                    handle.piecePriority(piece, Priority.SEVEN)
                } else {
                    Log.w(TAG, "[prepareTorrent] Handle became invalid while setting piece priority for piece: $piece")
                }
            }

            var isFinished = false
            Log.d(TAG, "[prepareTorrent] Total pieces to verify: $totalPiecesToBuffer")

            while (!isFinished) {
                if (cancellationToken.count == 0L) {
                    Log.i(TAG, "[prepareTorrent] Buffering cancelled.")
                    return
                }
                if (forcePlayTriggered) {
                    Log.i(TAG, "[prepareTorrent] Buffering bypassed.")
                    break
                }

                try {
                    val currentHandleValid = handle.isValid
                    if (!currentHandleValid) {
                        Log.e(TAG, "[prepareTorrent] Handle became invalid during buffering loop!")
                        onError("Torrent handle invalidated during buffering.")
                        return
                    }

                    // Check actual hash-verified piece completion states rather than disk storage allocations
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
                    Log.d(TAG, "[prepareTorrent] Loop status: $statusMsg, Seeds: $numSeeds")
                    onStatusUpdate(statusMsg, progress, numSeeds)

                    if (completedPieces >= totalPiecesToBuffer) {
                        isFinished = true
                        Log.i(TAG, "[prepareTorrent] All target prebuffer pieces successfully verified.")
                    } else {
                        Thread.sleep(800)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[prepareTorrent] Error in buffering loop", e)
                    onError("Buffering error: ${e.message}")
                    return
                }
            }

            Log.i(TAG, "[prepareTorrent] Torrent pre-buffering block completed. Invoking onReady().")
            onReady()
        } catch (e: Exception) {
            Log.e(TAG, "[prepareTorrent] Crash occurred in prepareTorrent thread", e)
            onError(e.message ?: "Torrent pre-buffering error")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        val method = session.method
        val headers = session.headers
        Log.i(TAG, "[serve] Thread: ${Thread.currentThread().name} (ID: ${Thread.currentThread().id}) - Incoming HTTP request: $method $uri")
        Log.v(TAG, "[serve] Headers: $headers")

        val parts = uri.split("/").filter { it.isNotEmpty() }
        if (parts.size < 2) {
            Log.w(TAG, "[serve] BAD REQUEST: Path contains insufficient parameters: $uri")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid Path")
        }

        val infoHash = parts[0].lowercase()
        val fileIdx = parts[1].toIntOrNull() ?: 0
        Log.d(TAG, "[serve] Resolved request: Hash: $infoHash, FileIndex: $fileIdx")

        try {
            val handle = getOrAddTorrent(infoHash) 
            if (handle == null) {
                Log.e(TAG, "[serve] getOrAddTorrent returned null for hash: $infoHash")
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to load torrent metadata"
                )
            }

            val isHandleValid = handle.isValid
            Log.d(TAG, "[serve] Retrieved handle validity state: $isHandleValid")
            if (!isHandleValid) {
                Log.e(TAG, "[serve] Handle for hash $infoHash is invalid.")
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Torrent handle is invalid"
                )
            }

            val torrentInfo = handle.torrentFile() 
            if (torrentInfo == null) {
                Log.e(TAG, "[serve] Metadata unavailable for hash: $infoHash. Handle IsValid: ${handle.isValid}")
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Metadata unavailable"
                )
            }

            val numFiles = torrentInfo.files().numFiles()
            if (fileIdx >= numFiles) {
                Log.e(TAG, "[serve] BAD REQUEST: Requested file index $fileIdx is >= numFiles ($numFiles)")
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid file index")
            }

            val filePath = torrentInfo.files().filePath(fileIdx)
            val fileOffset = torrentInfo.files().fileOffset(fileIdx)
            val fileSize = torrentInfo.files().fileSize(fileIdx)
            Log.d(TAG, "[serve] Streaming Target: Path: $filePath, Offset: $fileOffset, Size: $fileSize")

            Log.d(TAG, "[serve] Prioritizing file index: $fileIdx")
            prioritizeFile(handle, fileIdx, numFiles)
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD, TorrentFlags.SEQUENTIAL_DOWNLOAD)

            val targetFile = File(torrentStorageDir, filePath)
            Log.d(TAG, "[serve] Target output file location: ${targetFile.absolutePath}")

            // Retry loop when opening RandomAccessFile to prevent early playback exceptions before the file is allocated
            var randomAccessFile: RandomAccessFile? = null
            var fileOpenAttempts = 0
            while (randomAccessFile == null && fileOpenAttempts < 15) {
                try {
                    if (targetFile.exists()) {
                        randomAccessFile = RandomAccessFile(targetFile, "r")
                        Log.d(TAG, "[serve] Target output file successfully opened in read mode on attempt: $fileOpenAttempts")
                    } else {
                        Thread.sleep(150)
                    }
                } catch (e: Exception) {
                    Thread.sleep(150)
                }
                fileOpenAttempts++
            }

            if (randomAccessFile == null) {
                Log.e(TAG, "[serve] File allocation timeout! target file could not be created or opened.")
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
                    Log.d(TAG, "[serve] Parsed custom byte Range Header: Start: $rangeStart, End: $rangeEnd")
                } catch (e: NumberFormatException) {
                    Log.e(TAG, "[serve] Range parsing error from string: $rangeHeader", e)
                }
            }

            val contentLength = rangeEnd - rangeStart + 1
            
            // Refactored streaming wrapper: Serves immediate response headers to ExoPlayer,
            // and performs sequential piece checks dynamically on-demand inside read loops.
            val inputStream = object : java.io.InputStream() {
                private var remainingBytes = contentLength
                private var currentFilePos = fileOffset + rangeStart

                override fun read(): Int {
                    if (remainingBytes <= 0) return -1
                    try {
                        waitForBytes(handle, currentFilePos, 1, torrentInfo.pieceLength())
                        
                        // Explicit frame-precise seek prior to physical file read to prevent file pointer desynchronization
                        randomAccessFile.seek(currentFilePos - fileOffset)
                        
                        var b = randomAccessFile.read()
                        if (b == -1) {
                            Thread.sleep(100)
                            randomAccessFile.seek(currentFilePos - fileOffset)
                            b = randomAccessFile.read()
                            if (b == -1) {
                                Log.w(TAG, "[InputStream.read] Physical EOF reached on disk for single byte. Returning 0.")
                                b = 0
                            }
                        }
                        if (b != -1) {
                            remainingBytes--
                            currentFilePos++
                        }
                        return b
                    } catch (e: Exception) {
                        Log.e(TAG, "[InputStream.read] Error reading stream byte. Pos: $currentFilePos", e)
                        return -1
                    }
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remainingBytes <= 0) return -1
                    val maxToRead = if (len.toLong() < remainingBytes) len else remainingBytes.toInt()
                    try {
                        waitForBytes(handle, currentFilePos, maxToRead.toLong(), torrentInfo.pieceLength())
                        
                        // Explicit frame-precise seek prior to physical file read to prevent file pointer desynchronization
                        randomAccessFile.seek(currentFilePos - fileOffset)
                        
                        var bytesRead = randomAccessFile.read(b, off, maxToRead)
                        if (bytesRead == -1) {
                            Thread.sleep(100)
                            randomAccessFile.seek(currentFilePos - fileOffset)
                            bytesRead = randomAccessFile.read(b, off, maxToRead)
                            if (bytesRead == -1) {
                                Log.w(TAG, "[InputStream.read] Physical EOF reached on disk, but remainingBytes is $remainingBytes. Returning zeros.")
                                java.util.Arrays.fill(b, off, off + maxToRead, 0.toByte())
                                bytesRead = maxToRead
                            }
                        }
                        if (bytesRead > 0) {
                            remainingBytes -= bytesRead
                            currentFilePos += bytesRead
                        }
                        return bytesRead
                    } catch (e: Exception) {
                        Log.e(TAG, "[InputStream.read] Error reading stream block. Pos: $currentFilePos, Len: $maxToRead", e)
                        return -1
                    }
                }

                override fun close() {
                    Log.d(TAG, "[InputStream] Closing RandomAccessFile stream. Remaining bytes inside track: $remainingBytes")
                    try {
                        randomAccessFile.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "[InputStream.close] Exception during close", e)
                    }
                }
            }

            // Fixed-Length response matching the standard HTTP protocol specifications
            val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
            Log.i(TAG, "[serve] Returning standard HTTP $status response. Content-Length: $contentLength")
            
            val response = newFixedLengthResponse(status, "video/mp4", inputStream, contentLength)
            if (rangeHeader != null) {
                response.addHeader("Content-Range", "bytes $rangeStart-$rangeEnd/$fileSize")
            }
            response.addHeader("Accept-Ranges", "bytes")
            return response

        } catch (e: Exception) {
            Log.e(TAG, "[serve] Failed to stream torrent (Exception captured)", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun getOrAddTorrent(infoHash: String): TorrentHandle? {
        Log.d(TAG, "[getOrAddTorrent] Thread: ${Thread.currentThread().name} - Requested Hash: $infoHash")
        activeTorrentHandle?.let {
            val existingHash = it.infoHash()?.toString()?.lowercase()
            val isValid = it.isValid
            Log.d(TAG, "[getOrAddTorrent] Currently activeTorrentHandle found. Hash: $existingHash, IsValid: $isValid")
            if (existingHash == infoHash) {
                if (isValid) {
                    Log.d(TAG, "[getOrAddTorrent] Reusing active handle matches target Hash.")
                    return it
                } else {
                    Log.w(TAG, "[getOrAddTorrent] Active handle matches Hash, but is invalid. Removing invalid handle.")
                }
            }
            try {
                Log.d(TAG, "[getOrAddTorrent] Removing old mismatching/invalid active handle from session manager.")
                sessionManager.remove(it)
            } catch (e: Exception) {}
        }

        val magnetUri = "magnet:?xt=urn:btih:$infoHash"
        val latch = CountDownLatch(1)
        Log.i(TAG, "[getOrAddTorrent] Starting metadata retrieval latch. Magnet URI: $magnetUri")

        val listener = object : AlertListener {
            override fun types(): IntArray? = intArrayOf(AlertType.METADATA_RECEIVED.swig())
            override fun alert(alert: Alert<*>?) {
                Log.d(TAG, "[getOrAddTorrent-Listener] Alert received: ${alert?.type()}")
                if (alert?.type() == AlertType.METADATA_RECEIVED) {
                    Log.i(TAG, "[getOrAddTorrent-Listener] AlertType.METADATA_RECEIVED detected. Counting down latch.")
                    latch.countDown()
                }
            }
        }

        sessionManager.addListener(listener)
        sessionManager.download(magnetUri, torrentStorageDir, TorrentFlags.AUTO_MANAGED)
        val handle = sessionManager.find(Sha1Hash(infoHash))
        activeTorrentHandle = handle

        Log.d(TAG, "[getOrAddTorrent] Download requested, handle looked up. IsValid: ${handle?.isValid}. Waiting on Metadata latch...")
        val successMetadata = latch.await(30, TimeUnit.SECONDS)
        sessionManager.removeListener(listener)

        val handleValidPostLatch = handle?.isValid ?: false
        Log.i(TAG, "[getOrAddTorrent] Latch complete. Success: $successMetadata. Handle IsValid: $handleValidPostLatch")

        return handle
    }

    private fun prioritizeFile(handle: TorrentHandle, targetIdx: Int, numFiles: Int) {
        val handleValid = handle.isValid
        Log.d(TAG, "[prioritizeFile] Prioritizing Index $targetIdx inside handle. IsValid: $handleValid, numFiles: $numFiles")
        if (handleValid) {
            try {
                val priorities = Array(numFiles) { Priority.IGNORE }
                priorities[targetIdx] = Priority.SEVEN
                handle.prioritizeFiles(priorities)
                Log.v(TAG, "[prioritizeFile] Priorities array dispatched to handle.")
            } catch (e: Exception) {
                Log.w(TAG, "[prioritizeFile] Failed to set priorities on target handle", e)
            }
        } else {
            Log.e(TAG, "[prioritizeFile] Error: Called prioritizeFile on an INVALID torrent handle!")
        }
    }

    private fun waitForBytes(handle: TorrentHandle, absoluteOffset: Long, length: Long, pieceSize: Int) {
        val startPiece = (absoluteOffset / pieceSize).toInt()
        val endPiece = ((absoluteOffset + length - 1) / pieceSize).toInt().coerceAtLeast(startPiece)
        Log.d(TAG, "[waitForBytes] Offset: $absoluteOffset, Length: $length. Required range: startPiece=$startPiece, endPiece=$endPiece")

        // Set high priority for the target pieces ONCE to allow sequential picking,
        // avoiding repeating piecePriority calls in a loop which triggers continuous picker re-sorting.
        try {
            if (!handle.isValid) {
                Log.w(TAG, "[waitForBytes] Handle is invalid prior to setting piece priorities.")
                return
            }
            for (piece in startPiece..endPiece) {
                if (handle.piecePriority(piece) != Priority.SEVEN) {
                    handle.piecePriority(piece, Priority.SEVEN)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[waitForBytes] Exception while updating initial piece priority map values", e)
            return
        }

        for (piece in startPiece..endPiece) {
            var iterations = 0
            while (true) {
                try {
                    if (!handle.isValid) {
                        Log.w(TAG, "[waitForBytes] Handle became invalid inside sequential piece check loop.")
                        return
                    }

                    if (handle.havePiece(piece)) {
                        Log.v(TAG, "[waitForBytes] Piece $piece has completed downloading.")
                        break
                    }

                    iterations++
                    if (iterations % 50 == 0) {
                        val status = handle.status()
                        val speed = (status?.downloadPayloadRate() ?: 0) / (1024f * 1024f)
                        Log.w(TAG, "[waitForBytes] Long wait on piece $piece ($iterations iterations). Speed: %.2f MB/s, Progress: ${status?.progress() ?: 0f}".format(speed))
                        
                        // Fallback safety check: Re-verify priority if a piece is stalled
                        if (handle.isValid && handle.piecePriority(piece) != Priority.SEVEN) {
                            handle.piecePriority(piece, Priority.SEVEN)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[waitForBytes] Exception captured inside piece check loop. Thread exiting loop cleanly.", e)
                    return
                }
                Thread.sleep(100)
            }
        }
        Log.d(TAG, "[waitForBytes] Block wait succeeded for piece range $startPiece..$endPiece.")
    }
}
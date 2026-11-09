package fr.bonobo.synczen.data

import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal const val TRANSFER_BUFFER_SIZE = 64 * 1024

/** Also attempts close when cancellation occurs inside a blocking provider/library read. */
internal suspend fun <T : Closeable, R> T.useCancellable(block: suspend (T) -> R): R = coroutineScope {
    val finished = AtomicBoolean(false)
    val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally {
            if (!finished.get()) try { this@useCancellable.close() } catch (_: Exception) { }
        }
    }
    try {
        this@useCancellable.use {
            currentCoroutineContext().ensureActive()
            block(it)
        }
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        finished.set(true)
        withContext(NonCancellable) { watcher.cancelAndJoin() }
    }
}

/** Stream ownership belongs to the caller; use blocks close both sides on every exit. */
internal suspend fun copyBounded(input: InputStream, output: OutputStream): Long {
    val context = currentCoroutineContext()
    val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
    var total = 0L
    while (true) {
        context.ensureActive()
        val count = input.read(buffer)
        context.ensureActive()
        if (count < 0) return total
        if (count > 0) { output.write(buffer, 0, count); total += count }
    }
}

/** A disk snapshot, never a byte array proportional to file size. Never replay a PUT. */
internal class StreamingFileBody(private val file: File, private val context: CoroutineContext) : RequestBody() {
    private val length = file.length()
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = length
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
        var total = 0L
        file.inputStream().use { input ->
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                context.ensureActive()
                if (count < 0) break
                if (count > 0) { sink.write(buffer, 0, count); total += count }
            }
        }
        if (total != length) throw java.io.IOException("Instantané local modifié pendant le transfert")
    }
}

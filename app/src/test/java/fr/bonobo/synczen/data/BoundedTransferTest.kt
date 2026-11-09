package fr.bonobo.synczen.data

import kotlinx.coroutines.*
import okio.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.coroutines.EmptyCoroutineContext

class BoundedTransferTest {
    @Test fun artificial128MiBFileStreamsWithBoundedWritesAndMatchingSha256() = runBlocking {
        val file = File.createTempFile("synczen-large-fiction-", ".bin")
        try {
            val block = ByteArray(TRANSFER_BUFFER_SIZE) { (it * 31).toByte() }
            file.outputStream().use { output -> repeat(2048) { output.write(block) } }
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var maximum = 0L
            val sink = object : Sink {
                override fun timeout() = Timeout.NONE
                override fun flush() {}
                override fun close() {}
                override fun write(source: Buffer, byteCount: Long) {
                    maximum = maxOf(maximum, byteCount)
                    assertTrue(byteCount <= TRANSFER_BUFFER_SIZE)
                    val bytes = source.readByteArray(byteCount)
                    hash.update(bytes); total += byteCount
                }
            }.buffer()
            val body = StreamingFileBody(file, currentCoroutineContext())
            assertTrue(body.isOneShot()); assertEquals(128L * 1024 * 1024, body.contentLength())
            body.writeTo(sink); sink.flush()
            val fingerprint = file.inputStream().use { FileFingerprint.read(it) }
            assertEquals(fingerprint.size, total)
            assertEquals(fingerprint.sha256, hash.digest().joinToString("") { "%02x".format(it) })
            assertTrue(maximum > 0)
        } finally { file.delete() }
    }
    @Test fun sourceReadFailureClosesBothStreamsAndDoesNotReadAhead() = runBlocking {
        var inputClosed = false; var outputClosed = false; var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Must use bounded bulk read")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                assertEquals(TRANSFER_BUFFER_SIZE, len)
                if (++reads == 2) throw IOException("synthetic read failure")
                b.fill(7); return len
            }
            override fun close() { inputClosed = true }
        }
        val output = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) { assertEquals(TRANSFER_BUFFER_SIZE, len) }
            override fun close() { outputClosed = true }
        }
        try { input.use { output.use { copyBounded(input, output) } }; fail("Must fail") }
        catch (_: IOException) {}
        assertTrue(inputClosed); assertTrue(outputClosed); assertEquals(2, reads)
    }
    @Test fun cancellationBetweenBlocksClosesBothStreams() = runBlocking {
        val job = Job(); var reads = 0; var closed = 0
        val input = object : InputStream() {
            override fun read(): Int = error("bulk only")
            override fun read(b: ByteArray, off: Int, len: Int): Int { reads++; return len }
            override fun close() { closed++ }
        }
        val output = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) { job.cancel() }
            override fun close() { closed++ }
        }
        try { withContext(job) { input.use { output.use { copyBounded(input, output) } } }; fail("Must cancel") }
        catch (_: CancellationException) {}
        assertEquals(1, reads); assertEquals(2, closed)
    }
    @Test fun cancellationClosesBlockedSourceReadWithoutPublishingAnyBytes() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        var writes = 0
        val input = object : InputStream() {
            override fun read(): Int = error("bulk only")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                entered.countDown()
                if (!closed.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw IOException("timeout")
                return -1
            }
            override fun close() { closed.countDown() }
        }
        val output = object : OutputStream() { override fun write(b: Int) { writes++ } }
        val job = launch(Dispatchers.IO) { input.useCancellable { copyBounded(it, output) } }
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
        withTimeout(3000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled); assertEquals(0L, closed.count); assertEquals(0, writes)
    }
    @Test fun cancelledRequestBodyAndMissingSnapshotNeverWrite() {
        val file = File.createTempFile("synczen-fiction-body-", ".bin")
        try {
            file.writeText("fake")
            val job = Job().also { it.cancel() }; val sink = Buffer()
            assertThrows(CancellationException::class.java) { StreamingFileBody(file, job).writeTo(sink) }
            assertEquals(0L, sink.size)
            val body = StreamingFileBody(file, EmptyCoroutineContext)
            file.delete()
            assertThrows(IOException::class.java) { body.writeTo(sink) }
            assertEquals(0L, sink.size)
        } finally { file.delete() }
    }
    @Test fun changedSnapshotAndSinkFailureAreReportedAndSourceCanBeRemoved() {
        val file = File.createTempFile("synczen-fiction-body-", ".bin")
        try {
            file.writeText("fake")
            val body = StreamingFileBody(file, EmptyCoroutineContext)
            file.appendText("changed")
            assertThrows(IOException::class.java) { body.writeTo(Buffer()) }
            file.outputStream().use { it.write(ByteArray(TRANSFER_BUFFER_SIZE * 2)) }
            val failing = object : Sink {
                override fun timeout() = Timeout.NONE
                override fun flush() {}
                override fun close() {}
                override fun write(source: Buffer, byteCount: Long) { throw IOException("synthetic sink failure") }
            }.buffer()
            assertThrows(IOException::class.java) { StreamingFileBody(file, EmptyCoroutineContext).writeTo(failing) }
            assertTrue(file.delete())
        } finally { file.delete() }
    }
    @Test fun orphanCleanupOnlyDeletesOwnedRegularFilesAndPreservesForeignFilesAndSymlinks() {
        val root = Files.createTempDirectory("synczen-fiction-cache-").toFile()
        try {
            val staging = LocalStaging(root)
            val owned = staging.create(null).also { it.writeText("interrupted fake snapshot") }
            val directory = owned.parentFile!!
            val foreign = File(directory, "user.txt").also { it.writeText("preserved") }
            val old = File(root, "synczen-stage-old.part").also { it.writeText("preserved old cache") }
            val target = File(root, "user-original.txt").also { it.writeText("preserved target") }
            val link = File(directory, "stage-00000000-0000-0000-0000-000000000000.part")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            val nested = File(directory, "stage-11111111-1111-1111-1111-111111111111.part").also { it.mkdir() }
            staging.removeOrphans()
            assertFalse(owned.exists()); assertTrue(foreign.exists()); assertTrue(old.exists())
            assertTrue(Files.isSymbolicLink(link.toPath())); assertEquals("preserved target", target.readText())
            assertTrue(nested.isDirectory)
            assertThrows(SyncConflict::class.java) { staging.create(Long.MAX_VALUE) }
        } finally { root.deleteRecursively() }
    }
    @Test fun symbolicLinkStagingRootIsRefusedWithoutDeletingItsTarget() {
        val root = Files.createTempDirectory("synczen-fiction-cache-").toFile()
        try {
            val target = File(root, "foreign").also { it.mkdir() }
            val user = File(target, "user.txt").also { it.writeText("preserved") }
            Files.createSymbolicLink(File(root, "synczen-staging-v1").toPath(), target.toPath())
            assertThrows(SyncConflict::class.java) { LocalStaging(root) }
            assertEquals("preserved", user.readText())
        } finally { root.deleteRecursively() }
    }
}

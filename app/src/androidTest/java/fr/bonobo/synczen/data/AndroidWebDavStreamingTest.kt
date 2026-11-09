package fr.bonobo.synczen.data

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class AndroidWebDavStreamingTest {
    @org.junit.Before fun emulatorOnly() { check(android.os.Build.HARDWARE == "ranchu") { "Synthetic emulator only" } }
    private suspend fun fixture(block: suspend (File, MockWebServer, WebDavSafeTransport, RemotePath) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = WebDavSecurity.client(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .callTimeout(30, TimeUnit.SECONDS).build())
        val file = File.createTempFile("synczen-stream-fiction-", ".bin")
        try {
            val blockBytes = ByteArray(TRANSFER_BUFFER_SIZE) { (it * 13).toByte() }
            file.outputStream().use { out -> repeat(2048) { out.write(blockBytes) } }
            MockWebServer().use { server ->
                // Count the entire request without retaining its payload in the fake server heap.
                server.bodyLimit = 0
                // MockWebServer 4.12 only honors bodyLimit in its HTTP/1.1 reader.
                server.protocols = listOf(okhttp3.Protocol.HTTP_1_1)
                server.useHttps(tls.sslSocketFactory(), false)
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                val remote = WebDavSafeTransport(server.url("/dav/").newBuilder().host("127.0.0.1").build().toString(), client, "Basic ZmFrZTpmYWtl")
                block(file, server, remote, RemotePath.source("content://fiction/source", listOf("large.bin")).temporary())
            }
        } finally {
            file.delete(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun artificial128MiBUploadsOverLocalHttpsWithoutServerBodyBuffering() = runBlocking {
        fixture { file, server, remote, temporary ->
            server.enqueue(MockResponse().setResponseCode(201))
            withContext(Dispatchers.IO) { remote.uploadExclusive(temporary, file) }
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertEquals("PUT", request.method)
            assertEquals(file.length(), request.bodySize); assertEquals(0L, request.body.size)
            assertEquals(file.length().toString(), request.getHeader("Content-Length"))
            assertEquals("*", request.getHeader("If-None-Match"))
            assertTrue(request.path!!.contains(".synczen-upload-"))
        }
    }
    @Test fun disconnectDuringUploadFailsWithoutAutomaticReplayAndNextAttemptUsesNewTemporary() = runBlocking {
        fixture { file, server, remote, temporary ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY))
            val first = runCatching { withContext(Dispatchers.IO) { remote.uploadExclusive(temporary, file) } }
            assertTrue(first.isFailure)
            val interrupted = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertTrue(interrupted.bodySize < file.length()); assertEquals(1, server.requestCount)
            val second = RemotePath.source("content://fiction/source", listOf("large.bin")).temporary()
            server.enqueue(MockResponse().setResponseCode(201))
            withContext(Dispatchers.IO) { remote.uploadExclusive(second, file) }
            val retried = server.takeRequest(3, TimeUnit.SECONDS)!!
            assertNotEquals(interrupted.path, retried.path); assertEquals(file.length(), retried.bodySize)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun cancellationWhileServerDoesNotReadUploadClosesCallPromptly() = runBlocking {
        fixture { file, server, remote, temporary ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DO_NOT_READ_REQUEST_BODY).setHeadersDelay(3, TimeUnit.SECONDS))
            val job = launch(Dispatchers.IO) { remote.uploadExclusive(temporary, file) }
            withTimeout(5000) { while (server.requestCount == 0) delay(10) }
            delay(100) // Fill the loopback socket buffers; the 128 MiB payload cannot complete.
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(job.isCancelled); assertEquals(1, server.requestCount)
        }
    }
    @Test fun cancellationWhileWaitingForHeadersClosesCallPromptly() = runBlocking {
        fixture { _, server, remote, temporary ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { remote.fingerprint(temporary) }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(job.isCancelled); assertEquals(1, server.requestCount)
        }
    }
    @Test fun cancellationDuringRemoteSha256ReadClosesResponseAndCall() = runBlocking {
        fixture { _, server, remote, temporary ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(ByteArray(1024 * 1024)))
                .throttleBody(1024, 1, TimeUnit.SECONDS))
            val job = launch(Dispatchers.IO) { remote.fingerprint(temporary) }
            assertEquals("HEAD", withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!.method)
            assertEquals("GET", withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }!!.method)
            delay(100)
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(job.isCancelled); assertEquals(2, server.requestCount)
        }
    }
    @Test fun serverErrorDoesNotRetryOrPublish() = runBlocking {
        fixture { file, server, remote, temporary ->
            server.enqueue(MockResponse().setResponseCode(507))
            val result = runCatching { withContext(Dispatchers.IO) { remote.uploadExclusive(temporary, file) } }
            assertTrue(result.exceptionOrNull() is SyncConflict)
            assertEquals("PUT", server.takeRequest(3, TimeUnit.SECONDS)!!.method)
            assertEquals(1, server.requestCount)
        }
    }
}

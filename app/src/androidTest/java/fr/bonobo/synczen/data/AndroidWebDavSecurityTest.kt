package fr.bonobo.synczen.data

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** Loopback-only servers, synthetic credentials, no application or worker launched. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class AndroidWebDavSecurityTest {
    @org.junit.Before fun emulatorOnly() { check(android.os.Build.HARDWARE == "ranchu") { "Synthetic emulator only" } }
    private val certificate = HeldCertificate.Builder()
        .commonName("Fictitious WebDAV")
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val trustedClient = OkHttpClient.Builder()
        .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
        .callTimeout(3, TimeUnit.SECONDS).build()

    private fun server(tls: Boolean = false): MockWebServer = MockWebServer().apply {
        if (tls) useHttps(serverTls.sslSocketFactory(), false)
        start(InetAddress.getByName("127.0.0.1"), 0)
    }

    private fun config(address: String) = NasConfig(serverType = ServerType.WEBDAV,
        address = address, username = "fictitious-user", password = "fictitious-password")

    private fun repo(address: String, client: OkHttpClient = trustedClient) =
        NasRepository(config(address), webDavClientFactory = { client })

    private fun failure(result: NasResult): String {
        assertTrue("Expected failure, got $result", result is NasResult.Failure)
        return (result as NasResult.Failure).message
    }

    @Test fun httpConnectionIsBlockedBeforeAnyRequestOrClientCreation() = runBlocking {
        server().use { target ->
            val original = config(target.url("/dav/").toString())
            var clientCreated = false
            val repository = NasRepository(original, webDavClientFactory = {
                clientCreated = true; trustedClient
            })
            assertTrue(failure(repository.testConnection()).contains("HTTPS obligatoire"))
            assertFalse(clientCreated)
            assertEquals(0, target.requestCount)
            assertEquals(original, config(target.url("/dav/").toString()))
        }
    }

    @Test fun httpUploadIsBlockedWithoutReadingContentAndClosesInput() = runBlocking {
        server().use { target ->
            var read = false
            var closed = false
            val input = object : InputStream() {
                override fun read(): Int { read = true; error("Must not read") }
                override fun close() { closed = true }
            }
            assertTrue(failure(repo(target.url("/dav/").toString()).uploadFile("fake.txt", input))
                .contains("HTTPS obligatoire"))
            assertFalse(read); assertTrue(closed)
            assertEquals(0, target.requestCount)
        }
    }

    @Test fun trustedHttpsPropfindAndPutUseBasicAtInitialDestination() = runBlocking {
        server(tls = true).use { target ->
            target.enqueue(MockResponse().setResponseCode(207))
            target.enqueue(MockResponse().setResponseCode(201))
            val repository = repo(target.url("/dav/").toString())
            assertTrue(repository.testConnection() is NasResult.Success)
            val path = RemotePath.source("content://fictitious", listOf("fake.txt")).temporary()
            val file = File.createTempFile("webdav-security-test-", ".txt")
            try {
                file.writeText("synthetic content")
                repository.safeTransport().uploadExclusive(path, file)
            } finally { file.delete() }
            val find = target.takeRequest(1, TimeUnit.SECONDS)!!
            val put = target.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("PROPFIND", find.method); assertEquals("0", find.getHeader("Depth"))
            assertEquals("PUT", put.method); assertEquals("/dav/${path.value}", put.path)
            assertEquals("synthetic content", put.body.readUtf8())
            for (request in listOf(find, put)) {
                assertEquals(Credentials.basic("fictitious-user", "fictitious-password"), request.getHeader("Authorization"))
            }
        }
    }

    @Test fun redirectsAreNeverFollowedForEitherOperation() = runBlocking {
        server(tls = true).use { initial ->
            server(tls = true).use { otherHttps ->
                server().use { otherHttp ->
                    for (code in listOf(301, 302, 303, 307, 308)) {
                        for (destination in listOf(initial.url("/other/"), otherHttps.url("/dav/"), otherHttp.url("/dav/"))) {
                            for (upload in listOf(false, true)) {
                                val before = initial.requestCount
                                initial.enqueue(MockResponse().setResponseCode(code).addHeader("Location", destination))
                                val repository = repo(initial.url("/dav/").toString())
                                val path = RemotePath.source("content://fictitious", listOf("fake.txt")).temporary()
                                val result = if (upload) {
                                    val file = File.createTempFile("webdav-redirect-test-", ".txt")
                                    try {
                                        file.writeBytes(byteArrayOf(1))
                                        repository.safeTransport().uploadExclusive(path, file)
                                        NasResult.Success("unexpected")
                                    } catch (e: SyncConflict) { NasResult.Failure(e.message.orEmpty()) }
                                    finally { file.delete() }
                                } else repository.testConnection()
                                assertTrue(failure(result).contains("Redirection WebDAV refusée"))
                                assertEquals(before + 1, initial.requestCount)
                                val request = initial.takeRequest(1, TimeUnit.SECONDS)!!
                                assertEquals(if(upload) "/dav/${path.value}" else "/dav/", request.path)
                                assertEquals(0, otherHttps.requestCount)
                                assertEquals(0, otherHttp.requestCount)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun untrustedCertificateFailsWithDefaultTlsValidation() = runBlocking {
        server(tls = true).use { target ->
            val normalClient = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
            val message = failure(repo(target.url("/dav/").toString(), normalClient).testConnection())
            assertTrue(message.contains("TLS refusée"))
            assertEquals(0, target.requestCount) // TLS fails before the HTTP request/authentication.
        }
    }

    @Test fun hostnameMismatchFailsEvenWhenTestCertificateIsTrusted() = runBlocking {
        val wrongName = HeldCertificate.Builder().addSubjectAlternativeName("different.invalid").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(wrongName).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(wrongName.certificate).build()
        MockWebServer().use { target ->
            target.useHttps(tls.sslSocketFactory(), false)
            target.start(InetAddress.getByName("127.0.0.1"), 0)
            val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .callTimeout(3, TimeUnit.SECONDS).build()
            assertTrue(failure(repo(target.url("/dav/").toString(), client).testConnection()).contains("TLS refusée"))
            assertEquals(0, target.requestCount)
        }
    }

    @Test fun invalidAddressesAndCredentialsInUrlAreRejected() {
        for (address in listOf("", "not-an-url", "smb://fake/share", "ftp://fake/", "https://fake-user:fake-pass@localhost/dav/")) {
            assertThrows(WebDavSecurityException::class.java) { WebDavSecurity.requireHttpsUrl(address) }
        }
        assertEquals("https", WebDavSecurity.requireHttpsUrl("HTTPS://localhost/dav/").scheme)
    }

    @Test fun productionClientKeepsDefaultTlsAndDisablesBothRedirectOptions() {
        val normal = OkHttpClient()
        val secured = WebDavSecurity.client(normal)
        assertFalse(secured.followRedirects); assertFalse(secured.followSslRedirects)
        assertSame(normal.hostnameVerifier, secured.hostnameVerifier)
        assertSame(normal.sslSocketFactory, secured.sslSocketFactory)
        assertSame(normal.x509TrustManager, secured.x509TrustManager)
    }
}

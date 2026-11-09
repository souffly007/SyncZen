package fr.bonobo.synczen.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
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
class AndroidWebDavSafePublicationTest {
    @org.junit.Before fun emulatorOnly() { check(android.os.Build.HARDWARE == "ranchu") { "Synthetic emulator only" } }
    private class Journal : PublicationJournal {
        val records=mutableMapOf<String,SyncFileRecord>()
        override suspend fun get(destination: String,pathKey: String)=records[pathKey]
        override suspend fun put(record: SyncFileRecord) { records[record.pathKey]=record }
    }
    private class Dav : Dispatcher() {
        val files=mutableMapOf<String,ByteArray>()
        val directories=mutableSetOf("/dav/")
        var ignoreExclusive=false; var ignoreOverwrite=false; var missingEtag=false; var noMove=false
        var truncate=false; var race=false; var loseReply=false
        var finalMoves=0; var finalPuts=0
        val requests=mutableListOf<RecordedRequest>()
        private fun etag(bytes: ByteArray) = "\"${RemotePath.sha256(bytes.decodeToString())}\""
        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
            requests.add(request)
            val path=request.requestUrl!!.encodedPath
            fun code(c: Int)=MockResponse().setResponseCode(c)
            return when(request.method) {
                "MKCOL" -> if(path in directories || path.trimEnd('/') in files) code(405)
                    else if(path.trimEnd('/').substringBeforeLast('/')+"/" !in directories) code(409)
                    else { directories.add(path); code(201) }
                "PROPFIND" -> if(path !in directories) code(404) else code(207).setBody(
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")
                "HEAD", "GET" -> files[path]?.let { bytes ->
                    code(200).setHeader("Content-Length",bytes.size).apply {
                        if(!missingEtag) setHeader("ETag",etag(bytes))
                        if(request.method=="GET") setBody(okio.Buffer().write(bytes))
                    }
                } ?: code(404)
                "PUT" -> {
                    if(path.substringBeforeLast('/')+"/" !in directories) code(409)
                    else if(path in files && request.getHeader("If-None-Match")=="*" && !ignoreExclusive) code(412)
                    else {
                        val bytes=request.body.readByteArray()
                        if("/source-" in path) finalPuts++
                        files[path]=if(truncate && ".synczen-upload-" in path) bytes.take(bytes.size/2).toByteArray() else bytes
                        code(201)
                    }
                }
                "MOVE" -> {
                    val target=request.getHeader("Destination")!!.toHttpUrl().encodedPath
                    if(noMove) code(501)
                    else if(files[path]==null) code(404)
                    else if(request.getHeader("If-Match")!=etag(files[path]!!)) code(412)
                    else {
                        if(race && "/source-" in target) files[target]="other writer".toByteArray()
                        if(target in files && request.getHeader("Overwrite")=="F" && !ignoreOverwrite) code(412)
                        else {
                            files[target]=files.remove(path)!!
                            if("/source-" in target) finalMoves++
                            if(loseReply && "/source-" in target) code(201).setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                            else code(201)
                        }
                    }
                }
                else -> code(405)
            }
        }
    }
    private suspend fun fixture(block: suspend (File,Dav,MockWebServer,WebDavSafeTransport,Journal,RemotePath,SafeFilePublisher)->Unit) {
        val cert=HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("localhost").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust=HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val client=WebDavSecurity.client(OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(),trust.trustManager)
            .retryOnConnectionFailure(false).callTimeout(3,TimeUnit.SECONDS).build())
        val file=File.createTempFile("synczen-fictitious-dav-", ".txt")
        try { MockWebServer().use { server ->
            val dav=Dav(); server.dispatcher=dav
            server.useHttps(serverTls.sslSocketFactory(),false)
            server.start(InetAddress.getByName("127.0.0.1"),0)
            val remote=WebDavSafeTransport(server.url("/dav/").newBuilder().host("127.0.0.1").build().toString(),client,"Basic ZmFrZTpmYWtl")
            val journal=Journal();val path=RemotePath.source("content://fake/a",listOf("nested folder","same.txt"))
            file.writeText("synthetic local content")
            block(file,dav,server,remote,journal,path,SafeFilePublisher("fictitious-destination",remote,journal))
        } } finally { file.delete() }
    }
    @Test fun hierarchicalExclusiveTransferPublishesOnceAndLeavesLegacyUntouched() = runBlocking {
        fixture { file,dav,_,remote,_,path,publisher ->
            dav.files["/dav/same.txt"]="legacy".toByteArray()
            val outcome = publisher.publish(path,file)
            assertTrue("$outcome; requests=${dav.requests.map { it.method + " " + it.path }}", outcome is NasResult.Success)
            assertTrue(publisher.publish(path,file) is NasResult.Skipped)
            assertEquals(1,dav.finalMoves); assertEquals(1,dav.finalPuts)
            val final=remote.url(path.segments).encodedPath
            assertTrue(final.contains("nested%20folder"))
            assertArrayEquals(file.readBytes(),dav.files[final]); assertEquals("legacy",dav.files["/dav/same.txt"]!!.decodeToString())
            assertFalse(dav.requests.any { it.method=="DELETE" })
            for (put in dav.requests.filter { it.method=="PUT" }) assertEquals("*",put.getHeader("If-None-Match"))
            for (move in dav.requests.filter { it.method=="MOVE" }) {
                assertEquals("F",move.getHeader("Overwrite")); assertNotNull(move.getHeader("If-Match"))
                assertEquals("127.0.0.1",move.getHeader("Destination")!!.toHttpUrl().host)
            }
        }
    }
    @Test fun existingFinalAndRaceAtMoveCannotOverwrite() = runBlocking {
        fixture { file,dav,_,remote,_,path,publisher ->
            val final=remote.url(path.segments).encodedPath
            dav.files[final]="external".toByteArray()
            assertTrue(publisher.publish(path,file) is NasResult.Failure)
            assertEquals("external",dav.files[final]!!.decodeToString()); assertEquals(0,dav.finalPuts)
        }
        fixture { file,dav,_,remote,_,path,publisher ->
            dav.race=true
            assertTrue(publisher.publish(path,file) is NasResult.Failure)
            assertEquals("other writer",dav.files[remote.url(path.segments).encodedPath]!!.decodeToString())
            assertEquals(0,dav.finalMoves)
        }
    }
    @Test fun nonCompliantOrUnsupportedServersAreBlockedBySyntheticProbe() = runBlocking {
        for (mode in 0..3) fixture { file,dav,_,_,_,path,publisher ->
            when(mode) { 0 -> dav.ignoreExclusive=true; 1 -> dav.ignoreOverwrite=true; 2 -> dav.missingEtag=true; 3 -> dav.noMove=true }
            val result=publisher.publish(path,file)
            assertTrue("Mode $mode: $result",result is NasResult.Failure)
            assertEquals(0,dav.finalPuts); assertEquals(0,dav.finalMoves)
        }
    }
    @Test fun truncatedPutIsNotPublishedAndNextPassUsesANewTemporary() = runBlocking {
        fixture { file,dav,_,_,_,path,publisher ->
            dav.truncate=true
            assertTrue(publisher.publish(path,file) is NasResult.Failure); assertEquals(0,dav.finalMoves)
            dav.truncate=false
            val outcome = publisher.publish(path,file)
            assertTrue("$outcome; requests=${dav.requests.map { it.method + " " + it.path }}", outcome is NasResult.Success); assertEquals(1,dav.finalMoves)
            val names=dav.requests.filter { it.method=="PUT" && ".synczen-upload-" in it.path.orEmpty() }.map { it.path }
            assertEquals(2,names.size); assertEquals(2,names.toSet().size)
        }
    }
    @Test fun lostMoveResponseRecoversFromPendingJournalWithoutAnotherPut() = runBlocking {
        fixture { file,dav,_,remote,journal,path,publisher ->
            dav.loseReply=true
            assertTrue(publisher.publish(path,file) is NasResult.Failure)
            assertFalse(journal.records.values.single().published)
            dav.loseReply=false
            val restarted=SafeFilePublisher("fictitious-destination",remote,journal)
            assertTrue(restarted.publish(path,file) is NasResult.Skipped)
            assertEquals(1,dav.finalPuts);assertEquals(1,dav.finalMoves)
        }
    }
    @Test fun unsafeDestinationRootsAreRejectedWithoutAnyRequest() = runBlocking {
        fixture { _,dav,server,_,_,_,_ ->
            val client=WebDavSecurity.client()
            for (suffix in listOf("/dav/../outside/", "/dav/%2e%2e/outside/", "/dav/%252e%252e/", "/dav/%2fescape/", "/dav/?token=fake", "/dav/#fragment")) {
                val base="https://127.0.0.1:${server.port}$suffix"
                assertThrows(IllegalArgumentException::class.java) { WebDavSafeTransport(base,client,"Basic fake") }
            }
            assertEquals(0,dav.requests.size)
        }
    }
}

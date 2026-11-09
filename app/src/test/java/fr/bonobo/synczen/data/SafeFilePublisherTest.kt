package fr.bonobo.synczen.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SafeFilePublisherTest {
    private class Journal : PublicationJournal {
        val records = mutableMapOf<String,SyncFileRecord>()
        var failCommit=false
        override suspend fun get(destination: String,pathKey: String)=records["$destination|$pathKey"]
        override suspend fun put(record: SyncFileRecord) {
            if (record.published && failCommit) throw IOException("synthetic commit failure")
            records["${record.destinationId}|${record.pathKey}"]=record
        }
    }
    private class Remote : SafeRemoteTransport {
        val files = mutableMapOf<String,ByteArray>()
        var uploads=0; var moves=0; var supported=true; var truncated=false; var interrupt=false
        var lostReply=false; var race=false; var cancel=false
        override suspend fun verifyPublicationSupport() { if(!supported) throw SyncConflict("Unsupported safe publication") }
        override suspend fun fingerprint(path: RemotePath): FileFingerprint? = files[path.value]?.inputStream()?.use { FileFingerprint.read(it) }
        override suspend fun ensureParents(path: RemotePath) {}
        override suspend fun uploadExclusive(path: RemotePath,file: File) {
            uploads++
            check(!files.containsKey(path.value))
            val bytes=file.readBytes()
            files[path.value]=if(truncated || interrupt) bytes.take(bytes.size/2).toByteArray() else bytes
            if(interrupt) throw IOException("Synthetic transfer interrupted")
            if(cancel) throw CancellationException("Synthetic cancellation")
        }
        override suspend fun publishNoReplace(temporary: RemotePath,final: RemotePath) {
            moves++
            if(race) files[final.value]="other writer".toByteArray()
            if(files.containsKey(final.value)) throw SyncConflict("Conflict without replacement")
            files[final.value]=files.remove(temporary.value)!!
            if(lostReply) throw IOException("Reply lost after move")
        }
        override suspend fun publishWithOverwrite(temporary: RemotePath, final: RemotePath) {
            files[final.value] = files.remove(temporary.value)!!
        }
        override suspend fun delete(path: RemotePath) {
            files.remove(path.value)
        }
    }
    private suspend fun fixture(block: suspend (File, Remote, Journal, RemotePath, SafeFilePublisher)->Unit) {
        val file=File.createTempFile("synczen-fiction-", ".txt")
        try {
            file.writeText("synthetic source content")
            val remote=Remote(); val journal=Journal(); val path=RemotePath.source("content://fake/a",listOf("nested","same.txt"))
            block(file,remote,journal,path,SafeFilePublisher("fictional-destination",remote,journal))
        } finally { file.delete() }
    }
    @Test fun successfulPublicationPreservesLegacyAndSkipsRepeatedPasses() = runBlocking {
        fixture { file,remote,journal,path,publisher ->
            remote.files["same.txt"]="legacy untouched".toByteArray()
            assertTrue(publisher.publish(path,file) is NasResult.Success)
            assertArrayEquals(file.readBytes(),remote.files[path.value])
            assertEquals("legacy untouched",remote.files["same.txt"]!!.decodeToString())
            assertTrue(publisher.publish(path,file) is NasResult.Skipped)
            assertEquals(1,remote.uploads); assertEquals(1,remote.moves)
            assertTrue(journal.records.values.single().published)
            val other=RemotePath.source("content://fake/b",listOf("nested","same.txt"))
            assertTrue(publisher.publish(other,file) is NasResult.Success)
            assertEquals(2,remote.files.keys.count { it.endsWith("/same.txt") })
        }
    }
    @Test fun externalExistingFileAndChangedLocalContentAreNeverOverwritten() = runBlocking {
        fixture { file,remote,_,path,publisher ->
            remote.files[path.value]="external content".toByteArray()
            assertTrue(publisher.publish(path,file) is NasResult.Failure)
            assertEquals(0,remote.uploads); assertEquals("external content",remote.files[path.value]!!.decodeToString())
        }
        fixture { file,remote,_,path,publisher ->
            assertTrue(publisher.publish(path,file) is NasResult.Success)
            val old=remote.files[path.value]!!.copyOf()
            file.writeText("changed local content")
            assertTrue(publisher.publish(path,file) is NasResult.Failure)
            assertEquals(1,remote.uploads); assertArrayEquals(old,remote.files[path.value])
        }
    }
    @Test fun interruptedOrTruncatedTransfersNeverPublishAndCanBeRetried() = runBlocking {
        for (interrupt in listOf(true,false)) fixture { file,remote,_,path,publisher ->
            remote.interrupt=interrupt; remote.truncated=!interrupt
            assertTrue(publisher.publish(path,file) is NasResult.Failure)
            assertFalse(remote.files.containsKey(path.value)); assertEquals(0,remote.moves)
            assertTrue(remote.files.keys.single().endsWith(".part"))
            remote.interrupt=false; remote.truncated=false
            assertTrue(publisher.publish(path,file) is NasResult.Success)
            assertEquals(2,remote.uploads); assertEquals(1,remote.moves)
        }
    }
    @Test fun racesAtPublicationPreserveOtherWriter() = runBlocking {
        fixture { file,remote,_,path,publisher ->
            remote.race=true
            assertTrue(publisher.publish(path,file) is NasResult.Failure)
            assertEquals("other writer",remote.files[path.value]!!.decodeToString())
            assertTrue(remote.files.keys.any { it.endsWith(".part") })
        }
    }
    @Test fun uncertainRepliesAndDatabaseCommitFailuresRecoverWithoutDuplicateUpload() = runBlocking {
        for (lost in listOf(true,false)) fixture { file,remote,journal,path,publisher ->
            remote.lostReply=lost; journal.failCommit=!lost
            assertTrue(publisher.publish(path,file) is NasResult.Failure)
            assertFalse(journal.records.values.single().published)
            remote.lostReply=false; journal.failCommit=false
            val restarted=SafeFilePublisher("fictional-destination",remote,journal)
            assertTrue(restarted.publish(path,file) is NasResult.Skipped)
            assertEquals(1,remote.uploads); assertEquals(1,remote.moves)
            assertTrue(journal.records.values.single().published)
        }
    }
    @Test fun unsupportedPublicationSizeMismatchAndPersistentCaseConflictFailClosed() = runBlocking {
        fixture { file,remote,_,path,publisher ->
            remote.supported=false
            assertTrue(publisher.publish(path,file) is NasResult.Failure); assertEquals(0,remote.uploads)
            remote.supported=true
            assertTrue(publisher.publish(path,file,file.length()+1) is NasResult.Failure); assertEquals(0,remote.uploads)
            assertTrue(publisher.publish(path,file) is NasResult.Success)
            val caseAlias=RemotePath.source("content://fake/a",listOf("nested","SAME.txt"))
            assertTrue(publisher.publish(caseAlias,file) is NasResult.Failure); assertEquals(1,remote.uploads)
        }
    }
    @Test fun cancellationLeavesOnlyAnUnpublishedTemporaryAndPropagates() = runBlocking {
        fixture { file,remote,_,path,publisher ->
            remote.cancel=true
            try { publisher.publish(path,file); fail("Must cancel") } catch (_: CancellationException) { }
            assertFalse(remote.files.containsKey(path.value)); assertEquals(0,remote.moves)
        }
    }
    @Test fun operatingSystemLockExcludesConcurrentManualAndPeriodicPassesAndReleases() {
        val lockFile=File.createTempFile("synczen-fiction-lock-", ".lock")
        val held=CountDownLatch(1); val release=CountDownLatch(1)
        val outcomes=ConcurrentHashMap<String,Boolean>()
        val manual=Thread {
            SyncExecutionGate.acquire(lockFile)!!.use {
                outcomes["manual"]=true; held.countDown(); release.await(5,TimeUnit.SECONDS)
            }
        }
        try {
            manual.start(); assertTrue(held.await(5,TimeUnit.SECONDS))
            val periodic=Thread { outcomes["periodic"] = SyncExecutionGate.acquire(lockFile)?.use { true } ?: false }
            periodic.start(); periodic.join(5000)
            assertEquals(false,outcomes["periodic"])
            release.countDown(); manual.join(5000)
            assertNotNull(SyncExecutionGate.acquire(lockFile)?.use { true })
            try { SyncExecutionGate.acquire(lockFile)!!.use { throw CancellationException("fake") } }
            catch (_: CancellationException) { }
            assertNotNull(SyncExecutionGate.acquire(lockFile)?.use { true })
        } finally { release.countDown(); manual.join(5000); lockFile.delete() }
    }
}

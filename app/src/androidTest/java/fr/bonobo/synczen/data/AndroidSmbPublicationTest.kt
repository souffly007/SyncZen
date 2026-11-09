package fr.bonobo.synczen.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.SmbFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Properties
import java.util.UUID

/** Existing host smbd exposes only an isolated fixture through adb reverse tcp:16445. */
@RunWith(AndroidJUnit4::class)
class AndroidSmbPublicationTest {
    @org.junit.Before fun emulatorOnly() { check(android.os.Build.HARDWARE == "ranchu") { "Synthetic emulator only" } }
    @Test fun realAndroidSmbPublishesLargeFileAndRoomJournalAvoidsDuplicateAfterReopen() = runBlocking {
        check(android.os.Build.HARDWARE == "ranchu")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val properties=Properties().apply {
            setProperty("jcifs.smb.client.minVersion","SMB202")
            setProperty("jcifs.smb.client.maxVersion","SMB311")
            setProperty("jcifs.smb.client.connTimeout","3000")
            setProperty("jcifs.smb.client.responseTimeout","5000")
            setProperty("jcifs.smb.client.resolveOrder","DNS")
        }
        val smb=BaseContext(PropertyConfiguration(properties)).withAnonymousCredentials()
        val base="smb://127.0.0.1:16445/fiction/"
        val remote=SmbSafeTransport(base,smb)
        val name="synthetic-android-smb-${UUID.randomUUID()}.db"
        fun database()=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        val file=File.createTempFile("synthetic-smb-", ".bin",context.cacheDir)
        var db=database()
        try {
            val block=ByteArray(TRANSFER_BUFFER_SIZE) { (it * 17).toByte() }
            file.outputStream().use { out -> repeat(256) { out.write(block) } }
            val path=RemotePath.source("content://fake/smb/${UUID.randomUUID()}",listOf("nested","large.bin"))
            var uploads=0
            val counted=object : SafeRemoteTransport by remote {
                override suspend fun uploadExclusive(path: RemotePath,file: File) { uploads++; remote.uploadExclusive(path,file) }
            }
            val first=SafeFilePublisher("fiction-smb",counted,RoomPublicationJournal(db.syncFileDao())).publish(path,file)
            assertTrue("$first",first is NasResult.Success)
            assertEquals(file.inputStream().use { FileFingerprint.read(it) },remote.fingerprint(path))
            db.close();db=database()
            assertTrue(SafeFilePublisher("fiction-smb",counted,RoomPublicationJournal(db.syncFileDao())).publish(path,file) is NasResult.Skipped)
            assertEquals(1,uploads)
            val collision=path.temporary()
            remote.uploadExclusive(collision,file)
            assertThrows(jcifs.smb.SmbException::class.java) { runBlocking { remote.uploadExclusive(collision,file) } }
            try { remote.publishNoReplace(collision,path); fail("Must not overwrite") } catch (_: SyncConflict) { }
            assertEquals(file.inputStream().use { FileFingerprint.read(it) },remote.fingerprint(path))
            assertNotNull(remote.fingerprint(collision))
        } finally { db.close();context.deleteDatabase(name);file.delete();smb.close() }
    }
}

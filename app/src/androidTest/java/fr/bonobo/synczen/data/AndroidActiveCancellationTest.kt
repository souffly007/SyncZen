package fr.bonobo.synczen.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import fr.bonobo.synczen.worker.SyncWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidActiveCancellationTest {
    @Test fun cancellingRealSyncWorkerDuringSafSnapshotReleasesLockAndNewPassPublishesCompletely() = runBlocking {
        check(android.os.Build.HARDWARE=="ranchu")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=AppDatabase.getInstance(context);val repository=NasConfigRepository(db)
        val original=repository.getConfig()!!
        val permission=context.contentResolver.persistedUriPermissions.single { it.uri.toString().contains("SyncZenFiction") }
        val fixture=original.copy(serverType=ServerType.SMB,address="smb://127.0.0.1:16445/fiction/",username="fiction-worker",
            password="fiction-worker",sourceFolderUris=permission.uri.toString(),wifiSsid=null,requireCharging=false)
        val manager=WorkManager.getInstance(context)
        fun waitState(id: java.util.UUID,state: WorkInfo.State) {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45)
            while(System.nanoTime()<deadline) {
                val actual=manager.getWorkInfoById(id).get(5,TimeUnit.SECONDS)?.state
                if(actual==state) return
                if(actual?.isFinished==true) fail("Unexpected $actual, wanted $state")
                Thread.sleep(10)
            }
            fail("Timed out waiting for $state")
        }
        var active: java.util.UUID?=null
        try {
            repository.upsert(fixture)
            val first=OneTimeWorkRequestBuilder<SyncWorker>().addTag("synthetic-recette").build();active=first.id
            manager.enqueue(first).result.get(5,TimeUnit.SECONDS)
            waitState(first.id,WorkInfo.State.RUNNING)
            val cache=File(context.cacheDir,"synczen-staging-v1")
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            var copying=false
            while(System.nanoTime()<until && !copying) {
                copying=cache.listFiles()?.any { it.length()>TRANSFER_BUFFER_SIZE && it.length()<128L*1024*1024 }==true
                if(!copying) Thread.sleep(2)
            }
            assertTrue("Real SAF snapshot must be in progress before cancellation",copying)
            manager.cancelWorkById(first.id).result.get(5,TimeUnit.SECONDS)
            waitState(first.id,WorkInfo.State.CANCELLED)
            val released=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            var unlocked=false
            while(System.nanoTime()<released && !unlocked) {
                unlocked=SyncExecutionGate.acquire(File(context.filesDir,"synczen-sync.lock"))?.use { true }?:false
                if(!unlocked) Thread.sleep(10)
            }
            assertTrue("Cancellation must release shared lock",unlocked)
            val second=OneTimeWorkRequestBuilder<SyncWorker>().addTag("synthetic-recette").build();active=second.id
            manager.enqueue(second).result.get(5,TimeUnit.SECONDS)
            waitState(second.id,WorkInfo.State.SUCCEEDED)
            val path=RemotePath.source(permission.uri.toString(),listOf("active-cancellation.bin"))
            val destination=RemotePath.sha256("SMB|${fixture.address}")
            val record=db.syncFileDao().get(destination,path.collisionKey)!!
            assertTrue(record.published);assertEquals(128L*1024*1024,record.size)
            assertTrue(cache.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally {
            active?.let { manager.cancelWorkById(it).result.get(5,TimeUnit.SECONDS) }
            repository.upsert(original)
        }
    }
}

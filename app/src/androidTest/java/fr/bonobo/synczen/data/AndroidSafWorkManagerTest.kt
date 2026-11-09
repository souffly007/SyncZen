package fr.bonobo.synczen.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import fr.bonobo.synczen.worker.SyncWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidSafWorkManagerTest {
    @org.junit.Before fun emulatorOnly() { check(android.os.Build.HARDWARE == "ranchu") { "Synthetic emulator only" } }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun selectedSyntheticSafTreeAndPersistedReadPermissionSurviveProcessRestart() = runBlocking {
        val permission = context.contentResolver.persistedUriPermissions.single { it.uri.toString().contains("SyncZenFiction") }
        assertTrue(permission.isReadPermission)
        val tree = DocumentFile.fromTreeUri(context, permission.uri)!!
        assertEquals("SyncZenFiction", tree.name)
        val file = tree.findFile("source.txt")!!
        assertEquals("only fictitious SAF content", context.contentResolver.openInputStream(file.uri)!!.bufferedReader().use { it.readText().trim() })
        assertTrue(NasConfigRepository(AppDatabase.getInstance(context)).getConfig()!!.sourceFolderUriList().contains(permission.uri.toString()))
    }
    private fun awaitState(id: java.util.UUID, terminal: WorkInfo.State): WorkInfo {
        val manager=WorkManager.getInstance(context)
        val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        while(System.nanoTime()<until) {
            val info=manager.getWorkInfoById(id).get(5,TimeUnit.SECONDS)
            if(info?.state==terminal) return info
            if(info?.state?.isFinished==true) fail("Unexpected work state ${info.state}")
            Thread.sleep(50)
        }
        error("WorkManager timed out waiting for $terminal")
    }
    @Test fun actualSyncWorkerIsExcludedBySharedLockForTwoConcurrentTriggers() {
        val manager=WorkManager.getInstance(context)
        SyncExecutionGate.acquire(File(context.filesDir,"synczen-sync.lock"))!!.use {
            val first=OneTimeWorkRequestBuilder<SyncWorker>().addTag("synthetic-recette").build()
            val second=OneTimeWorkRequestBuilder<SyncWorker>().addTag("synthetic-recette").build()
            manager.enqueue(listOf(first,second)).result.get(5,TimeUnit.SECONDS)
            awaitState(first.id,WorkInfo.State.SUCCEEDED); awaitState(second.id,WorkInfo.State.SUCCEEDED)
        }
        val db=AppDatabase.getInstance(context)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_log WHERE message LIKE 'Synchronisation déjà en cours%'").use {
            assertTrue(it.moveToFirst()); assertTrue(it.getInt(0)>=2)
        }
    }
    @Test fun actualSyncWorkerPublishesSyntheticSafFileToSmbAndNextPassUsesRoomJournal() = runBlocking {
        val db=AppDatabase.getInstance(context)
        val repository=NasConfigRepository(db)
        val original=repository.getConfig()!!
        val permission=context.contentResolver.persistedUriPermissions.single { it.uri.toString().contains("SyncZenFiction") }
        val fixture=original.copy(serverType=ServerType.SMB,address="smb://127.0.0.1:16445/fiction/",
            username="fiction-worker",password="fiction-worker",sourceFolderUris=permission.uri.toString(),wifiSsid=null,requireCharging=false)
        val manager=WorkManager.getInstance(context)
        val expectedCount=DocumentFile.fromTreeUri(context,permission.uri)!!.listFiles().count { it.isFile }
        try {
            repository.upsert(fixture)
            val preflight = NasRepository(fixture).testConnection()
            assertTrue("Synthetic SMB preflight: $preflight", preflight is NasResult.Success)
            val bound = NasRepository(fixture, context).testConnection()
            assertTrue("Synthetic SMB Wi-Fi binding: $bound", bound is NasResult.Success)
            val first=OneTimeWorkRequestBuilder<SyncWorker>().addTag("synthetic-recette").build()
            manager.enqueue(first).result.get(5,TimeUnit.SECONDS)
            awaitState(first.id,WorkInfo.State.SUCCEEDED)
            val path=RemotePath.source(permission.uri.toString(),listOf("source.txt"))
            val destination=RemotePath.sha256("SMB|${fixture.address}")
            val record=db.syncFileDao().get(destination,path.collisionKey)!!
            assertTrue(record.published)
            val second=OneTimeWorkRequestBuilder<SyncWorker>().addTag("synthetic-recette").build()
            manager.enqueue(second).result.get(5,TimeUnit.SECONDS)
            awaitState(second.id,WorkInfo.State.SUCCEEDED)
            assertEquals(record,db.syncFileDao().get(destination,path.collisionKey))
            db.openHelper.readableDatabase.query("SELECT message FROM sync_log ORDER BY id DESC LIMIT 1").use {
                assertTrue(it.moveToFirst()); assertTrue(it.getString(0),it.getString(0).contains("0 fichier(s) publié(s), $expectedCount déjà publié(s)"))
            }
        } finally { repository.upsert(original) }
    }
    @Test fun queuedWorkCanBeCancelledAndNewPassCanBeScheduledWithoutReusingCancelledWork() {
        val manager=WorkManager.getInstance(context)
        val queued=OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(1,TimeUnit.DAYS).addTag("synthetic-recette").build()
        manager.enqueueUniqueWork("synthetic-recette-manual",ExistingWorkPolicy.KEEP,queued).result.get(5,TimeUnit.SECONDS)
        manager.cancelWorkById(queued.id).result.get(5,TimeUnit.SECONDS)
        awaitState(queued.id,WorkInfo.State.CANCELLED)
        SyncExecutionGate.acquire(File(context.filesDir,"synczen-sync.lock"))!!.use {
            val restarted=OneTimeWorkRequestBuilder<SyncWorker>().addTag("synthetic-recette").build()
            manager.enqueueUniqueWork("synthetic-recette-manual",ExistingWorkPolicy.KEEP,restarted).result.get(5,TimeUnit.SECONDS)
            awaitState(restarted.id,WorkInfo.State.SUCCEEDED)
        }
        val old=manager.getWorkInfoById(queued.id).get()
        assertTrue("Cancelled predecessor may be pruned but must not restart",old==null || old.state==WorkInfo.State.CANCELLED)
    }
}

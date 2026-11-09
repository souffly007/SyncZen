package fr.bonobo.synczen.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMigrationCrashRecoveryTest {
    @Test fun processDeathRollsBackAndRetryMigratesWithoutLosingFictitiousData() = runBlocking {
        check(android.os.Build.HARDWARE == "ranchu")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="synthetic-crash-v1.db"
        assertTrue(context.getDatabasePath(name).exists())
        context.openOrCreateDatabase(name,android.content.Context.MODE_PRIVATE,null).use { sql ->
            assertEquals(1,sql.version)
            sql.rawQuery("SELECT COUNT(*) FROM nas_config WHERE username='fiction-user' AND password='fiction-password'",null).use {
                assertTrue(it.moveToFirst());assertEquals(2,it.getInt(0))
            }
            sql.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='nas_config_encrypted'",null).use {
                assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))
            }
        }
        val db=Room.databaseBuilder(context,AppDatabase::class.java,name)
            .addMigrations(CredentialMigration(AndroidKeystoreCredentials.cipher),SyncFileMigration()).addCallback(CredentialStorageCleanup()).build()
        try {
            assertEquals(NasConfig(1,ServerType.SMB,"smb://127.0.0.1:16445/fiction/","fiction-user","fiction-password",
                "content://fake/tree/retained","fiction-wifi",true,ConflictResolutionStrategy.SKIP,123456789),NasConfigRepository(db).getConfig())
            assertEquals(3,db.openHelper.writableDatabase.version)
            db.openHelper.readableDatabase.query("SELECT message,fileCount FROM sync_log WHERE id=7").use {
                assertTrue(it.moveToFirst());assertEquals("Crash fixture preserved",it.getString(0));assertEquals(42,it.getInt(1))
            }
        } finally { db.close();context.deleteDatabase(name) }
    }
}

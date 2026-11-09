package fr.bonobo.synczen.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/** Deliberate process-kill setup, NOT part of the normal test suite. Only synthetic DB. */
class AndroidMigrationCrashFixture {
    @Test fun pauseAfterFirstEncryptedRowForHostToKillOnlyThisEmulatorApp() {
        check(android.os.Build.HARDWARE == "ranchu")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val name="synthetic-crash-v1.db"
        check(!context.getDatabasePath(name).exists())
        context.openOrCreateDatabase(name,android.content.Context.MODE_PRIVATE,null).use { sql ->
            sql.execSQL("CREATE TABLE nas_config (id INTEGER NOT NULL PRIMARY KEY, serverType TEXT NOT NULL, address TEXT NOT NULL, username TEXT NOT NULL, password TEXT NOT NULL, sourceFolderUris TEXT NOT NULL, wifiSsid TEXT, requireCharging INTEGER NOT NULL, lastSyncTimestamp INTEGER NOT NULL)")
            sql.execSQL("CREATE TABLE sync_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL, fileCount INTEGER NOT NULL)")
            sql.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            sql.execSQL("INSERT INTO room_master_table VALUES(42,'b0d8db853bc8b5f14a76f9b71c1e48ec')")
            for(id in 1..2) sql.execSQL("INSERT INTO nas_config VALUES(?,'SMB','smb://127.0.0.1:16445/fiction/','fiction-user','fiction-password','content://fake/tree/retained','fiction-wifi',1,123456789)",arrayOf(id))
            sql.execSQL("INSERT INTO sync_log VALUES(7,987654321,'SUCCESS','Crash fixture preserved',42)")
            sql.version=1
        }
        val cipher=object : CredentialCipher {
            override fun encrypt(value: String, context: String): String {
                if(context=="2:username") {
                    instrumentation.sendStatus(42,android.os.Bundle().apply { putString("stream","SYNCZEN_READY_TO_KILL_SYNTHETIC_MIGRATION\n") })
                    Thread.sleep(600000) // Host stops this one emulator application at the marker.
                }
                return AndroidKeystoreCredentials.cipher.encrypt(value,context)
            }
            override fun decrypt(value: String,context: String)=AndroidKeystoreCredentials.cipher.decrypt(value,context)
        }
        val db = Room.databaseBuilder(context,AppDatabase::class.java,name)
            .addMigrations(CredentialMigration(cipher),SyncFileMigration()).build()
        try { db.openHelper.writableDatabase } finally { db.close() }
    }
}

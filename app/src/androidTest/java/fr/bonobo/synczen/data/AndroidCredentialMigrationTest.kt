package fr.bonobo.synczen.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class AndroidCredentialMigrationTest {
    @org.junit.Before fun emulatorOnly() { check(android.os.Build.HARDWARE == "ranchu") { "Synthetic emulator only" } }
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "synthetic-android-credentials-test.db"
    private val cipher = AndroidKeystoreCredentials.cipher
    private var opened: AppDatabase? = null
    private val expected = NasConfig(1, ServerType.WEBDAV, "https://nas.invalid/backup",
        "synthetic-user-é", "synthetic-password-🔐", "content://fake/tree/a|content://fake/tree/b",
        "Fictitious-Wifi", true, ConflictResolutionStrategy.SKIP, 123456789L)

    @After fun close() { opened?.close(); context.deleteDatabase(name) }

    // Exact v1 schema and Room identity from the pre-change generated AppDatabase_Impl.
    private fun legacy(configs: List<NasConfig> = listOf(expected)) {
        context.deleteDatabase(name)
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE nas_config (id INTEGER NOT NULL PRIMARY KEY, serverType TEXT NOT NULL, address TEXT NOT NULL, username TEXT NOT NULL, password TEXT NOT NULL, sourceFolderUris TEXT NOT NULL, wifiSsid TEXT, requireCharging INTEGER NOT NULL, lastSyncTimestamp INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE sync_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL, fileCount INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL("INSERT INTO room_master_table VALUES (42, 'b0d8db853bc8b5f14a76f9b71c1e48ec')")
            for (c in configs) db.execSQL("INSERT INTO nas_config VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf(
                c.id, c.serverType.name, c.address, c.username, c.password, c.sourceFolderUris,
                c.wifiSsid, if(c.requireCharging) 1 else 0, c.lastSyncTimestamp))
            db.execSQL("INSERT INTO sync_log VALUES (7, 987654321, 'SUCCESS', 'Synthetic log', 42)")
            db.version = 1
        }
    }

    private fun open(migrationCipher: CredentialCipher = cipher): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(CredentialMigration(migrationCipher), SyncFileMigration(), ConflictResolutionMigration())
            .addCallback(CredentialStorageCleanup())
            .allowMainThreadQueries().build().also { opened = it }

    @Test fun migrationPreservesAllFieldsLogsAndEncryptsEveryRow() = runBlocking {
        legacy(listOf(expected, expected.copy(id = 2, serverType = ServerType.SMB,
            username = "", password = "", wifiSsid = null, requireCharging = false)))
        val db = open()
        val repo = NasConfigRepository(db, cipher)
        assertEquals(expected, repo.getConfig()) // Opening also validates Room's v2 schema.
        val path = context.getDatabasePath(name)
        for (file in listOf(path, java.io.File(path.path + "-wal"))) {
            if (file.exists()) {
                val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(bytes.contains(expected.username.toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1)))
                assertFalse(bytes.contains(expected.password.toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1)))
            }
        }
        val sql = db.openHelper.writableDatabase
        sql.query("SELECT * FROM nas_config ORDER BY id").use { c ->
            assertEquals(2, c.count)
            assertEquals(-1, c.getColumnIndex("username"))
            assertEquals(-1, c.getColumnIndex("password"))
            while (c.moveToNext()) {
                val id = c.getInt(c.getColumnIndexOrThrow("id"))
                val u = c.getString(c.getColumnIndexOrThrow("usernameEncrypted"))
                val p = c.getString(c.getColumnIndexOrThrow("passwordEncrypted"))
                assertTrue(u.startsWith("v1:")); assertTrue(p.startsWith("v1:"))
                assertEquals(if(id == 1) expected.username else "", cipher.decrypt(u, "$id:username"))
                assertEquals(if(id == 1) expected.password else "", cipher.decrypt(p, "$id:password"))
            }
        }
        sql.query("SELECT * FROM sync_log").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(7, c.getInt(0))
            assertEquals(987654321L, c.getLong(1)); assertEquals("SUCCESS", c.getString(2))
            assertEquals("Synthetic log", c.getString(3)); assertEquals(42, c.getInt(4))
        }
        repo.upsert(expected.copy(address = "https://other.invalid/"))
        assertEquals(expected.copy(address = "https://other.invalid/"), repo.getConfig())
        db.close()
        assertEquals(expected.copy(address = "https://other.invalid/"), NasConfigRepository(open(), cipher).getConfig())
    }

    @Test fun encryptionFailureRollsBackLegacyDatabaseAndAllowsRetry() = runBlocking {
        legacy(listOf(expected, expected.copy(id = 2)))
        val failure = object : CredentialCipher {
            override fun encrypt(value: String, context: String): String {
                if (context.startsWith("2:")) error("synthetic failure after first row")
                return cipher.encrypt(value, context)
            }
            override fun decrypt(value: String, context: String): String = cipher.decrypt(value, context)
        }
        assertThrows(Exception::class.java) { open(failure).openHelper.writableDatabase }
        opened?.close()
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            assertEquals(1, db.version)
            db.rawQuery("SELECT username, password, sourceFolderUris, lastSyncTimestamp FROM nas_config", null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(expected.username, c.getString(0))
                assertEquals(expected.password, c.getString(1)); assertEquals(expected.sourceFolderUris, c.getString(2))
                assertEquals(expected.lastSyncTimestamp, c.getLong(3))
            }
        }
        assertEquals(expected, NasConfigRepository(open(), cipher).getConfig())
    }

    @Test fun decryptionFailureCannotOverwriteConfiguration() = runBlocking {
        legacy()
        val db = open()
        val repo = NasConfigRepository(db, cipher)
        assertEquals(expected, repo.getConfig())
        val before = db.nasConfigDao().getConfig()
        val broken = NasConfigRepository(db, AesGcmCredentialCipher { error("missing synthetic key") })
        try { broken.getConfig(); fail("Should fail") } catch (_: CredentialStorageException) { }
        try { broken.upsert(NasConfig(address = "https://replacement.invalid")); fail("Should fail") }
        catch (_: CredentialStorageException) { }
        assertEquals(before, db.nasConfigDao().getConfig())
        assertEquals(expected, repo.getConfig())
        db.openHelper.writableDatabase.execSQL("UPDATE nas_config SET passwordEncrypted = 'v1:corrupted' WHERE id = 1")
        val corrupted = db.nasConfigDao().getConfig()
        try { repo.upsert(expected); fail("Should fail") } catch (_: CredentialStorageException) { }
        assertEquals(corrupted, db.nasConfigDao().getConfig())
    }

    @Test fun savingConfigurationPreservesLatestSyncDate() = runBlocking {
        legacy()
        val db = open()
        val repo = NasConfigRepository(db, cipher)
        repo.getConfig()
        db.nasConfigDao().updateLastSync(999999999L)
        repo.upsert(expected)
        assertEquals(999999999L, repo.getConfig()!!.lastSyncTimestamp)
    }

    @Test fun legacyHttpConfigurationIsPreservedButCannotConnect() = runBlocking {
        val httpConfig = expected.copy(address = "http://nas.invalid/backup")
        legacy(listOf(httpConfig))
        val db = open()
        val repo = NasConfigRepository(db, cipher)
        val loaded = repo.getConfig()!!
        assertEquals(httpConfig, loaded)
        val encryptedBefore = db.nasConfigDao().getConfig()
        val result = NasRepository(loaded).testConnection()
        assertTrue(result is NasResult.Failure)
        assertTrue((result as NasResult.Failure).message.contains("HTTPS obligatoire"))
        assertEquals(encryptedBefore, db.nasConfigDao().getConfig())
        assertEquals(httpConfig, repo.getConfig())
    }

    @Test fun encryptedVersion2MigratesTo3AndPublicationJournalSurvivesReopen() = runBlocking {
        context.deleteDatabase(name)
        val version2Identity = "f18e58a48aa855fd56ffe38d6b6146cb"
        val u = cipher.encrypt(expected.username, "1:username")
        val p = cipher.encrypt(expected.password, "1:password")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sql ->
            sql.execSQL("CREATE TABLE nas_config (id INTEGER NOT NULL PRIMARY KEY, serverType TEXT NOT NULL, address TEXT NOT NULL, usernameEncrypted TEXT NOT NULL, passwordEncrypted TEXT NOT NULL, sourceFolderUris TEXT NOT NULL, wifiSsid TEXT, requireCharging INTEGER NOT NULL, lastSyncTimestamp INTEGER NOT NULL)")
            sql.execSQL("CREATE TABLE sync_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, status TEXT NOT NULL, message TEXT NOT NULL, fileCount INTEGER NOT NULL)")
            sql.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            sql.execSQL("INSERT INTO room_master_table VALUES (42, ?)", arrayOf(version2Identity))
            sql.execSQL("INSERT INTO nas_config VALUES (1, 'WEBDAV', ?, ?, ?, ?, ?, 1, ?)", arrayOf(
                expected.address, u, p, expected.sourceFolderUris, expected.wifiSsid, expected.lastSyncTimestamp))
            sql.execSQL("INSERT INTO sync_log VALUES (7, 987654321, 'SUCCESS', 'Synthetic log', 42)")
            sql.version = 2
        }
        val db = open()
        assertEquals(expected, NasConfigRepository(db, cipher).getConfig())
        assertEquals(u, db.nasConfigDao().getConfig()!!.usernameEncrypted)
        assertEquals(p, db.nasConfigDao().getConfig()!!.passwordEncrypted)
        val record = SyncFileRecord("fictional-destination", "canonical-path", "SyncZen-v3/source-fake/file", "fake-sha", 42L, false)
        db.syncFileDao().put(record)
        assertEquals(record, db.syncFileDao().get(record.destinationId, record.pathKey))
        db.close()
        val reopened = open()
        assertEquals(record, reopened.syncFileDao().get(record.destinationId, record.pathKey))
        assertEquals(expected, NasConfigRepository(reopened, cipher).getConfig())
        reopened.openHelper.writableDatabase.query("SELECT message FROM sync_log WHERE id = 7").use {
            assertTrue(it.moveToFirst()); assertEquals("Synthetic log", it.getString(0))
        }
    }

    @Test fun emptyLegacyConfigurationTableMigrates() = runBlocking {
        legacy(emptyList())
        val repo = NasConfigRepository(open(), cipher)
        assertNull(repo.getConfig())
        repo.upsert(expected)
        assertEquals(expected, repo.getConfig())
    }
}

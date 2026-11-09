package fr.bonobo.synczen.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidUpgradeTest {
    @org.junit.Before fun emulatorOnly() { check(android.os.Build.HARDWARE == "ranchu") { "Synthetic emulator only" } }
    @Test fun installedLegacyDebugMigratesInPlaceAndPreservesFictitiousConfigAndLog() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        val config = NasConfigRepository(db).getConfig()!!
        assertEquals(NasConfig(1, ServerType.WEBDAV, "http://nas.invalid/fictitious", "fictional-user",
            "fictional-password", "content://fake/tree/one|content://fake/tree/two", "Fictitious-Wifi", true, ConflictResolutionStrategy.SKIP, 123456789), config)
        assertEquals(3, db.openHelper.writableDatabase.version)
        db.openHelper.writableDatabase.query("SELECT message, fileCount FROM sync_log WHERE id = 7").use {
            assertTrue(it.moveToFirst()); assertEquals("Fictitious upgrade log", it.getString(0)); assertEquals(42,it.getInt(1))
        }
        val stored = db.nasConfigDao().getConfig()!!
        assertTrue(stored.usernameEncrypted.startsWith("v1:")); assertTrue(stored.passwordEncrypted.startsWith("v1:"))
        assertTrue(NasRepository(config).testConnection() is NasResult.Failure)
        assertEquals(stored, db.nasConfigDao().getConfig())
    }
}

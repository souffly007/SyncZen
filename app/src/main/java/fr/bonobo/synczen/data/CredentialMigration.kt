package fr.bonobo.synczen.data

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Room runs this migration in a transaction: any encryption error rolls everything back. */
class CredentialMigration(private val cipher: CredentialCipher) : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
        db.execSQL("""CREATE TABLE nas_config_encrypted (
            id INTEGER NOT NULL PRIMARY KEY, serverType TEXT NOT NULL, address TEXT NOT NULL,
            usernameEncrypted TEXT NOT NULL, passwordEncrypted TEXT NOT NULL,
            sourceFolderUris TEXT NOT NULL, wifiSsid TEXT, requireCharging INTEGER NOT NULL,
            lastSyncTimestamp INTEGER NOT NULL)""")
        db.query("SELECT * FROM nas_config").use { cursor ->
            while (cursor.moveToNext()) {
                fun text(column: String) = cursor.getString(cursor.getColumnIndexOrThrow(column))
                val id = cursor.getInt(cursor.getColumnIndexOrThrow("id"))
                val username = text("username")
                val password = text("password")
                val userEncrypted = cipher.encrypt(username, "$id:username")
                val passEncrypted = cipher.encrypt(password, "$id:password")
                // Verify the new representation before dropping the legacy table.
                check(cipher.decrypt(userEncrypted, "$id:username") == username)
                check(cipher.decrypt(passEncrypted, "$id:password") == password)
                db.execSQL("INSERT INTO nas_config_encrypted VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf(
                    id, text("serverType"), text("address"), userEncrypted, passEncrypted,
                    text("sourceFolderUris"), text("wifiSsid"),
                    cursor.getInt(cursor.getColumnIndexOrThrow("requireCharging")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("lastSyncTimestamp"))))
            }
        }
        db.execSQL("DROP TABLE nas_config")
        db.execSQL("ALTER TABLE nas_config_encrypted RENAME TO nas_config")
    }
}

/** Flush migrated pages and truncate WAL before any configuration is delivered to the app. */
class CredentialStorageCleanup : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                "Credential database checkpoint unavailable; configuration retained"
            }
        }
    }
}

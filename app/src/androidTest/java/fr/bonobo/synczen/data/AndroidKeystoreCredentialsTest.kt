package fr.bonobo.synczen.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on an isolated emulator/test device with fictitious data. No network calls. */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCredentialsTest {
    @org.junit.Before fun emulatorOnly() { check(android.os.Build.HARDWARE == "ranchu") { "Synthetic emulator only" } }
    @Test fun missingDedicatedSyntheticKeyIsNotRegeneratedByDecryption() {
        val alias = "synczen.recette.synthetic." + java.util.UUID.randomUUID()
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cipher = AesGcmCredentialCipher { create ->
            if (!store.containsAlias(alias)) {
                check(create) { "Missing fictitious key" }
                javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                    init(android.security.keystore.KeyGenParameterSpec.Builder(alias, 3)
                        .setKeySize(256).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
                }.generateKey()
            }
            store.getKey(alias, null) as javax.crypto.SecretKey
        }
        try {
            val encrypted = cipher.encrypt("fictional-secret", "fiction:password")
            assertEquals("fictional-secret", cipher.decrypt(encrypted, "fiction:password"))
            store.deleteEntry(alias) // Only a dedicated randomly named test key in this emulator.
            assertThrows(CredentialStorageException::class.java) { cipher.decrypt(encrypted, "fiction:password") }
            assertFalse(store.containsAlias(alias))
        } finally { store.deleteEntry(alias) }
    }
    @Test fun keystoreRoundTripUsesUniqueIvAndAuthenticatesField() {
        val cipher = AndroidKeystoreCredentials.cipher
        val value = "synthetic-emulator-password-é🔐"
        val first = cipher.encrypt(value, "test:password")
        val second = cipher.encrypt(value, "test:password")
        assertNotEquals(first, second)
        assertEquals(value, cipher.decrypt(first, "test:password"))
        assertThrows(CredentialStorageException::class.java) {
            cipher.decrypt(first, "test:username")
        }
    }
}

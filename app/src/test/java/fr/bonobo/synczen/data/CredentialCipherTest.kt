package fr.bonobo.synczen.data

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class CredentialCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmCredentialCipher { key }

    @Test fun roundTripAndRandomNonce() {
        for (value in listOf("", "fake-user", "fictif-é🔐|123")) {
            val first = cipher.encrypt(value, "1:password")
            val second = cipher.encrypt(value, "1:password")
            assertNotEquals(first, second)
            assertEquals(value, cipher.decrypt(first, "1:password"))
        }
    }

    @Test fun tamperingWrongFieldWrongKeyAndMalformedDataFail() {
        val encrypted = cipher.encrypt("synthetic-password", "1:password")
        val bytes = java.util.Base64.getDecoder().decode(encrypted.substring(3))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = "v1:" + java.util.Base64.getEncoder().encodeToString(bytes)
        assertThrows(CredentialStorageException::class.java) { cipher.decrypt(tampered, "1:password") }
        assertThrows(CredentialStorageException::class.java) { cipher.decrypt(encrypted, "1:username") }
        assertThrows(CredentialStorageException::class.java) { cipher.decrypt(encrypted, "2:password") }
        val other = AesGcmCredentialCipher { KeyGenerator.getInstance("AES").generateKey() }
        assertThrows(CredentialStorageException::class.java) { other.decrypt(encrypted, "1:password") }
        for (bad in listOf("plain", "v2:abc", "v1:!", "v1:")) {
            assertThrows(CredentialStorageException::class.java) { cipher.decrypt(bad, "1:password") }
        }
    }

    @Test fun missingKeyDoesNotRequestCreationDuringDecryption() {
        val encrypted = cipher.encrypt("synthetic-password", "1:password")
        val missing = AesGcmCredentialCipher { create ->
            assertFalse(create)
            error("missing key")
        }
        assertThrows(CredentialStorageException::class.java) { missing.decrypt(encrypted, "1:password") }
    }
}

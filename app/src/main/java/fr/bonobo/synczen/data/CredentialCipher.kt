package fr.bonobo.synczen.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialCipher {
    fun encrypt(value: String, context: String): String
    fun decrypt(value: String, context: String): String
}

class CredentialStorageException(cause: Throwable) : Exception(
    "Identifiants NAS indisponibles : configuration conservée. Aucun remplacement automatique.", cause
)

/** Versioned AES-256-GCM envelope, with a fresh IV and field/row-bound authentication. */
class AesGcmCredentialCipher(private val keyProvider: (Boolean) -> SecretKey) : CredentialCipher {
    override fun encrypt(value: String, context: String): String = protect {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider(true))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        "v1:" + Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    override fun decrypt(value: String, context: String): String = protect {
        require(value.startsWith("v1:")) { "Unsupported credential format" }
        val bytes = Base64.getDecoder().decode(value.substring(3))
        require(bytes.size >= 28) { "Invalid credential envelope" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    private fun <T> protect(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw CredentialStorageException(e)
    }
}

object AndroidKeystoreCredentials {
    private const val ALIAS = "synczen.nas.credentials.v1"
    val cipher: CredentialCipher by lazy { AesGcmCredentialCipher(::key) }

    @Synchronized
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) {
            return store.getKey(ALIAS, null) as SecretKey
        }
        check(create) { "NAS credential key is missing" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
}

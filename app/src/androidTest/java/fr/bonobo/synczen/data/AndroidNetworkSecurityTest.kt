package fr.bonobo.synczen.data

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Policy-only check on an isolated emulator; no requests or personal configuration. */
@RunWith(AndroidJUnit4::class)
class AndroidNetworkSecurityTest {
    @org.junit.Before fun emulatorOnly() { check(android.os.Build.HARDWARE == "ranchu") { "Synthetic emulator only" } }
    @Test fun platformHttpConnectionRejectsCleartextBeforeContactingLoopback() {
        val connection = java.net.URL("http://127.0.0.1:9/fictitious").openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 1000; connection.readTimeout = 1000
        try {
            val error = org.junit.Assert.assertThrows(java.io.IOException::class.java) { connection.inputStream.use { it.read() } }
            org.junit.Assert.assertTrue(error.toString(), error.toString().contains("cleartext", true))
        } finally { connection.disconnect() }
    }
    @Test fun cleartextHttpIsForbiddenGlobally() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertFalse(policy.isCleartextTrafficPermitted)
        assertFalse(policy.isCleartextTrafficPermitted("nas.invalid"))
        assertFalse(policy.isCleartextTrafficPermitted("127.0.0.1"))
    }
}

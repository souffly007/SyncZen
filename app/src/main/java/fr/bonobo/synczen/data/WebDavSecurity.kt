package fr.bonobo.synczen.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

internal class WebDavSecurityException(message: String) : IllegalArgumentException(message)

internal object WebDavSecurity {
    const val HTTPS_REQUIRED = "WebDAV bloqué : HTTPS obligatoire. Configuration conservée ; renseignez une adresse HTTPS pour utiliser cette destination."

    fun requireHttpsUrl(address: String): HttpUrl {
        val url = address.toHttpUrlOrNull()
            ?: throw WebDavSecurityException("Adresse WebDAV invalide : une URL HTTPS est obligatoire.")
        if (!url.isHttps) throw WebDavSecurityException(HTTPS_REQUIRED)
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw WebDavSecurityException("Adresse WebDAV invalide : utilisez les champs utilisateur et mot de passe, sans identifiants dans l'URL.")
        }
        return url
    }

    // Keep platform certificate/hostname verification. Only redirect policy is changed.
    fun client(base: OkHttpClient = OkHttpClient()): OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun redirectMessage(code: Int) =
        "Redirection WebDAV refusée (HTTP $code). Renseignez directement l'adresse HTTPS finale ; aucun suivi automatique."
}

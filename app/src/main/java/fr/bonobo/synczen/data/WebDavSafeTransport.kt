package fr.bonobo.synczen.data

import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import kotlinx.coroutines.*
import okhttp3.RequestBody
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

internal class WebDavSafeTransport(address: String, client: OkHttpClient, private val auth: String) : SafeRemoteTransport {
    private val client = client.newBuilder().retryOnConnectionFailure(false).build()
    private val base: HttpUrl = WebDavSecurity.requireHttpsUrl(address)
    private var verified = false
    private var verificationFailure: String? = null
    private val etags = mutableMapOf<String, String>()
    init {
        require(base.query == null && base.fragment == null) { "La destination WebDAV ne doit pas contenir de requête ni fragment" }
        URI(address.trim()).rawPath.split('/').filter { it.isNotEmpty() }.forEach {
            RemotePath.validateSegment(java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8"))
        }
        require(!client.followRedirects && !client.followSslRedirects) { "Redirections interdites" }
    }
    internal fun url(segments: List<String>, directory: Boolean = false): HttpUrl {
        val builder = base.newBuilder()
        segments.forEach { RemotePath.validateSegment(it, true); builder.addPathSegment(it) }
        if (directory) builder.addPathSegment("")
        return builder.build().also {
            check(it.isHttps && it.host == base.host && it.port == base.port)
            check(it.encodedPath.startsWith(base.encodedPath.trimEnd('/') + "/"))
        }
    }
    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", auth)
        .header("Accept-Encoding", "identity").cacheControl(CacheControl.Builder().noCache().noStore().build())
    // The watcher remains alive until the response body has also been consumed/closed.
    private suspend fun <T> response(request: Request, block: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use {
                currentCoroutineContext().ensureActive()
                if (it.code in 300..399) throw SyncConflict(WebDavSecurity.redirectMessage(it.code))
                block(it)
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            withContext(NonCancellable) { watcher.cancelAndJoin() }
        }
    }
    override suspend fun fingerprint(path: RemotePath): FileFingerprint? {
        response(request(url(path.segments)).head().build()) {
            if (it.code == 404) return@response false
            if (it.code != 200) throw SyncConflict("Destination non vérifiable : HTTP ${it.code}")
            true
        }.also { if (!it) return null }
        return response(request(url(path.segments)).get().build()) {
            if (it.code != 200 || it.body == null) throw SyncConflict("Contenu distant non vérifiable : HTTP ${it.code}")
            val fingerprint = it.body!!.byteStream().use { input -> FileFingerprint.read(input) }
            val etag = it.header("ETag")
            if (etag != null && etag.startsWith('"') && etag.endsWith('"')) etags[path.value] = etag
            else etags.remove(path.value)
            fingerprint
        }
    }
    override suspend fun ensureParents(path: RemotePath) { path.parents.forEach { ensureDirectory(it) } }
    private suspend fun ensureDirectory(segments: List<String>) {
        val target = url(segments, true)
        response(request(target).method("MKCOL", null).build()) {
            if (it.code == 201) return@response true
            if (it.code != 405) throw SyncConflict("Création du dossier refusée : HTTP ${it.code}")
            false
        }.also { if (it) return }
        // 405 can mean a file exists. Require an actual DAV collection, not just a successful HEAD.
        val xml = """<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        response(request(target).header("Depth", "0").method("PROPFIND", xml.toRequestBody("application/xml; charset=utf-8".toMediaType())).build()) {
            if (it.code != 207) throw SyncConflict("Dossier distant existant non vérifiable : HTTP ${it.code}")
            val body = it.body ?: throw SyncConflict("Description du dossier absente")
            val bytes = body.byteStream().use { input -> input.readBytesLimited(1024 * 1024) }
            val text = String(bytes, Charsets.UTF_8)
            if (text.contains("<!DOCTYPE", true) || text.contains("<!ENTITY", true)) throw SyncConflict("Description XML dangereuse refusée")
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
            val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
            val responses = doc.getElementsByTagNameNS("DAV:", "response")
            if (responses.length != 1) throw SyncConflict("Description du dossier ambiguë")
            val response = responses.item(0) as org.w3c.dom.Element
            val href = response.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent
            val described = href?.let { base.resolve(it) }
            if (described == null || described.scheme != target.scheme || described.host != target.host ||
                described.port != target.port || described.encodedPath.trimEnd('/') != target.encodedPath.trimEnd('/')) {
                throw SyncConflict("Description du dossier hors destination")
            }
            val properties = response.getElementsByTagNameNS("DAV:", "propstat")
            val directory = (0 until properties.length).any { index ->
                val property = properties.item(index) as org.w3c.dom.Element
                val status = property.getElementsByTagNameNS("DAV:", "status").item(0)?.textContent.orEmpty()
                Regex("HTTP/\\S+ 200(?: .*|$)").matches(status.trim()) &&
                    property.getElementsByTagNameNS("DAV:", "collection").length == 1
            }
            if (!directory) throw SyncConflict("Conflit : un parent existe sans être un dossier")
        }
    }
    override suspend fun uploadExclusive(path: RemotePath, file: File) {
        require(path.isTemporary) { "Le contenu source doit être envoyé uniquement vers un temporaire" }
        putExclusive(path, StreamingFileBody(file, currentCoroutineContext()))
    }
    private suspend fun putExclusive(path: RemotePath, body: RequestBody) {
        response(request(url(path.segments)).header("If-None-Match", "*").put(body).build()) {
            if (it.code != 201) throw SyncConflict("Création exclusive refusée ou non garantie : HTTP ${it.code}")
        }
    }
    private suspend fun move(temporary: RemotePath, final: RemotePath): Int {
        val etag = etags[temporary.value] ?: throw SyncConflict("Publication sûre indisponible : ETag fort absent")
        return response(request(url(temporary.segments)).header("Destination", url(final.segments).toString())
            .header("Overwrite", "F").header("If-Match", etag).method("MOVE", null).build()) { it.code }
    }
    override suspend fun publishNoReplace(temporary: RemotePath, final: RemotePath) {
        val code = move(temporary, final)
        if (code != 201) throw SyncConflict("Conflit ou publication sans écrasement indisponible : HTTP $code")
    }

    override suspend fun publishWithOverwrite(temporary: RemotePath, final: RemotePath) {
        val etag = etags[temporary.value] ?: throw SyncConflict("Publication indisponible : ETag absent")
        val code = response(request(url(temporary.segments))
            .header("Destination", url(final.segments).toString())
            .header("Overwrite", "T")  // T pour true (écraser)
            .header("If-Match", etag)
            .method("MOVE", null).build()) { it.code }
        
        if (code != 204 && code != 201) {
            throw SyncConflict("Échec de l'écrasement WebDAV : HTTP $code")
        }
    }

    override suspend fun delete(path: RemotePath) {
        val code = response(request(url(path.segments)).delete().build()) { it.code }
        if (code != 204 && code != 200 && code != 404) {
            throw SyncConflict("Échec de la suppression WebDAV : HTTP $code")
        }
    }
    override suspend fun verifyPublicationSupport() {
        if (verified) return
        verificationFailure?.let { throw SyncConflict(it) }
        try {
            probeSupport()
            verified = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = if (e is SyncConflict) e.message.orEmpty() else "Garanties WebDAV non vérifiables : publication bloquée"
            verificationFailure = reason
            throw SyncConflict(reason)
        }
    }
    private suspend fun probeSupport() {
        ensureDirectory(listOf(RemotePath.NAMESPACE))
        val id = UUID.randomUUID().toString()
        val first = RemotePath.probe(".synczen-probe-$id-a")
        val second = RemotePath.probe(".synczen-probe-$id-b")
        val a = "SyncZen synthetic publication probe A".toByteArray()
        val b = "SyncZen synthetic publication probe B".toByteArray()
        putExclusive(first, a.toRequestBody()); putExclusive(second, b.toRequestBody())
        val beforeA = fingerprint(first); val beforeB = fingerprint(second)
        response(request(url(first.segments)).header("If-None-Match", "*").put(b.toRequestBody("application/octet-stream".toMediaType())).build()) {
            if (it.code != 412) throw SyncConflict("Publication bloquée : le serveur ne garantit pas la création exclusive")
        }
        if (fingerprint(first) != beforeA || beforeA == null || beforeB == null) throw SyncConflict("Sonde exclusive incohérente : publication bloquée")
        if (move(first, second) != 412 || fingerprint(first) != beforeA || fingerprint(second) != beforeB) {
            throw SyncConflict("Publication bloquée : le serveur ne respecte pas MOVE avec Overwrite: F")
        }
        // Probe and interrupted transfer files remain: no automatic DELETE, no uncertain cleanup.
    }
}

private suspend fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = read(buffer)
        if (count < 0) break
        if (out.size() + count > limit) throw SyncConflict("Description XML trop volumineuse")
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

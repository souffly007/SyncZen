package fr.bonobo.synczen.data

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

internal class SyncConflict(message: String) : Exception(message)

internal data class RemotePath private constructor(val segments: List<String>) {
    val value: String get() = segments.joinToString("/")
    val collisionKey: String get() = canonical(value)
    val isTemporary: Boolean get() = segments.last().startsWith(".synczen-upload-") && segments.last().endsWith(".part")
    val parents: List<List<String>> get() = (1 until segments.size).map { segments.take(it) }

    fun temporary(): RemotePath = checked(segments.dropLast(1) + ".synczen-upload-${java.util.UUID.randomUUID()}.part", true)
    fun sibling(name: String): RemotePath = checked(segments.dropLast(1) + name, false)

    companion object {
        const val NAMESPACE = "SyncZen-v3"
        fun source(sourceUri: String, relative: List<String>): RemotePath {
            require(relative.isNotEmpty()) { "Chemin relatif vide" }
            relative.forEach { validateSegment(it) }
            return checked(listOf(NAMESPACE, "source-${sha256(sourceUri)}") + relative, true)
        }
        fun probe(name: String) = checked(listOf(NAMESPACE, name), true)
        private fun checked(segments: List<String>, allowInternal: Boolean): RemotePath {
            require(segments.size <= 64) { "Chemin trop profond" }
            segments.forEach { validateSegment(it, allowInternal) }
            return RemotePath(segments.toList())
        }
        fun validateSegment(name: String, allowInternal: Boolean = false) {
            require(name.isNotEmpty() && name != "." && name != "..") { "Segment de chemin invalide" }
            require(name == name.trim() && !name.endsWith('.')) { "Nom ambigu : espace ou point terminal" }
            require(name.none { it.isISOControl() || it in "<>:\"/\\|?*%#" }) { "Caractère de chemin interdit" }
            require(name.toByteArray(Charsets.UTF_8).size <= 240) { "Nom trop long" }
            require(allowInternal || !name.startsWith(".synczen-", true)) { "Nom réservé aux transferts temporaires" }
            val stem = name.substringBefore('.').uppercase(Locale.ROOT)
            require(stem !in setOf("CON", "PRN", "AUX", "NUL") &&
                !stem.matches(Regex("(?:COM|LPT)[1-9¹²³]"))) { "Nom de fichier réservé" }
        }
        fun canonical(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** Detect case/Unicode directory aliases, duplicate leaves and file/directory collisions. */
        fun collisions(paths: List<RemotePath>): Set<RemotePath> {
            val names = mutableMapOf<String, MutableSet<Pair<String, Boolean>>>()
            val leaves = paths.groupingBy { it.collisionKey }.eachCount()
            for (path in paths) for (size in 1..path.segments.size) {
                val raw = path.segments.take(size).joinToString("/")
                names.getOrPut(canonical(raw)) { mutableSetOf() }.add(raw to (size == path.segments.size))
            }
            val bad = names.filterValues { it.size > 1 }.keys + leaves.filterValues { it > 1 }.keys
            return paths.filter { path -> (1..path.segments.size).any {
                canonical(path.segments.take(it).joinToString("/")) in bad
            } }.toSet()
        }
    }
}

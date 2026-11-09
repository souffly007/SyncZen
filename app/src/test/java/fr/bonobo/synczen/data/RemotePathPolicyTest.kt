package fr.bonobo.synczen.data

import org.junit.Assert.*
import org.junit.Test

class RemotePathPolicyTest {
    @Test fun stableSourcesAndRelativeHierarchyPreventHomonymCollisions() {
        val a = RemotePath.source("content://fake/tree/a", listOf("photos", "2026", "same.jpg"))
        val b = RemotePath.source("content://fake/tree/b", listOf("photos", "2026", "same.jpg"))
        assertEquals(a, RemotePath.source("content://fake/tree/a", listOf("photos", "2026", "same.jpg")))
        assertNotEquals(a.value, b.value)
        assertTrue(a.value.endsWith("/photos/2026/same.jpg"))
        assertTrue(RemotePath.collisions(listOf(a,b)).isEmpty())
    }
    @Test fun traversalAbsoluteEncodedAndPortableAmbiguousNamesAreRejected() {
        for (bad in listOf("", ".", "..", "a/b", "a\\b", "/absolute", "a:b", "%2e%2e", "%252f", "a?b", "a#b",
            "trailing.", " space", "end ", "NUL.txt", "com1.jpg", "a\u0000b", ".synczen-upload-fake.part")) {
            assertThrows(IllegalArgumentException::class.java) { RemotePath.source("content://fake", listOf(bad)) }
        }
    }
    @Test fun caseUnicodeDirectoryAliasesAndFileDirectoryCollisionsAreDetected() {
        fun p(vararg s: String) = RemotePath.source("content://fake", s.toList())
        for (paths in listOf(listOf(p("A.txt"), p("a.txt")),
            listOf(p("é.txt"),p("e\u0301.txt")),
            listOf(p("Photos","a.txt"),p("photos","b.txt")),
            listOf(p("folder"),p("folder","a.txt")), listOf(p("same.txt"),p("same.txt")))) {
            assertEquals(paths.toSet(), RemotePath.collisions(paths))
        }
        assertTrue(RemotePath.collisions(listOf(p("a", "same.txt"), p("b", "same.txt"))).isEmpty())
    }
    @Test fun temporaryNamesAreUniqueAndStayWithFinalParent() {
        val final = RemotePath.source("content://fake", listOf("nested","file.txt"))
        val a=final.temporary(); val b=final.temporary()
        assertNotEquals(a,b)
        assertEquals(final.segments.dropLast(1),a.segments.dropLast(1))
        assertTrue(a.value.endsWith(".part"))
    }
}

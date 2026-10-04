package fr.bonobo.synczen.data

import org.junit.Assert.assertEquals
import org.junit.Test

class NasConfigTest {

    @Test
    fun `sourceFolderUriList parses pipe-separated URIs`() {
        val config = NasConfig(sourceFolderUris = "content://a|content://b")
        assertEquals(listOf("content://a", "content://b"), config.sourceFolderUriList())
    }

    @Test
    fun `sourceFolderUriList is empty when blank`() {
        val config = NasConfig(sourceFolderUris = "")
        assertEquals(emptyList<String>(), config.sourceFolderUriList())
    }

    @Test
    fun `ensureTrailingSlash-style address stays consistent`() {
        // NasRepository ajoute un slash final avant d'assembler le chemin distant ;
        // ce test documente juste le format d'adresse attendu en config.
        val config = NasConfig(address = "smb://192.168.1.50/partage")
        assertEquals("smb://192.168.1.50/partage", config.address)
    }
}

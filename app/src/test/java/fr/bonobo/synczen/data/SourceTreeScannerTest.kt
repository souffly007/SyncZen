package androidx.documentfile.provider

import fr.bonobo.synczen.data.*

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class SourceTreeScannerTest {
    private class Node(private val label: String?, private val directory: Boolean,
        val children: MutableList<Node> = mutableListOf(), var readable: Boolean=true) : DocumentFile(null) {
        private val identity=Uri.parse("content://fictitious/${java.util.UUID.randomUUID()}")
        override fun getUri()=identity
        override fun getName()=label
        override fun getType(): String?=null
        override fun isDirectory()=directory
        override fun isFile()=!directory
        override fun isVirtual()=false
        override fun lastModified()=123L
        override fun length()=42L
        override fun canRead()=readable
        override fun canWrite()=false
        override fun delete()=false
        override fun exists()=true
        override fun listFiles(): Array<DocumentFile> = children.toTypedArray()
        override fun renameTo(displayName: String)=false
        override fun createFile(mimeType: String,displayName: String): DocumentFile?=null
        override fun createDirectory(displayName: String): DocumentFile?=null
    }
    @Test fun nestedHierarchyAndHomonymsRemainSeparateAcrossDirectoriesAndRoots() {
        val a=Node("same.txt",false);val b=Node("same.txt",false)
        val root=Node("root",true,mutableListOf(Node("a",true,mutableListOf(a)),Node("b",true,mutableListOf(b))))
        val files=SourceTreeScanner.scan("content://fake/root1",root)
        assertEquals(2,files.size)
        assertTrue(files[0].path.value.endsWith("/a/same.txt")); assertTrue(files[1].path.value.endsWith("/b/same.txt"))
        assertSame(a,files[0].document);assertSame(b,files[1].document)
        val second=SourceTreeScanner.scan("content://fake/root2",root)
        assertTrue(RemotePath.collisions(files.map { it.path }+second.map { it.path }).isEmpty())
    }
    @Test fun traversalUnreadableAndUnnamedSourcesFailBeforeReturningCandidates() {
        for(node in listOf(Node("..",false),Node(null,false),Node("safe.txt",false,readable=false))) {
            val root=Node("root",true,mutableListOf(Node("first.txt",false),node))
            assertThrows(Exception::class.java) { SourceTreeScanner.scan("content://fake/root",root) }
        }
        assertThrows(SyncConflict::class.java) { SourceTreeScanner.scan("content://fake/root",Node("root",true,readable=false)) }
    }
    @Test fun cyclesAndDirectoryCaseAliasesCannotProduceUnsafePublication() {
        val cycle=Node("root",true);cycle.children.add(cycle)
        assertThrows(SyncConflict::class.java) { SourceTreeScanner.scan("content://fake/root",cycle) }
        val root=Node("root",true,mutableListOf(Node("Photos",true,mutableListOf(Node("a.txt",false))),
            Node("photos",true,mutableListOf(Node("b.txt",false)))))
        val paths=SourceTreeScanner.scan("content://fake/root",root).map { it.path }
        assertEquals(paths.toSet(),RemotePath.collisions(paths))
    }
}

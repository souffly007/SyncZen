package fr.bonobo.synczen.data

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.SmbFile
import jcifs.smb.SmbAuthException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.TimeUnit

/** Standalone smbd on loopback only. All Samba state and shared files are in a new temp directory. */
class SmbSafePublicationTest {
    private lateinit var directory: File
    private lateinit var share: File
    private var server: Process?=null
    private var context: CIFSContext?=null
    private lateinit var remote: SmbSafeTransport

    @Before fun startIsolatedSamba() {
        assumeTrue("Local smbd unavailable",File("/usr/bin/smbd").canExecute())
        directory=Files.createTempDirectory("synczen-fictitious-smb-").toFile()
        directory.setReadable(true,false); directory.setExecutable(true,false)
        share=File(directory,"share").apply {
            mkdir(); setReadable(true,false); setWritable(true,false); setExecutable(true,false)
        }
        val port=ServerSocket(0,50,InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val username=System.getProperty("user.name") // OS owner of fictitious files, no NAS credentials.
        for (name in listOf("state","lock","cache","private","pid","rpc","winbind","ntp")) File(directory,name).mkdir()
        val conf=File(directory,"smb.conf")
        conf.writeText("""
            [global]
            server role = standalone server
            security = user
            log level = 3
            map to guest = Bad User
            guest account = $username
            interfaces = 127.0.0.1
            bind interfaces only = yes
            smb ports = $port
            disable netbios = yes
            server min protocol = SMB2_02
            server max protocol = SMB3
            server signing = disabled
            load printers = no
            disable spoolss = yes
            printing = bsd
            printcap name = /dev/null
            state directory = ${directory.path}/state
            lock directory = ${directory.path}/lock
            cache directory = ${directory.path}/cache
            private dir = ${directory.path}/private
            pid directory = ${directory.path}/pid
            ncalrpc dir = ${directory.path}/rpc
            winbindd socket directory = ${directory.path}/winbind
            ntp signd socket directory = ${directory.path}/ntp
            log file = ${directory.path}/log.smbd
            passdb backend = tdbsam:${directory.path}/private/passdb.tdb
            [fiction]
            path = ${share.path}
            read only = no
            guest ok = yes
            guest only = yes
            create mask = 0666
            force create mode = 0666
            directory mask = 0777
            force directory mode = 0777
            force user = $username
        """.trimIndent())
        server=ProcessBuilder("/usr/bin/setsid","/usr/bin/smbd","--foreground","--no-process-group","--log-basename=${directory.path}","--debug-stdout","--configfile=${conf.path}")
            .redirectErrorStream(true).redirectOutput(File(directory,"process.log")).start()
        var listening=false
        repeat(100) {
            if(!listening && server!!.isAlive) {
                try { Socket().use { socket -> socket.connect(java.net.InetSocketAddress("127.0.0.1",port),50) }; listening=true }
                catch (_: java.io.IOException) { Thread.sleep(50) }
            }
        }
        assertTrue("Isolated smbd not listening: ${File(directory,"process.log").readText()}",listening)
        val props=Properties().apply {
            setProperty("jcifs.smb.client.minVersion","SMB202")
            setProperty("jcifs.smb.client.maxVersion","SMB311")
            setProperty("jcifs.smb.client.connTimeout","2000")
            setProperty("jcifs.smb.client.responseTimeout","3000")
            setProperty("jcifs.smb.client.resolveOrder","DNS")
        }
        context=BaseContext(PropertyConfiguration(props)).withAnonymousCredentials()
        val base="smb://127.0.0.1:$port/fiction/"
        try { SmbFile(base,context!!).use { it.listFiles() }
            SmbFile(base+"fictitious-write-probe/",context!!).use { it.mkdir() } } catch (e: SmbAuthException) {
            println("Isolated Samba guest session unavailable. Diagnostics: " +
                File(directory,"process.log").readLines().filter {
                    it.contains("denied",true) || it.contains("setgroups",true) || it.contains("setuid",true) || it.contains("failed",true)
                }.takeLast(10).joinToString("\n"))
            org.junit.Assume.assumeNoException("Local Samba guest session denied; no NAS test attempted",e)
        }
        remote=SmbSafeTransport(base,context!!)
    }
    @After fun stopOnlyIsolatedServer() {
        if(::directory.isInitialized && File(directory,"process.log").exists()) {
            println(File(directory,"process.log").readLines().filter {
                it.contains("denied",true) || it.contains("setgroups",true) || it.contains("setuid",true) || it.contains("failed",true)
            }.takeLast(10).joinToString("\n"))
        }
        context?.close()
        server?.let { process ->
            // Android's compile stubs omit ProcessHandle; the local JDK provides it at runtime.
            val handle = Process::class.java.getMethod("toHandle").invoke(process)
            val type = Class.forName("java.lang.ProcessHandle")
            (type.getMethod("descendants").invoke(handle) as java.util.stream.Stream<*>).use { children ->
                children.forEach { child -> type.getMethod("destroy").invoke(child) }
            }
            process.destroy()
            if(!process.waitFor(3,TimeUnit.SECONDS)) process.destroyForcibly()
        }
        if(::directory.isInitialized) directory.deleteRecursively()
    }
    private class Journal : PublicationJournal {
        val records=mutableMapOf<String,SyncFileRecord>()
        override suspend fun get(destination: String,pathKey: String)=records[pathKey]
        override suspend fun put(record: SyncFileRecord) { records[record.pathKey]=record }
    }
    @Test fun artificial128MiBPublicationVerifiesSha256AndRestartDoesNotUploadAgain() = runBlocking {
        val input = File(directory, "large-fiction.bin")
        val buffer = ByteArray(TRANSFER_BUFFER_SIZE) { (it * 17).toByte() }
        input.outputStream().use { output -> repeat(2048) { output.write(buffer) } }
        val path = RemotePath.source("content://fake/large", listOf("nested", "large.bin"))
        var uploads = 0
        val counted = object : SafeRemoteTransport by remote {
            override suspend fun uploadExclusive(path: RemotePath, file: File) {
                uploads++; remote.uploadExclusive(path, file)
            }
        }
        val journal = Journal()
        val result = SafeFilePublisher("loopback-fiction", counted, journal).publish(path, input)
        assertTrue("$result", result is NasResult.Success)
        val local = input.inputStream().use { FileFingerprint.read(it) }
        assertEquals(128L * 1024 * 1024, local.size)
        assertEquals(local, File(share, path.value).inputStream().use { FileFingerprint.read(it) })
        assertTrue(SafeFilePublisher("loopback-fiction", counted, journal).publish(path, input) is NasResult.Skipped)
        assertEquals(1, uploads); assertTrue(journal.records.values.single().published)
    }
    @Test fun nestedHomonymsPublishOnceAndPreserveFlatLegacyFile() = runBlocking {
        val input=File(directory,"source.txt").apply { writeText("fictitious SMB data") }
        File(share,"same.txt").writeText("legacy untouched")
        val publisher=SafeFilePublisher("loopback-fiction",remote,Journal())
        for(source in listOf("content://fake/a","content://fake/b")) {
            val path=RemotePath.source(source,listOf("nested folder","same.txt"))
            assertTrue(publisher.publish(path,input) is NasResult.Success)
            assertArrayEquals(input.readBytes(),File(share,path.value).readBytes())
            assertTrue(publisher.publish(path,input) is NasResult.Skipped)
        }
        assertEquals("legacy untouched",File(share,"same.txt").readText())
    }
    @Test fun exclusiveCreateAndRenameRefuseExistingTargetsAtServer() = runBlocking {
        val input=File(directory,"source.txt").apply { writeText("new fictitious content") }
        val final=RemotePath.source("content://fake/a",listOf("nested","same.txt"))
        remote.verifyPublicationSupport();remote.ensureParents(final)
        val temporary=final.temporary()
        remote.uploadExclusive(temporary,input)
        try { remote.uploadExclusive(temporary,input);fail("Must reject existing temporary") } catch (_: jcifs.smb.SmbException) { }
        assertEquals("new fictitious content",File(share,temporary.value).readText())
        File(share,final.value).writeText("external final")
        try { remote.publishNoReplace(temporary,final); fail("Must not replace final") } catch (_: SyncConflict) { }
        assertEquals("external final",File(share,final.value).readText())
        assertTrue(File(share,temporary.value).exists())
    }
    @Test fun incompleteRemoteTemporaryDoesNotPublishFinal() = runBlocking {
        val input=File(directory,"source.txt").apply { writeText("fictitious complete content") }
        val truncated=object : SafeRemoteTransport by remote {
            override suspend fun uploadExclusive(path: RemotePath,file: File) {
                remote.uploadExclusive(path,file)
                File(share,path.value).writeText("incomplete")
            }
        }
        val path=RemotePath.source("content://fake/a",listOf("nested","same.txt"))
        assertTrue(SafeFilePublisher("loopback-fiction",truncated,Journal()).publish(path,input) is NasResult.Failure)
        assertFalse(File(share,path.value).exists())
        assertTrue(File(share,path.value).parentFile!!.listFiles()!!.any { it.name.endsWith(".part") })
    }
}

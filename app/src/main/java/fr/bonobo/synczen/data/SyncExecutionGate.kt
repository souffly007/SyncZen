package fr.bonobo.synczen.data

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** Shared by manual/periodic workers. OS lock is released on cancellation or process death. */
internal class SyncExecutionGate private constructor(
    private val file: RandomAccessFile, private val lock: FileLock
) : Closeable {
    override fun close() { try { lock.release() } finally { file.close() } }
    companion object {
        fun acquire(path: File): SyncExecutionGate? {
            val file = RandomAccessFile(path, "rw")
            try {
                val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) { file.close(); return null }
                return SyncExecutionGate(file, lock)
            } catch (e: Exception) { file.close(); throw e }
        }
    }
}

package com.haseebullahjan.roshd.core

import org.junit.Assert.*
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class EvidenceArchiveTest {
    @Test fun legacyGzipEvidenceRestoresExactBytes() {
        val folder=kotlin.io.path.createTempDirectory("yse-archive-test").toFile()
        try {
            val bytes=ByteArray(20000) { (it%251).toByte() };val hash=sha256(bytes)
            val compressed=java.io.ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()
            val source=File(folder,"old.ysheet")
            DataOutputStream(source.outputStream()).use { it.writeBytes("YSESHT01");it.writeByte(1);it.writeLong(bytes.size.toLong());it.write(hash.chunked(2).map { part -> part.toInt(16).toByte() }.toByteArray());it.write(compressed) }
            val target=EvidenceArchive.restore(source,File(folder,"restored.jpg"),hash)
            assertArrayEquals(bytes,target.readBytes());assertTrue(source.exists())
            assertThrows(IllegalArgumentException::class.java) { EvidenceArchive.restore(source,File(folder,"wrong.jpg"),"0".repeat(64)) }
        } finally { folder.deleteRecursively() }
    }
}

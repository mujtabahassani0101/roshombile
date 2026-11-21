package com.haseebullahjan.roshd

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Properties
import java.util.zip.Deflater
import org.junit.Assert.*
import org.junit.Test

class NoticeJweCipherTest {
    private val vector = Properties().apply {
        NoticeJweCipherTest::class.java.classLoader!!.getResourceAsStream(
            "mobile-notice-independent-vector.properties")!!.use { load(it) }
    }
    private fun bytes(key: String) = Base64.getDecoder().decode(vector.getProperty(key))
    private val privateKey get() = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes("privateKey")))
    private fun decode(parts: Map<String, ByteArray> = emptyMap(), wrongKey: Boolean = false): ByteArray {
        fun part(name: String) = parts[name] ?: bytes(name)
        val key = if (wrongKey) KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private else privateKey
        return NoticeJweCipher.decrypt(key, part("encryptedKey"), part("iv"), part("ciphertext"),
            part("tag"), part("protectedAscii"))
    }
    private fun fails(action: () -> Unit) {
        var failed = false
        try { action() } catch (_: Exception) { failed = true }
        assertTrue("Must fail without exposing plaintext", failed)
    }
    @Test fun pythonVectorUsesExactOaepSha256MgfSha256AadAndRawDeflate() {
        val plain = decode()
        assertArrayEquals(bytes("plain"), plain)
        assertTrue(String(plain, Charsets.UTF_8).contains("شاگرد آزمایشی"))
        plain.fill(0)
    }
    @Test fun backendSingleAndBothBatchVectorsUseProductionCipher() {
        for (name in listOf("single", "batch.page-1", "batch.page-2")) {
            val fixture = Properties().apply {
                NoticeJweCipherTest::class.java.classLoader!!.getResourceAsStream("mobile-notice-backend-$name.properties")!!.use { load(it) }
            }
            fun b(k: String) = Base64.getDecoder().decode(fixture.getProperty(k))
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(b("privateKey")))
            val plain = NoticeJweCipher.decrypt(key,b("encryptedKey"),b("iv"),b("ciphertext"),b("tag"),b("protectedAscii"))
            assertArrayEquals(b("plain"),plain)
            for (component in listOf("encryptedKey", "iv", "ciphertext", "tag", "protectedAscii")) {
                val bad=b(component).also { it[it.size/2]=(it[it.size/2].toInt() xor 1).toByte() }
                fails { NoticeJweCipher.decrypt(key,if(component=="encryptedKey")bad else b("encryptedKey"),if(component=="iv")bad else b("iv"),if(component=="ciphertext")bad else b("ciphertext"),if(component=="tag")bad else b("tag"),if(component=="protectedAscii")bad else b("protectedAscii")) }
            }
            plain.fill(0)
        }
    }
    @Test fun everyAuthenticatedComponentRejectsTampering() {
        for (name in listOf("encryptedKey", "iv", "ciphertext", "tag", "protectedAscii")) {
            val mutated = bytes(name).also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
            fails { decode(mapOf(name to mutated)) }
        }
    }
    @Test fun wrongPrivateKeyNeverReturnsPlaintext() { fails { decode(wrongKey = true) } }
    @Test fun incorrectIvTagAndRsaCiphertextSizesFail() {
        for ((part, size) in listOf("iv" to 11, "tag" to 15, "encryptedKey" to 255)) {
            fails { decode(mapOf(part to ByteArray(size))) }
        }
    }
    private fun compress(plain: ByteArray, raw: Boolean = true): ByteArray {
        val deflater = Deflater(6, raw)
        try {
            deflater.setInput(plain); deflater.finish()
            val output = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (!deflater.finished()) output.write(chunk, 0, deflater.deflate(chunk))
            return output.toByteArray()
        } finally { deflater.end() }
    }
    @Test fun inflateHasBoundedOutputAndRejectsWrappersTruncationAndTrailingBytes() {
        fails { NoticeJweCipher.inflate(compress(ByteArray(NoticeJweCipher.MAX_PLAINTEXT + 1))) }
        fails { NoticeJweCipher.inflate(compress("{}".toByteArray(), raw = false)) }
        val compressed = compress(bytes("plain"))
        fails { NoticeJweCipher.inflate(compressed.copyOf(compressed.size - 1)) }
        fails { NoticeJweCipher.inflate(compressed + byteArrayOf(0)) }
        assertEquals(NoticeJweCipher.MAX_PLAINTEXT,
            NoticeJweCipher.inflate(compress(ByteArray(NoticeJweCipher.MAX_PLAINTEXT))).size)
    }
}

package com.haseebullahjan.roshd

import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** No Android dependencies: the production primitives also run in JVM tests. */
internal object NoticeJweCipher {
    const val MAX_PLAINTEXT = 4 * 1024 * 1024
    const val MAX_ENVELOPE = 6 * 1024 * 1024
    val oaep: OAEPParameterSpec get() = OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
    )

    fun unwrap(key: PrivateKey, encryptedKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, oaep)
        return cipher.doFinal(encryptedKey)
    }

    fun decrypt(
        key: PrivateKey, encryptedKey: ByteArray, iv: ByteArray,
        ciphertext: ByteArray, tag: ByteArray, protectedAscii: ByteArray
    ): ByteArray {
        require(encryptedKey.size in listOf(256, 384, 512))
        require(iv.size == 12 && tag.size == 16)
        require(ciphertext.size in 1..MAX_ENVELOPE)
        require(protectedAscii.size in 1..4096 && protectedAscii.all { it.toInt() in 0..127 })
        val cek = unwrap(key, encryptedKey)
        var compressed: ByteArray? = null
        val combined = ciphertext + tag
        try {
            require(cek.size == 32)
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, iv))
            aes.updateAAD(protectedAscii)
            // doFinal authenticates before any plaintext is passed to inflate.
            compressed = aes.doFinal(combined)
            return inflate(compressed)
        } finally {
            cek.fill(0)
            compressed?.fill(0)
            combined.fill(0)
        }
    }

    private class WipingOutput : ByteArrayOutputStream() {
        fun wipe() { buf.fill(0); reset() }
    }

    internal fun inflate(compressed: ByteArray): ByteArray {
        val inflater = Inflater(true) // RFC 7516 zip=DEF: raw, no zlib/gzip header.
        val output = WipingOutput()
        val chunk = ByteArray(8192)
        try {
            inflater.setInput(compressed)
            while (!inflater.finished()) {
                val count = inflater.inflate(chunk)
                require(output.size().toLong() + count <= MAX_PLAINTEXT)
                if (count > 0) output.write(chunk, 0, count)
                else require(inflater.finished()) { "Invalid raw DEFLATE stream" }
            }
            require(inflater.remaining == 0 && output.size() > 0)
            return output.toByteArray()
        } finally {
            inflater.end()
            chunk.fill(0)
            output.wipe()
        }
    }
}

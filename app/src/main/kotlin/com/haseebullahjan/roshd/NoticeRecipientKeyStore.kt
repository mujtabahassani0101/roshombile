package com.haseebullahjan.roshd

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Dedicated RSA identities; never shares scanner signing/agreement aliases. */
internal class NoticeRecipientKeyStore(private val directory: File) {
    data class Identity(val pair: KeyPair, val storage: String) {
        val bits get() = (pair.public as RSAPublicKey).modulus.bitLength()
    }
    companion object {
        private const val WRAP_ALIAS = "yse.mobile.notice.wrapping.aes.v1"
        private const val MAGIC = 0x594e4b31
        private val lock = Any()
    }
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(scope: String): String {
        require(Regex("^[0-9a-f]{64}$").matches(scope))
        return "yse.mobile.notice.rsa.v1.$scope"
    }
    private fun file(scope: String): AtomicFile {
        alias(scope)
        return AtomicFile(File(directory, "$scope.rsa"))
    }
    private fun stored(file: AtomicFile) = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    fun get(scope: String, create: Boolean = true): Identity = synchronized(lock) {
        val name = alias(scope)
        val file = file(scope)
        if (stored(file)) return@synchronized Identity(read(file, name), "keystore-wrapped-rsa")
        val ks = store()
        if (ks.containsAlias(name)) {
            val pair = KeyPair(ks.getCertificate(name).publicKey, ks.getKey(name, null) as PrivateKey)
            probe(pair)
            return@synchronized Identity(pair, "android-keystore-rsa")
        }
        check(create) { "Notice key is unavailable" }
        // Providers on some Android versions accept OAEP-SHA256 but cannot use
        // MGF1-SHA256. Probe the exact algorithm; never fall back to SHA-1.
        for (bits in listOf(3072, 2048)) {
            try {
                val spec = KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(bits).setDigests(KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setUserAuthenticationRequired(false)
                if (Build.VERSION.SDK_INT >= 35) spec.setMgf1Digests(KeyProperties.DIGEST_SHA256)
                val generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
                generator.initialize(spec.build())
                val pair = generator.generateKeyPair()
                probe(pair)
                return@synchronized Identity(pair, "android-keystore-rsa")
            } catch (_: java.security.GeneralSecurityException) {
                if (ks.containsAlias(name)) ks.deleteEntry(name)
            } catch (_: java.security.ProviderException) {
                if (ks.containsAlias(name)) ks.deleteEntry(name)
            }
        }
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(3072)
        val pair = generator.generateKeyPair()
        probe(pair)
        write(file, name, pair)
        Identity(read(file, name), "keystore-wrapped-rsa")
    }

    /** Explicit recovery only. An unreadable existing key is never overwritten by get(). */
    fun replace(scope: String): Identity = synchronized(lock) {
        file(scope).delete()
        store().deleteEntry(alias(scope))
        get(scope)
    }

    private fun probe(pair: KeyPair) {
        val plain = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pair.public, NoticeJweCipher.oaep)
        val decoded = NoticeJweCipher.unwrap(pair.private, cipher.doFinal(plain))
        try { check(java.security.MessageDigest.isEqual(plain, decoded)) }
        finally { plain.fill(0); decoded.fill(0) }
    }

    private fun wrapKey(create: Boolean): SecretKey {
        val ks = store()
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        check(create && !ks.containsAlias(WRAP_ALIAS)) { "Wrapping key is unavailable" }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(WRAP_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun write(file: AtomicFile, name: String, pair: KeyPair) {
        val privateBytes = pair.private.encoded
        val publicBytes = pair.public.encoded
        val plain = java.nio.ByteBuffer.allocate(8 + publicBytes.size + privateBytes.size)
            .putInt(publicBytes.size).put(publicBytes).putInt(privateBytes.size).put(privateBytes).array()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrapKey(true))
            cipher.updateAAD(name.toByteArray(Charsets.US_ASCII))
            val encrypted = cipher.doFinal(plain)
            check(directory.isDirectory || directory.mkdirs())
            val stream = file.startWrite()
            try {
                DataOutputStream(stream).apply { writeInt(MAGIC); write(cipher.iv); write(encrypted); flush() }
                file.finishWrite(stream)
            } catch (error: Exception) { file.failWrite(stream); throw error }
        } finally {
            privateBytes.fill(0); plain.fill(0)
        }
    }

    private fun read(file: AtomicFile, name: String): KeyPair {
        val encrypted = file.openRead().use {
            require(it.channel.size() in 33..16384)
            it.readBytes()
        }
        val input = DataInputStream(ByteArrayInputStream(encrypted))
        require(input.readInt() == MAGIC)
        val iv = ByteArray(12).also { input.readFully(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapKey(false), GCMParameterSpec(128, iv))
        cipher.updateAAD(name.toByteArray(Charsets.US_ASCII))
        val plain = cipher.doFinal(input.readBytes())
        var privateBytes: ByteArray? = null
        try {
            val data = DataInputStream(ByteArrayInputStream(plain))
            val publicSize = data.readInt()
            require(publicSize in 250..800)
            val publicBytes = ByteArray(publicSize).also { data.readFully(it) }
            val privateSize = data.readInt()
            require(privateSize in 1000..4096 && privateSize == data.available())
            privateBytes = ByteArray(privateSize).also { data.readFully(it) }
            val factory = KeyFactory.getInstance("RSA")
            val pair = KeyPair(factory.generatePublic(X509EncodedKeySpec(publicBytes)),
                factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)))
            require((pair.public as RSAPublicKey).modulus.bitLength() in listOf(2048, 3072, 4096))
            probe(pair)
            return pair
        } finally { plain.fill(0); privateBytes?.fill(0) }
    }
}

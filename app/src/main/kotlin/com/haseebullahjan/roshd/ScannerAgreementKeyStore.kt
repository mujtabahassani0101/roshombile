package com.haseebullahjan.roshd

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** P-256 agreement on API 24+. Pre-31 keys are wrapped by Android Keystore AES.
 * Public keys alone cross the Kotlin service boundary. Existing software keys remain in
 * use after an OS upgrade; unreadable keys must never be silently replaced.
 */
internal class ScannerAgreementKeyStore(private val directory: File) {
    companion object {
        private const val WRAPPING_ALIAS = "roshd.scanner.ecdh.wrapping.v1"
        private const val MAGIC = 0x59534531
        private val lock = Any()
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun file(alias: String): AtomicFile {
        val name = MessageDigest.getInstance("SHA-256")
            .digest(alias.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$name.ecdh"))
    }

    fun contains(alias: String): Boolean = synchronized(lock) {
        hasStored(file(alias)) || keyStore().containsAlias(alias)
    }

    private fun hasStored(stored: AtomicFile) = stored.baseFile.exists() ||
        File(stored.baseFile.path + ".bak").exists()

    fun get(alias: String, create: Boolean = true): KeyPair = synchronized(lock) {
        val stored = file(alias)
        if (hasStored(stored)) return@synchronized readWrapped(stored, alias)
        val store = keyStore()
        if (store.containsAlias(alias)) {
            val certificate = store.getCertificate(alias)
                ?: error("Agreement certificate is unavailable")
            val privateKey = store.getKey(alias, null) as? PrivateKey
                ?: error("Agreement key is unavailable")
            return@synchronized KeyPair(certificate.publicKey, privateKey)
        }
        check(create) { "Agreement identity is unavailable" }
        if (Build.VERSION.SDK_INT >= 31) {
            val generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setUserAuthenticationRequired(false).build()
            )
            return@synchronized generator.generateKeyPair()
        }
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val privateBytes = pair.private.encoded
        val publicBytes = pair.public.encoded
        val plain = ByteBuffer.allocate(8 + publicBytes.size + privateBytes.size)
            .putInt(publicBytes.size).put(publicBytes)
            .putInt(privateBytes.size).put(privateBytes).array()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(create = true))
            cipher.updateAAD(aad(alias))
            val encrypted = cipher.doFinal(plain)
            check(directory.isDirectory || directory.mkdirs())
            val output = stored.startWrite()
            try {
                val data = DataOutputStream(output)
                data.writeInt(MAGIC)
                data.write(cipher.iv)
                data.write(encrypted)
                data.flush()
                stored.finishWrite(output)
            } catch (error: Exception) {
                stored.failWrite(output)
                throw error
            }
            // Verify durable bytes before advertising this identity to server.
            readWrapped(stored, alias)
        } finally {
            privateBytes.fill(0)
            plain.fill(0)
        }
    }

    private fun aad(alias: String) = "yse.local.ecdh-key.v1\n$alias".toByteArray(Charsets.UTF_8)

    private fun readWrapped(stored: AtomicFile, alias: String): KeyPair {
        val envelope = stored.openRead().use { it.readBytes() }
        require(envelope.size in 33..4096) { "Invalid wrapped agreement key" }
        val input = DataInputStream(ByteArrayInputStream(envelope))
        require(input.readInt() == MAGIC)
        val nonce = ByteArray(12).also { input.readFully(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(create = false), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(alias))
        val plain = cipher.doFinal(input.readBytes())
        var privateBytes: ByteArray? = null
        try {
            val data = DataInputStream(ByteArrayInputStream(plain))
            val publicSize = data.readInt()
            require(publicSize == 91)
            val publicBytes = ByteArray(publicSize).also { data.readFully(it) }
            val privateSize = data.readInt()
            require(privateSize in 32..1024 && privateSize == data.available())
            privateBytes = ByteArray(privateSize).also { data.readFully(it) }
            val factory = KeyFactory.getInstance("EC")
            return KeyPair(
                factory.generatePublic(X509EncodedKeySpec(publicBytes)),
                factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes))
            )
        } finally {
            privateBytes?.fill(0)
            plain.fill(0)
        }
    }

    private fun wrappingKey(create: Boolean): SecretKey {
        val store = keyStore()
        (store.getKey(WRAPPING_ALIAS, null) as? SecretKey)?.let { return it }
        check(create && !store.containsAlias(WRAPPING_ALIAS)) { "Wrapping key is unavailable" }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAPPING_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build()
        )
        return generator.generateKey()
    }
}

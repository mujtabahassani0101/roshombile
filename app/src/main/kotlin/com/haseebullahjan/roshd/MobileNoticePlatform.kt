package com.haseebullahjan.roshd

import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.haseebullahjan.roshd.platform.NativeCall
import com.haseebullahjan.roshd.platform.NativeResult
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

internal class MobileNoticePlatform(directory: File) {
    private val keys = NoticeRecipientKeyStore(directory)
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    fun close() { executor.shutdownNow() }
    fun handle(call: NativeCall, result: NativeResult) {
        executor.execute {
            try {
                val scope = call.argument<String>("scope") ?: error("Missing scope")
                when (call.method) {
                    "mobileNoticeIdentity", "mobileNoticeRecoverIdentity" -> {
                        val identity = if (call.method == "mobileNoticeRecoverIdentity") keys.replace(scope) else keys.get(scope)
                        val publicBytes = identity.pair.public.encoded
                        val value = mapOf("publicKeySpki" to Base64.encodeToString(publicBytes, Base64.NO_WRAP),
                            "bits" to identity.bits, "storage" to identity.storage)
                        main.post { result.success(value) }
                    }
                    "mobileNoticeDecrypt" -> {
                        val identity = try { keys.get(scope, create = false) }
                        catch (_: Exception) {
                            main.post { result.error("notice_key_unavailable", "Notice key is unavailable.", null) }
                            return@execute
                        }
                        val kid = Base64.encodeToString(MessageDigest.getInstance("SHA-256")
                            .digest(identity.pair.public.encoded), Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
                        require(kid == call.argument<String>("kid"))
                        val plain = NoticeJweCipher.decrypt(identity.pair.private,
                            call.argument<ByteArray>("encryptedKey") ?: error("Missing encrypted key"),
                            call.argument<ByteArray>("iv") ?: error("Missing iv"),
                            call.argument<ByteArray>("ciphertext") ?: error("Missing ciphertext"),
                            call.argument<ByteArray>("tag") ?: error("Missing tag"),
                            call.argument<ByteArray>("protectedAscii") ?: error("Missing protected header"))
                        main.post { try { result.success(plain) } finally { plain.fill(0) } }
                    }
                    else -> main.post { result.notImplemented() }
                }
            } catch (_: Exception) {
                // Deliberately no exception message, private data or stack trace.
                val code = if (call.method == "mobileNoticeDecrypt") "notice_integrity_invalid" else "notice_key_unavailable"
                main.post { result.error(code, "Notice cryptographic operation failed.", null) }
            }
        }
    }
}

package com.haseebullahjan.roshd

import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity

import com.haseebullahjan.roshd.platform.NativeCall
import com.haseebullahjan.roshd.platform.NativeResult
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.zip.ZipFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

open class NativeHostActivity : ComponentActivity() {
    companion object {
        private const val CHANNEL = "com.haseebullahjan.roshd/platform"
        private const val PICK_DOCUMENTS = 7214
        private const val SAVE_DOCUMENT = 7216
        private const val CAPTURE_PHOTO_BASE = 10000
        private val captureSequence = AtomicInteger(0)
        private const val SECURE_ALIAS = "roshd.secure.storage.aes.v1"
        private const val SECURE_PREFERENCES = "roshd_secure_values"
        private val ALLOWED_MIME_TYPES = arrayOf(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/heic",
            "image/heif"
        )
        private val STUDENT_FILE_MIME_TYPES = arrayOf(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "text/csv"
        )
    }

    private var pendingPickerResult: NativeResult? = null
    private var pickerTargetDirectory: File? = null
    private var pickerMaxBytes: Long = 50L * 1024L * 1024L
    private var pickerMimeTypes = ALLOWED_MIME_TYPES
    private data class CaptureRequest(
        val result: NativeResult,
        val targetDirectory: File,
        val maxBytes: Long,
        val activityCode: Int,
        var cameraFile: File? = null,
        private val replied: AtomicBoolean = AtomicBoolean(false)
    ) {
        fun success(value: Map<String, Any>?) {
            if (replied.compareAndSet(false, true)) result.success(value)
        }

        fun error(code: String, message: String) {
            if (replied.compareAndSet(false, true)) result.error(code, message, null)
        }
    }

    private var pendingCapture: CaptureRequest? = null
    private var pendingSaveResult: NativeResult? = null
    private var saveSource: File? = null
    private val mobileNoticesDelegate=lazy { MobileNoticePlatform(File(noBackupFilesDir, "mobile-notice-keys")) }
    private val mobileNotices by mobileNoticesDelegate
    private val noticePagesDelegate=lazy { NoticePagePlatform(this) }
    private val noticePages by noticePagesDelegate
    private val appUpdatesDelegate=lazy { AppUpdatePlatform(this) }
    private val appUpdates by appUpdatesDelegate
    private val protectedStorageKey by lazy { loadSecureKey() }
    private val secureStorageWorker = Executors.newSingleThreadExecutor()
    // ECDSA signing, identity creation and offline grading hit Android Keystore (an IPC to
    // the TEE/StrongBox). Keep them off the UI thread, serialized and separate from storage.
    private val cryptoWorker = Executors.newSingleThreadExecutor()
    // Cache only after a successful protected read/commit. Match the encrypted
    // envelope too, so a newer stored value never returns an earlier plaintext.
    private val protectedValueCache = object : LinkedHashMap<String, Pair<String, String>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>): Boolean =
            size > 128
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(true)
        }
    }

    override fun onDestroy() {
        if(appUpdatesDelegate.isInitialized())appUpdates.close()
        if(mobileNoticesDelegate.isInitialized())mobileNotices.close()
        if(noticePagesDelegate.isInitialized())noticePages.close()
        cancelCapture()
        secureStorageWorker.shutdown()
        cryptoWorker.shutdown()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
    }

    fun nativeCall(call: NativeCall, result: NativeResult) {
        runOnUiThread {
            when {
                call.method.startsWith("appUpdate.") -> appUpdates.handle(NativeCall(call.method.removePrefix("appUpdate."), call.arguments), result)
                else -> handleMethod(call, result)
            }
        }
    }

    private fun handleMethod(call: NativeCall, result: NativeResult) {
        when (call.method) {
            "mobileNoticeIdentity", "mobileNoticeRecoverIdentity", "mobileNoticeDecrypt" -> mobileNotices.handle(call, result)
            "noticeRenderPage", "noticeCancelRender" -> noticePages.handle(call, result)
            "setSecureFlag" -> {
                val enabled = call.argument<Boolean>("enabled") != false
                if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                result.success(null)
            }
            "secureWrite", "secureRead", "secureDelete" -> secureStorageWorker.execute {
                // Keystore access and durable preference commits must not block
                // the Android UI thread. Serialize them with the value cache.
                val reply = object : NativeResult {
                    override fun success(value: Any?) {
                        runOnUiThread { result.success(value) }
                    }
                    override fun error(code: String, message: String?, details: Any?) {
                        runOnUiThread { result.error(code, message, details) }
                    }
                    override fun notImplemented() {
                        runOnUiThread { result.notImplemented() }
                    }
                }
                try {
                    when (call.method) {
                        "secureWrite" -> secureWrite(call, reply)
                        "secureRead" -> secureRead(call, reply)
                        "secureDelete" -> secureDelete(call, reply)
                        else -> reply.notImplemented()
                    }
                } catch (_: Exception) {
                    reply.error("secure_storage_failed", "Protected local storage is unavailable.", null)
                }
            }
            "p256Identity" -> onCryptoWorker(result) { p256Identity(call, it) }
            "registrationMetadata" -> {
                @Suppress("DEPRECATION")
                val info = packageManager.getPackageInfo(packageName, 0)
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
                result.success(mapOf("platform" to "android",
                    "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}".take(192),
                    "appVersion" to "${info.versionName}+$code"))
            }
            "p256Sign" -> onCryptoWorker(result) { p256Sign(call, it) }
            "p256Verify" -> p256Verify(call, result)
            "offlineCorrectCount" -> onCryptoWorker(result) { offlineCorrectCount(call, it) }
            "shareFile" -> shareFile(call, result)
            "openPdf" -> openPdf(call, result)
            "saveFile" -> saveFile(call, result)
            "pickDocuments" -> openDocumentPicker(call, result)
            "captureDocument" -> captureDocument(call, result)
            "stageScannerImage" -> stageScannerImage(call, result)
            "inspectDocumentImage" -> inspectDocumentImage(call, result)
            "cancelCapture" -> { cancelCapture(); result.success(null) }
            "rasterizeDocument" -> rasterizeDocument(call, result)
            "prepareOmrImages" -> prepareOmrImages(call, result)
            else -> result.notImplemented()
        }
    }

    private fun onCryptoWorker(result: NativeResult, work: (NativeResult) -> Unit) {
        val reply = object : NativeResult {
            override fun success(value: Any?) { runOnUiThread { result.success(value) } }
            override fun error(code: String, message: String?, details: Any?) { runOnUiThread { result.error(code, message, details) } }
            override fun notImplemented() { runOnUiThread { result.notImplemented() } }
        }
        try {
            cryptoWorker.execute {
                try { work(reply) } catch (_: Exception) { reply.error("crypto_failed", "Cryptographic operation failed.", null) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            result.error("crypto_unavailable", "The app is closing.", null)
        }
    }

    private fun openPdf(call: NativeCall, result: NativeResult) {
        val file = call.argument<String>("path")?.let(::File)
        val root = File(filesDir, "manager-files/notices").canonicalFile
        if (file == null || !file.isFile || !file.canonicalPath.startsWith(root.path + File.separator) ||
            !file.name.endsWith(".pdf", ignoreCase = true)) {
            result.error("notice_file_invalid", "PDF file is unavailable.", null)
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("PDF", uri)
            }
            startActivity(Intent.createChooser(intent, "بازکردن اطلاع‌نامه"))
            result.success(null)
        } catch (_: Exception) {
            result.error("pdf_viewer_unavailable", "A PDF viewer is unavailable.", null)
        }
    }

    private fun shareFile(call: NativeCall, result: NativeResult) {
        val source = call.argument<String>("path")?.let(::File)?.canonicalFile
        val allowed = listOf(File(noBackupFilesDir,"native-media"),File(filesDir,"native-reports"),
            File(filesDir,"manager-files/notices"),File(filesDir,"protected-exports"),File(filesDir,"staged-imports"),File(cacheDir,"native-share"))
        if (source == null || !source.isFile || allowed.none { source.path.startsWith(it.canonicalPath + File.separator) }) {
            result.error("share_file_missing", "فایل این حساب برای اشتراک‌گذاری موجود نیست.", null)
            return
        }
        secureStorageWorker.execute {
            try {
                val folder = File(cacheDir,"native-share").apply { mkdirs() }
                val name = source.name.replace(Regex("[^A-Za-z0-9._-]"),"_")
                val copy = File(folder,"${UUID.randomUUID()}-$name")
                source.copyTo(copy,overwrite=false)
                runOnUiThread {
                    try {
                        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", copy)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = call.argument<String>("mimeType") ?: "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM,uri)
                            call.argument<String>("text")?.let { putExtra(Intent.EXTRA_TEXT,it) }
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            clipData = ClipData.newRawUri("document",uri)
                        }
                        startActivity(Intent.createChooser(intent,"رشد"))
                        result.success(null)
                    } catch (_:Exception) { result.error("share_failed","اشتراک‌گذاری باز نشد؛ فایل اصلی محفوظ است.",null) }
                }
            } catch (_:Exception) { runOnUiThread { result.error("share_failed","آماده‌سازی فایل کامل نشد؛ فایل اصلی محفوظ است.",null) } }
        }
    }

    private fun saveFile(call: NativeCall, result: NativeResult) {
        if (pendingSaveResult != null) {
            result.error("save_busy", "A save operation is already open.", null)
            return
        }
        val file = call.argument<String>("path")?.let(::File)
        if (file == null || !file.exists()) {
            result.error("save_file_missing", "The file is no longer available.", null)
            return
        }
        pendingSaveResult = result
        saveSource = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = call.argument<String>("mimeType") ?: "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, call.argument<String>("fileName") ?: file.name)
        }
        startActivityForResult(intent, SAVE_DOCUMENT)
    }

    private fun captureDocument(call: NativeCall, result: NativeResult) {
        if (pendingCapture != null) {
            result.error(
                "scanner_busy",
                "یک اسکن در جریان است؛ ابتدا آن را تمام یا لغو کنید.",
                null
            )
            return
        }
        val directory = call.argument<String>("targetDirectory")?.let(::File)
        if (directory == null) {
            result.error("target_missing", "The private import directory is missing.", null)
            return
        }
        directory.mkdirs()
        val request = CaptureRequest(
            result = result,
            targetDirectory = directory,
            maxBytes = call.argument<Number>("maxBytes")?.toLong()
                ?: 50L * 1024L * 1024L,
            activityCode = CAPTURE_PHOTO_BASE + (captureSequence.getAndIncrement() and 0xFFF)
        )
        pendingCapture = request
        launchOfflineCamera(request)
    }

    // Delegate full-resolution capture to the installed camera app. No Google
    // module download or network connection is needed, and bytes remain private.
    private fun launchOfflineCamera(request: CaptureRequest) {
        if (pendingCapture !== request) return
        try {
            val target = File(request.targetDirectory, "${UUID.randomUUID()}.jpg")
            target.createNewFile()
            request.cameraFile = target
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", target)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = ClipData.newRawUri("OMR photo", uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, request.activityCode)
        } catch (error: Exception) {
            request.cameraFile?.takeIf { it.length() == 0L }?.delete()
            finishCaptureError(request, "camera_unavailable", "دوربین گوشی باز نشد؛ تصویر را از فایل‌های گوشی انتخاب کنید.")
        }
    }

    private fun handleOfflineCameraResult(resultCode: Int) {
        val request = takeCaptureRequest() ?: return
        val target = request.cameraFile
        if (resultCode != Activity.RESULT_OK || target == null) {
            target?.takeIf { it.length() == 0L }?.delete()
            request.success(null)
            return
        }
        Thread {
            try {
                if (target.length() <= 0 || target.length() > request.maxBytes) {
                    throw IllegalArgumentException("اندازهٔ عکس معتبر نیست؛ عکس کوچک‌تر انتخاب کنید.")
                }
                validateSignature(target, "image/jpeg")
                runOnUiThread { request.success(mapOf(
                    "name" to "camera-${System.currentTimeMillis()}.jpg",
                    "path" to target.absolutePath, "mimeType" to "image/jpeg",
                    "size" to target.length(), "pageCount" to 1
                )) }
            } catch (error: Exception) {
                // A failed read must not erase a non-empty captured original.
                if (target.length() == 0L) target.delete()
                runOnUiThread { request.error("camera_capture_failed", "عکس کامل دریافت نشد؛ دوباره عکس بگیرید.") }
            }
        }.start()
    }

    private fun takeCaptureRequest(): CaptureRequest? {
        val request = pendingCapture ?: return null
        pendingCapture = null
        return request
    }

    private fun finishCaptureError(
        request: CaptureRequest,
        code: String,
        message: String
    ) {
        if (pendingCapture !== request) return
        pendingCapture = null
        request.error(code, message)
    }

    private fun secureKey(): SecretKey = protectedStorageKey

    private fun loadSecureKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(SECURE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                SECURE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun secureWrite(call: NativeCall, result: NativeResult) {
        val key = call.argument<String>("key")
        if (key.isNullOrBlank()) {
            result.error("secure_key_missing", "A secure-storage key is required.", null)
            return
        }
        val value = call.argument<String>("value")
        if (value == null) {
            val saved = getSharedPreferences(SECURE_PREFERENCES, MODE_PRIVATE).edit().remove(key).commit()
            if (saved) {
                protectedValueCache.remove(key)
                result.success(null)
            }
            else result.error("secure_write_failed", "Secure storage could not be committed.", null)
            return
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secureKey())
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val envelope = "v1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
            if (!getSharedPreferences(SECURE_PREFERENCES, MODE_PRIVATE).edit().putString(key, envelope).commit()) {
                throw IllegalStateException("Secure storage could not be committed")
            }
            protectedValueCache[key] = envelope to value
            result.success(null)
        } catch (error: Exception) {
            result.error("secure_write_failed", "Secure storage is unavailable.", null)
        }
    }

    private fun secureRead(call: NativeCall, result: NativeResult) {
        val key = call.argument<String>("key")
        if (key.isNullOrBlank()) {
            result.error("secure_key_missing", "A secure-storage key is required.", null)
            return
        }
        val envelope = getSharedPreferences(SECURE_PREFERENCES, MODE_PRIVATE).getString(key, null)
        if (envelope == null) {
            protectedValueCache.remove(key)
            result.success(null)
            return
        }
        protectedValueCache[key]?.let { cached ->
            if (cached.first == envelope) {
                result.success(cached.second)
                return
            }
        }
        try {
            val parts = envelope.split(':', limit = 3)
            if (parts.size != 3 || parts[0] != "v1") throw IllegalArgumentException("Invalid secure envelope")
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secureKey(), GCMParameterSpec(128, iv))
            val value = String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            protectedValueCache[key] = envelope to value
            result.success(value)
        } catch (error: Exception) {
            result.error("secure_read_failed", "Protected local data could not be read; it has not been deleted.", null)
        }
    }

    private fun secureDelete(call: NativeCall, result: NativeResult) {
        val key = call.argument<String>("key")
        if (!key.isNullOrBlank()) {
            if (!getSharedPreferences(SECURE_PREFERENCES, MODE_PRIVATE).edit().remove(key).commit()) {
                result.error("secure_delete_failed", "Secure storage could not be committed.", null)
                return
            }
            protectedValueCache.remove(key)
        }
        result.success(null)
    }

    /**
     * ECDSA stays in Android Keystore. ECDH uses a Keystore-wrapped key on
     * Android 7–11 and a Keystore agreement key on Android 12+.
     */
    private fun p256Identity(call: NativeCall, result: NativeResult) {
        val alias = call.argument<String>("alias")
        if (alias.isNullOrBlank() || !alias.startsWith("roshd.scanner.")) {
            result.error("key_alias_invalid", "The scanner key alias is invalid.", null)
            return
        }
        try {
            val agreementStore = ScannerAgreementKeyStore(File(noBackupFilesDir, "scanner-agreement"))
            val signingExists = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .containsAlias("$alias.signing")
            // Registered v1 identities are immutable on the server. Let the controller
            // drain old queues before activating a new UUID and both new keys.
            val upgradeRequired = signingExists && !agreementStore.contains("$alias.encryption")
            val encryption = if (upgradeRequired) null else agreementStore.get("$alias.encryption")
            val signing = getOrCreateP256SigningKey("$alias.signing")
            val signingSpki = signing.public.encoded
            val response = mutableMapOf<String, Any?>(
                "signingPublicKeySpki" to Base64.encodeToString(signingSpki, Base64.NO_WRAP),
                "signingPublicKeySha256" to sha256Hex(signingSpki),
                "algorithm" to "ECDSA-P256-SHA256",
                "encryptionSupported" to false,
                "encryptionUpgradeRequired" to upgradeRequired
            )
            if (encryption != null) {
                val encryptionSpki = encryption.public.encoded
                response["encryptionSupported"] = true
                response["encryptionPublicKeySpki"] = Base64.encodeToString(
                    encryptionSpki,
                    Base64.NO_WRAP
                )
                response["encryptionPublicKeySha256"] = sha256Hex(encryptionSpki)
            }
            result.success(response)
        } catch (error: Exception) {
            result.error(
                "keystore_identity_failed",
                "Android Keystore could not create the scanner identity.",
                null
            )
        }
    }

    private fun getOrCreateP256SigningKey(alias: String): KeyPair {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val certificate = keyStore.getCertificate(alias)
        val privateKey = keyStore.getKey(alias, null)
        if (certificate != null && privateKey != null) {
            return KeyPair(certificate.publicKey, privateKey as java.security.PrivateKey)
        }
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore"
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKeyPair()
    }

    private fun p256Sign(call: NativeCall, result: NativeResult) {
        val alias = call.argument<String>("alias")
        val dataBase64 = call.argument<String>("dataBase64")
        if (alias.isNullOrBlank() || dataBase64.isNullOrBlank()) {
            result.error("sign_input_invalid", "Signing input is missing.", null)
            return
        }
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val privateKey = keyStore.getKey("$alias.signing", null)
                as? java.security.PrivateKey
                ?: throw IllegalStateException("Signing key is unavailable")
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(privateKey)
            signer.update(Base64.decode(dataBase64, Base64.DEFAULT))
            val p1363 = derSignatureToP1363(signer.sign())
            result.success(Base64.encodeToString(p1363, Base64.NO_WRAP))
        } catch (error: Exception) {
            result.error("sign_failed", "The scanner message could not be signed.", null)
        }
    }

    private fun p256Verify(call: NativeCall, result: NativeResult) {
        val publicKeySpki = call.argument<String>("publicKeySpki")
        val dataBase64 = call.argument<String>("dataBase64")
        val signatureBase64 = call.argument<String>("signatureBase64")
        if (publicKeySpki.isNullOrBlank() ||
            dataBase64.isNullOrBlank() ||
            signatureBase64.isNullOrBlank()) {
            result.success(false)
            return
        }
        try {
            val publicKey = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.decode(publicKeySpki, Base64.DEFAULT))
            )
            val supplied = Base64.decode(signatureBase64, Base64.DEFAULT)
            val der = if (supplied.size == 64) p1363SignatureToDer(supplied) else supplied
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(Base64.decode(dataBase64, Base64.DEFAULT))
            result.success(verifier.verify(der))
        } catch (error: Exception) {
            result.success(false)
        }
    }

    /**
     * Decrypts the offline grading capsule entirely inside the Android layer
     * and returns only the permitted aggregate. Correct options and derived
     * key material never cross the native service boundary and are zeroed after use.
     */
    private fun offlineCorrectCount(call: NativeCall, result: NativeResult) {
        val alias = call.argument<String>("alias")
        val aad = call.argument<String>("aad")
        val ephemeralSpki = call.argument<String>("ephemeralPublicKeySpki")
        val saltBase64 = call.argument<String>("saltBase64")
        val nonceBase64 = call.argument<String>("nonceBase64")
        val ciphertextBase64 = call.argument<String>("ciphertextBase64")
        val tagBase64 = call.argument<String>("tagBase64")
        val expectedRecipientHash = call.argument<String>("recipientKeySha256")
        val expectedReleaseVersion = call.argument<String>("releaseVersion")
        val expectedSchema = call.argument<String>("schemaVersion") ?: "roshd.offline-correct-count.v1"
        val expectedAnswerKeyVersion = call.argument<String>("answerKeyVersion")
        val expectedQuestionCount = call.argument<Number>("questionCount")?.toInt()
        val observations = call.argument<List<Map<String, Any?>>>("observations")
        if (alias.isNullOrBlank() || aad == null || ephemeralSpki.isNullOrBlank() ||
            saltBase64.isNullOrBlank() || nonceBase64.isNullOrBlank() ||
            ciphertextBase64.isNullOrBlank() || tagBase64.isNullOrBlank() ||
            expectedRecipientHash.isNullOrBlank() || expectedReleaseVersion.isNullOrBlank() ||
            expectedQuestionCount == null || observations == null) {
            result.error("offline_count_invalid", "Offline grading input is incomplete.", null)
            return
        }
        var sharedSecret: ByteArray? = null
        var aesKeyBytes: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            require(expectedSchema in setOf("roshd.offline-correct-count.v1", "roshd.offline-correct-count.v2"))
            require(aad.split('\n').firstOrNull() == expectedSchema && !aad.endsWith('\n'))
            val encryption = ScannerAgreementKeyStore(File(noBackupFilesDir, "scanner-agreement"))
                .get("$alias.encryption", create = false)
            val privateKey = encryption.private
            val ownPublic = encryption.public.encoded
            if (sha256Hex(ownPublic) != expectedRecipientHash.lowercase()) {
                throw IllegalArgumentException("Offline grading recipient mismatch")
            }
            val remotePublic = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.decode(ephemeralSpki, Base64.DEFAULT))
            )
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(privateKey)
            agreement.doPhase(remotePublic, true)
            val generated = agreement.generateSecret()
            sharedSecret = when {
                generated.size == 32 -> generated
                generated.size < 32 -> ByteArray(32).also {
                    System.arraycopy(generated, 0, it, 32 - generated.size, generated.size)
                    generated.fill(0)
                }
                else -> generated.copyOfRange(generated.size - 32, generated.size).also {
                    generated.fill(0)
                }
            }
            val salt = Base64.decode(saltBase64, Base64.DEFAULT)
            if (salt.size != 32) throw IllegalArgumentException("Invalid HKDF salt")
            aesKeyBytes = hkdfSha256(sharedSecret, salt, aad.toByteArray(Charsets.UTF_8))
            salt.fill(0)
            val nonce = Base64.decode(nonceBase64, Base64.DEFAULT)
            val tag = Base64.decode(tagBase64, Base64.DEFAULT)
            val ciphertext = Base64.decode(ciphertextBase64, Base64.DEFAULT)
            if (nonce.size != 12 || tag.size != 16) {
                throw IllegalArgumentException("Invalid AES-GCM envelope")
            }
            val encrypted = ciphertext + tag
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKeyBytes, "AES"),
                GCMParameterSpec(128, nonce)
            )
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            plaintext = cipher.doFinal(encrypted)
            encrypted.fill(0)
            ciphertext.fill(0)
            tag.fill(0)
            nonce.fill(0)

            val decoded = JSONObject(String(plaintext, Charsets.UTF_8))
            if (decoded.optString("schemaVersion") != expectedSchema ||
                decoded.optString("releaseVersion") != expectedReleaseVersion ||
                (decoded.has("answerKeyVersion") && decoded.optString("answerKeyVersion") != expectedAnswerKeyVersion) ||
                decoded.optInt("questionCount", -1) != expectedQuestionCount) {
                throw IllegalArgumentException("Offline grading plaintext mismatch")
            }
            val v2 = expectedSchema == "roshd.offline-correct-count.v2"
            if (v2) require(decoded.optString("keySemanticsVersion") == "roshd.answer-choices.v2")
            val options = decoded.optJSONArray(if (v2) "acceptedOptions" else "correctOptions")
                ?: throw IllegalArgumentException("Accepted options are missing")
            if (options.length() != expectedQuestionCount || observations.size != expectedQuestionCount) {
                throw IllegalArgumentException("Offline grading question count mismatch")
            }
            val policy = if (!decoded.has("scoringPolicy") || decoded.isNull("scoringPolicy")) null else {
                val value = decoded.getJSONObject("scoringPolicy")
                require(value.getString("schemaVersion") == "roshd.points.v1" && value.getInt("version") == 1)
                fun decimal(field: String): java.math.BigDecimal {
                    val number = java.math.BigDecimal(value.get(field).toString())
                    require(number.precision() <= 32 && kotlin.math.abs(number.scale()) <= 18)
                    return number
                }
                OfflineScore.Policy(1, decimal("correctPoints"), decimal("incorrectPoints"), decimal("blankPoints"))
            }
            val values = List(options.length()) { index ->
                if (v2) options.getJSONArray(index).let { row -> List(row.length()) { row.get(it) } }
                else options.get(index)
            }
            val correctOptions = OfflineScore.parseOptions(values, expectedQuestionCount, v2)
            try {
                val score = if (v2) OfflineScore.aggregateAccepted(correctOptions, observations, policy)
                    else OfflineScore.aggregate(correctOptions.map { it.single() }.toIntArray(), observations, policy)
                val annotation=call.argument<Map<String,Any?>>("annotation")?.let { value ->
                    val request=JSONObject(value)
                    val record=request.getJSONObject("record")
                    require(record.getJSONObject("exam").getInt("questionCount")==expectedQuestionCount)
                    val recorded=com.haseebullahjan.roshd.core.ScanReview.observations(record,expectedQuestionCount)
                    require(recorded.size==observations.size && recorded.indices.all { index ->
                        recorded[index].getString("state")==observations[index]["state"] &&
                        (recorded[index].opt("selectedOption").takeUnless { it==JSONObject.NULL })==observations[index]["selectedOption"]
                    })
                    val grade=JSONObject(score).put("answersSha256",com.haseebullahjan.roshd.core.GradedSheet.answersHash(record))
                    val render=com.haseebullahjan.roshd.core.GradedSheet.request(record,grade,request.getJSONObject("sheet"),request.getString("outputPath"),correctOptions)
                    // The score box is drawn here (Persian text needs the platform
                    // fonts) and blended by the native renderer into the same JPEG.
                    val footer=java.io.File(request.getString("outputPath")+".score.rgba")
                    try {
                        render.remove("footerBox")?.let { it as JSONObject }?.let { box ->
                            runCatching {
                                val summary=com.haseebullahjan.roshd.core.GradedSheetFooter.summary(score,
                                    policy?.correct?.multiply(java.math.BigDecimal(expectedQuestionCount)),request.optBoolean("showPoints"))
                                com.haseebullahjan.roshd.core.GradedSheetFooter.write(this,summary,box.getInt("pixelWidth"),box.getInt("pixelHeight"),footer)
                                render.put("footer",JSONObject(box.toString()).put("path",footer.path))
                            }.onFailure { footer.delete() }
                        }
                        JSONObject(com.haseebullahjan.roshd.platform.OmrNative.annotateJson(render.toString())).also {
                            require(it.optBoolean("success")) { "Graded image could not be rendered" }
                        }
                    } finally { footer.delete() }
                }
                result.success(score +
                    mapOf("releaseVersion" to expectedReleaseVersion,"answerKeyVersion" to expectedAnswerKeyVersion)+
                    (annotation?.let { mapOf("annotationResult" to com.haseebullahjan.roshd.core.unwrap(it)) } ?: emptyMap()))
            } finally {
                correctOptions.forEach { it.fill(0) }
            }
        } catch (error: Exception) {
            result.error(
                "offline_count_failed",
                "The temporary offline count could not be verified.",
                null
            )
        } finally {
            sharedSecret?.fill(0)
            aesKeyBytes?.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun hkdfSha256(secret: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extract.doFinal(secret)
        return try {
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(prk, "HmacSHA256"))
            expand.doFinal(info + byteArrayOf(1)).copyOf(32)
        } finally {
            prk.fill(0)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun derSignatureToP1363(der: ByteArray): ByteArray {
        var offset = 0
        if (der[offset++].toInt() and 0xff != 0x30) {
            throw IllegalArgumentException("Invalid ECDSA DER sequence")
        }
        val sequenceLength = readDerLength(der, offset)
        offset = sequenceLength.second
        if (sequenceLength.first != der.size - offset) {
            throw IllegalArgumentException("Invalid ECDSA DER length")
        }
        if (der[offset++].toInt() and 0xff != 0x02) {
            throw IllegalArgumentException("Invalid ECDSA r integer")
        }
        val rLength = readDerLength(der, offset)
        offset = rLength.second
        val r = der.copyOfRange(offset, offset + rLength.first)
        offset += rLength.first
        if (der[offset++].toInt() and 0xff != 0x02) {
            throw IllegalArgumentException("Invalid ECDSA s integer")
        }
        val sLength = readDerLength(der, offset)
        offset = sLength.second
        val s = der.copyOfRange(offset, offset + sLength.first)
        val output = ByteArray(64)
        copyUnsignedInteger(r, output, 0)
        copyUnsignedInteger(s, output, 32)
        return output
    }

    private fun copyUnsignedInteger(source: ByteArray, target: ByteArray, targetOffset: Int) {
        var sourceOffset = 0
        while (sourceOffset < source.size - 1 && source[sourceOffset].toInt() == 0) {
            sourceOffset++
        }
        val length = source.size - sourceOffset
        if (length > 32) throw IllegalArgumentException("ECDSA integer is too large")
        System.arraycopy(source, sourceOffset, target, targetOffset + 32 - length, length)
    }

    private fun readDerLength(bytes: ByteArray, start: Int): Pair<Int, Int> {
        val first = bytes[start].toInt() and 0xff
        if (first < 0x80) return first to start + 1
        val count = first and 0x7f
        if (count < 1 || count > 2) throw IllegalArgumentException("Invalid DER length")
        var value = 0
        repeat(count) { index ->
            value = (value shl 8) or (bytes[start + 1 + index].toInt() and 0xff)
        }
        return value to start + 1 + count
    }

    private fun p1363SignatureToDer(signature: ByteArray): ByteArray {
        if (signature.size != 64) throw IllegalArgumentException("Invalid P1363 signature")
        val r = encodeDerInteger(signature.copyOfRange(0, 32))
        val s = encodeDerInteger(signature.copyOfRange(32, 64))
        val bodyLength = 2 + r.size + 2 + s.size
        return byteArrayOf(0x30, bodyLength.toByte(), 0x02, r.size.toByte()) +
            r + byteArrayOf(0x02, s.size.toByte()) + s
    }

    private fun encodeDerInteger(input: ByteArray): ByteArray {
        var first = 0
        while (first < input.size - 1 && input[first].toInt() == 0) first++
        val unsigned = input.copyOfRange(first, input.size)
        return if (unsigned[0].toInt() and 0x80 != 0) byteArrayOf(0) + unsigned else unsigned
    }

    private fun openDocumentPicker(call: NativeCall, result: NativeResult) {
        if (pendingPickerResult != null) {
            result.error("picker_busy", "The document picker is already open.", null)
            return
        }
        val directory = call.argument<String>("targetDirectory")?.let(::File)
        if (directory == null) {
            result.error("target_missing", "The private import directory is missing.", null)
            return
        }
        directory.mkdirs()
        pickerTargetDirectory = directory
        pickerMaxBytes = (call.argument<Number>("maxBytes")?.toLong() ?: pickerMaxBytes)
        val studentFile = call.argument<Boolean>("studentFile") == true
        pickerMimeTypes = if (studentFile) STUDENT_FILE_MIME_TYPES else ALLOWED_MIME_TYPES
        pendingPickerResult = result
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, pickerMimeTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, !studentFile)
        }
        startActivityForResult(intent, PICK_DOCUMENTS)
    }

    // Inspect the privately staged original before preparing recognition copies.
    private fun inspectDocumentImage(call: NativeCall, result: NativeResult) {
        val path = call.argument<String>("path")
        Thread {
            try {
                val source = File(path ?: "").canonicalFile
                val privateRoot = File(applicationInfo.dataDir).canonicalFile
                require(source.isFile && source.path.startsWith(privateRoot.path + File.separator))
                val info = DocumentImagePreparation.inspect(source)
                runOnUiThread {
                    result.success(mapOf(
                        "width" to info.width, "height" to info.height,
                        "mimeType" to info.mimeType, "orientation" to info.orientation
                    ))
                }
            } catch (e: Exception) {
                runOnUiThread { result.error("image_inspection_failed", "Image inspection unavailable", null) }
            }
        }.start()
    }

    private fun stageScannerImage(call: NativeCall, result: NativeResult) {
        val rawUri = call.argument<String>("imageUri")
        val directory = call.argument<String>("targetDirectory")?.let(::File)
        val maxBytes = call.argument<Number>("maxBytes")?.toLong() ?: 0L
        val stagedFileName = call.argument<String>("fileName")
        if (rawUri.isNullOrBlank() || directory == null || maxBytes <= 0) {
            result.error("scanner_copy_failed", "محل یا اندازهٔ برگه معتبر نیست.", null)
            return
        }
        val parsed = Uri.parse(rawUri)
        val uri = if (parsed.scheme == null) Uri.fromFile(File(rawUri)) else parsed
        if (uri.scheme != "file" && uri.scheme != "content") {
            result.error("scanner_copy_failed", "آدرس برگهٔ اسکن‌شده معتبر نیست.", null)
            return
        }
        Thread {
            val fileName = stagedFileName ?: (UUID.randomUUID().toString() + ".jpg")
            if (!Regex("^[a-f0-9-]{36}\\.jpg$").matches(fileName)) {
                runOnUiThread { result.error("scanner_copy_failed", "نام فایل اسکن معتبر نیست.", null) }
                return@Thread
            }
            val target = File(directory, fileName)
            val partial = File(directory, "$fileName.partial")
            var ownsPartial = false
            try {
                if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Private storage unavailable")
                if (target.exists() || partial.exists()) throw IllegalStateException("Capture already staged")
                ownsPartial = true
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw IllegalArgumentException("Missing scanner image")
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > maxBytes) throw IllegalArgumentException("Image limit")
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
                if (partial.length() <= 0L) throw IllegalArgumentException("Empty scan")
                validateSignature(partial, "image/jpeg")
                require(DocumentImagePreparation.inspect(partial).mimeType == "image/jpeg")
                if (!partial.renameTo(target)) throw IllegalStateException("Capture commit failed")
                // The local scanner may return a second image in our cache.
                // The committed temporary input now owns these pixels.
                if (uri.scheme == "file") {
                    try {
                        val scannerCopy = File(uri.path ?: "").canonicalFile
                        val cacheRoot = cacheDir.canonicalPath + File.separator
                        if (scannerCopy.path.startsWith(cacheRoot)) scannerCopy.delete()
                    } catch (_: Exception) { }
                }
                runOnUiThread {
                    result.success(mapOf(
                        "name" to ("scan-" + System.currentTimeMillis() + ".jpg"),
                        "path" to target.absolutePath, "mimeType" to "image/jpeg",
                        "size" to target.length(), "pageCount" to 1
                    ))
                }
            } catch (_: Exception) {
                // A failed scan must not leave a partial photograph behind.
                // A pre-existing file belongs to another staging attempt.
                if (ownsPartial) partial.delete()
                runOnUiThread { result.error("scanner_copy_failed", "برگهٔ اسکن‌شده به‌طور کامل ذخیره نشد؛ فضای گوشی و اندازهٔ عکس را بررسی کنید.", null) }
            }
        }.start()
    }

    private fun cancelCapture() {
        val request = takeCaptureRequest() ?: return
        try { finishActivity(request.activityCode) } catch (_: Exception) { }
        request.cameraFile?.takeIf { it.length() == 0L }?.delete()
        request.success(null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (appUpdates.onActivityResult(requestCode)) return
        if (requestCode == pendingCapture?.activityCode) {
            handleOfflineCameraResult(resultCode)
            return
        }
        if (requestCode == SAVE_DOCUMENT) {
            val result = pendingSaveResult ?: return
            val source = saveSource
            pendingSaveResult = null
            saveSource = null
            val destination = data?.data
            if (resultCode != Activity.RESULT_OK || source == null || destination == null) {
                result.success(false)
            } else {
                Thread {
                    try {
                        contentResolver.openOutputStream(destination, "w").use { output ->
                            if (output == null) throw IllegalArgumentException("The destination cannot be written.")
                            source.inputStream().use { input -> input.copyTo(output) }
                        }
                        runOnUiThread { result.success(true) }
                    } catch (error: Exception) {
                        runOnUiThread { result.error("save_failed", "The file could not be saved.", null) }
                    }
                }.start()
            }
            return
        }
        if (requestCode != PICK_DOCUMENTS) return
        val result = pendingPickerResult ?: return
        pendingPickerResult = null
        if (resultCode != Activity.RESULT_OK || data == null) {
            result.success(emptyList<Map<String, Any>>())
            return
        }
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) uris += clip.getItemAt(index).uri
        }
        data.data?.let { if (!uris.contains(it)) uris += it }
        val targetDirectory = pickerTargetDirectory!!
        val maxBytes = pickerMaxBytes
        val mimeTypes = pickerMimeTypes.copyOf()
        Thread {
            try {
                val items = uris.map { copyPrivateDocument(it, targetDirectory, maxBytes, mimeTypes) }
                runOnUiThread { result.success(items) }
            } catch (error: Exception) {
                runOnUiThread {
                    result.error("document_invalid", error.message ?: "The selected document is invalid.", null)
                }
            }
        }.start()
    }

    private fun copyPrivateDocument(
        uri: Uri,
        targetDirectory: File,
        maxBytes: Long,
        mimeTypes: Array<String>
    ): Map<String, Any> {
        val metadata = queryMetadata(uri)
        val displayName = metadata.first.ifBlank { "document" }
        val reportedSize = metadata.second
        if (reportedSize > maxBytes) throw IllegalArgumentException("File is larger than the configured limit.")
        val reportedMime = contentResolver.getType(uri)
        val mimeType = if (reportedMime == null || reportedMime == "application/octet-stream")
            mimeFromName(displayName) else reportedMime
        if (!mimeTypes.contains(mimeType)) throw IllegalArgumentException("Unsupported file type.")
        val extension = extensionForMime(mimeType)
        val target = File(targetDirectory, "${UUID.randomUUID()}$extension")
        try {
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) throw IllegalArgumentException("The selected file cannot be read.")
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > maxBytes) throw IllegalArgumentException("The selected file exceeds the size limit.")
                        output.write(buffer, 0, read)
                    }
                }
            }
            validateSignature(target, mimeType)
        } catch (error: Exception) {
            target.delete()
            throw error
        }
        val size = target.length()
        if (size <= 0L || size > maxBytes) {
            target.delete()
            throw IllegalArgumentException("The selected file has an invalid size: $displayName")
        }
        val pages = if (mimeType == "application/pdf") {
            android.os.ParcelFileDescriptor.open(target, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
            }
        } else 1
        return mapOf(
            "name" to displayName,
            "path" to target.absolutePath,
            "mimeType" to mimeType,
            "size" to size,
            "pageCount" to pages
        )
    }

    private fun queryMetadata(uri: Uri): Pair<String, Long> {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "" else ""
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                    return name to size
                }
            }
        return "document" to 0L
    }

    private fun validateSignature(file: File, mimeType: String) {
        val header = ByteArray(16)
        val read = file.inputStream().use { it.read(header) }
        if (read < 4) throw IllegalArgumentException("The selected file is incomplete.")
        val valid = when (mimeType) {
            "application/pdf" -> header.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-"
            "image/jpeg" -> header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()
            "image/png" -> header.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
            "image/heic", "image/heif" -> header.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                ZipFile(file).use { zip ->
                    zip.getEntry("[Content_Types].xml") != null && zip.getEntry("xl/workbook.xml") != null
                }
            "application/vnd.ms-excel" -> header.sliceArray(0..7).contentEquals(
                byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte())
            )
            "text/csv" -> header.take(read).none { it == 0.toByte() }
            else -> false
        }
        if (!valid) {
            throw IllegalArgumentException("The file contents do not match its declared type.")
        }
    }

    private fun prepareOmrImages(call: NativeCall, result: NativeResult) {
        val paths = call.argument<List<String>>("imagePaths")
        val output = call.argument<String>("outputDirectory")?.let(::File)
        val maxDimension = call.argument<Number>("maxInputDimension")?.toInt() ?: 2600
        if (paths.isNullOrEmpty() || output == null || maxDimension !in setOf(1800, 2000, 2600)) {
            result.error("omr_inputs_missing", "OMR inputs are missing.", null)
            return
        }
        Thread {
            try {
                val privateRoot = File(applicationInfo.dataDir).canonicalPath + File.separator
                require(output.canonicalPath.startsWith(privateRoot))
                val prepared = paths.mapIndexed { index, path ->
                    val source = File(path).canonicalFile
                    require(source.path.startsWith(privateRoot))
                    try {
                        // Flatten EXIF before the header worker; Google may
                        // encode its final rotation in metadata. Upright,
                        // ordinary JPEGs still pass without another encoding.
                        DocumentImagePreparation.prepareForHeader(
                            source, File(output, "page-${index + 1}"), maxDimension
                        )
                    } catch (_: Exception) {
                        // Preserve page-level native errors and later pages
                        // when one input cannot be prepared.
                        source.path
                    }
                }
                runOnUiThread { result.success(prepared) }
            } catch (error: Exception) {
                runOnUiThread { result.error("omr_prepare_failed", error.message, null) }
            }
        }.start()
    }

    private fun rasterizeDocument(call: NativeCall, result: NativeResult) {
        val input = call.argument<String>("inputPath")?.let(::File)
        val output = call.argument<String>("outputDirectory")?.let(::File)
        val mimeType = call.argument<String>("mimeType") ?: ""
        val maxDimension = call.argument<Number>("maxDimension")?.toInt() ?: 3000
        val previewPage = call.argument<Number>("previewPage")?.toInt()
        if (input == null || !input.exists() || output == null) {
            result.error("raster_input_missing", "The document is no longer available.", null)
            return
        }
        output.mkdirs()
        Thread {
            try {
                val paths = if (mimeType == "application/pdf") {
                    renderPdf(input, output, maxDimension, previewPage)
                } else {
                    listOf(renderImage(input, output, maxDimension))
                }
                runOnUiThread { result.success(paths) }
            } catch (error: Exception) {
                runOnUiThread { result.error("raster_failed", error.message ?: "Document rendering failed.", null) }
            }
        }.start()
    }

    private fun renderPdf(input: File, output: File, maxDimension: Int, previewPage: Int? = null): List<String> {
        val paths = mutableListOf<String>()
        android.os.ParcelFileDescriptor.open(input, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val indices = if (previewPage == null) 0 until renderer.pageCount else {
                    require(previewPage in 1..renderer.pageCount) { "The requested PDF page does not exist." }
                    (previewPage - 1)..(previewPage - 1)
                }
                for (index in indices) {
                    renderer.openPage(index).use { page ->
                        val scale = maxDimension.toFloat() / max(page.width, page.height).toFloat()
                        val width = max(1, (page.width * scale).roundToInt())
                        val height = max(1, (page.height * scale).roundToInt())
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        val matrix = Matrix().apply { postScale(scale, scale) }
                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val target = File(output, "page-${index + 1}.png")
                        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                        paths += target.absolutePath
                    }
                }
            }
        }
        return paths
    }

    private fun renderImage(input: File, output: File, maxDimension: Int): String {
        return DocumentImagePreparation.render(input, output, maxDimension)
    }

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "xls" -> "application/vnd.ms-excel"
        "csv" -> "text/csv"
        else -> "image/jpeg"
    }

    private fun extensionForMime(mimeType: String): String = when (mimeType) {
        "application/pdf" -> ".pdf"
        "image/png" -> ".png"
        "image/heic" -> ".heic"
        "image/heif" -> ".heif"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
        "application/vnd.ms-excel" -> ".xls"
        "text/csv" -> ".csv"
        else -> ".jpg"
    }
}

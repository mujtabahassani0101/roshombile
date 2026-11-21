package com.haseebullahjan.roshd

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.haseebullahjan.roshd.platform.NativeCall
import com.haseebullahjan.roshd.platform.NativeResult
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/** A separate provider exposes only verified update files, never school data. */
class AppUpdateFileProvider : FileProvider()

class AppUpdatePlatform(private val activity: Activity) {
    companion object { const val SETTINGS_REQUEST = 7220 }
    private val root get() = File(activity.filesDir, "app-updates")
    private val worker = Executors.newSingleThreadExecutor()
    private var permissionResult: NativeResult? = null
    private var installing = false
    private fun allowed() = Build.VERSION.SDK_INT < 26 ||
        activity.packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28)
        info.longVersionCode else info.versionCode.toLong()

    fun handle(call: NativeCall, result: NativeResult) {
        when (call.method) {
            "info" -> {
                @Suppress("DEPRECATION")
                val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
                result.success(mapOf("packageName" to activity.packageName,
                    "versionCode" to code(info), "versionName" to info.versionName,
                    "updateDirectory" to root.path))
            }
            "canInstall" -> result.success(allowed())
            "settings" -> {
                if (permissionResult != null) {
                    result.error("busy", "درخواست اجازهٔ نصب در جریان است.", null)
                    return
                }
                if (allowed()) { result.success(null); return }
                try {
                    permissionResult = result
                    activity.startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")), SETTINGS_REQUEST)
                } catch (error: Exception) {
                    permissionResult = null
                    result.error("settings", "تنظیم اجازهٔ نصب در این دستگاه باز نشد.", null)
                }
            }
            "installStatus" -> result.success(activity.getSharedPreferences("app-update", 0)
                .getString("status", null))
            "install" -> install(call, result)
            else -> result.notImplemented()
        }
    }

    fun onActivityResult(requestCode: Int): Boolean {
        if (requestCode != SETTINGS_REQUEST) return false
        permissionResult?.success(null)
        permissionResult = null
        return true
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val values = if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners else info.signatures
        return values?.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }?.toSet() ?: emptySet()
    }

    @Suppress("DEPRECATION")
    private fun validate(file: File, call: NativeCall): PackageInfo {
        require(file.isFile && file.canonicalFile.parentFile == root.canonicalFile &&
            Regex("[0-9a-f-]{36}\\.apk").matches(file.name)) {
            "فایل نصب معتبر نیست. دوباره دانلود کنید."
        }
        require(file.length() == call.argument<Number>("sizeBytes")!!.toLong()) {
            "حجم فایل نصب معتبر نیست."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash == call.argument<String>("sha256")) { "فایل نصب سالم نیست. دوباره دانلود کنید." }
        val pm = activity.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(activity.packageName, flags)
        val candidate = pm.getPackageArchiveInfo(file.path, flags)
            ?: throw IllegalArgumentException("ساختار APK معتبر نیست.")
        require(candidate.packageName == activity.packageName &&
            candidate.packageName == call.argument<String>("packageName")) {
            "این فایل متعلق به رشد نیست."
        }
        require(code(candidate) == call.argument<Number>("versionCode")!!.toLong() &&
            code(candidate) > code(installed) &&
            candidate.versionName == call.argument<String>("versionName")) {
            "نسخهٔ فایل نصب با نسخهٔ اعلام‌شده مطابقت ندارد."
        }
        val installedSigners = signatures(installed)
        val candidateSigners = signatures(candidate)
        val compatible = if (installedSigners.size == 1 && candidateSigners.size == 1 &&
            Build.VERSION.SDK_INT >= 28) {
            val history = candidate.signingInfo?.signingCertificateHistory?.map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                    .joinToString("") { b -> "%02x".format(b.toInt() and 255) }
            }?.toSet() ?: candidateSigners
            history.containsAll(installedSigners)
        } else installedSigners == candidateSigners
        require(installedSigners.isNotEmpty() && candidateSigners.isNotEmpty() && compatible) {
            "امضای این نسخه با برنامهٔ نصب‌شده سازگار نیست."
        }
        if (Build.VERSION.SDK_INT >= 24) {
            require((candidate.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) {
                "این نسخه با نسخهٔ اندروید دستگاه سازگار نیست."
            }
        }
        ZipFile(file).use { zip ->
            val abis = zip.entries().asSequence().map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.split('/')[1] }.toSet()
            require(abis.isEmpty() || Build.SUPPORTED_ABIS.any { it in abis }) {
                "این APK با پردازندهٔ دستگاه سازگار نیست."
            }
        }
        return candidate
    }

    private fun install(call: NativeCall, result: NativeResult) {
        if (installing) { result.error("busy", "نصب در جریان است.", null); return }
        if (!allowed()) { result.error("permission", "ابتدا اجازهٔ نصب را فعال کنید.", null); return }
        installing = true
        worker.execute {
            var sessionId: Int? = null
            try {
                val file = File(requireNotNull(call.argument<String>("path")))
                val info = validate(file, call)
                val installer = activity.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(activity.packageName)
                params.setSize(file.length())
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                val id = installer.createSession(params)
                sessionId = id
                installer.openSession(id).use { session ->
                    val uri = FileProvider.getUriForFile(activity,
                        "${activity.packageName}.updates", file)
                    activity.contentResolver.openInputStream(uri)!!.use { input ->
                        session.openWrite("base.apk", 0, file.length()).use { output ->
                            input.copyTo(output, 65536)
                            session.fsync(output)
                        }
                    }
                    val intent = Intent(activity, AppUpdateInstallReceiver::class.java)
                        .setAction("${activity.packageName}.UPDATE_INSTALL")
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(activity, id, intent, flags)
                    activity.getSharedPreferences("app-update", 0).edit()
                        .putString("status", "pending")
                        .putLong("expectedVersion", code(info)).commit()
                    session.commit(pending.intentSender)
                }
                activity.runOnUiThread { installing = false; result.success(null) }
            } catch (error: Exception) {
                sessionId?.let { try { activity.packageManager.packageInstaller.abandonSession(it) } catch (_: Exception) {} }
                activity.runOnUiThread {
                    installing = false
                    result.error("install", error.message ?: "نصب انجام نشد؛ فضای خالی دستگاه را بررسی کنید.", null)
                }
            }
        }
    }

    fun close() {
        permissionResult?.error("closed", "درخواست اجازهٔ نصب بسته شد.", null)
        permissionResult = null
        worker.shutdown()
    }
}

/** Explicit, non-exported PackageInstaller callback; no external broadcasts. */
class AppUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val preferences = context.getSharedPreferences("app-update", 0)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            try {
                requireNotNull(confirmation)
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmation)
            } catch (_: Exception) {
                preferences.edit().putString("status", "failed").apply()
            }
        } else {
            preferences.edit().putString("status", when (status) {
                PackageInstaller.STATUS_SUCCESS -> "success"
                PackageInstaller.STATUS_FAILURE_ABORTED -> "cancelled"
                else -> "failed"
            }).apply()
        }
    }
}

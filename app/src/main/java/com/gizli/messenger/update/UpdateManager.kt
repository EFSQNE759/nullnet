package com.gizli.messenger.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.gizli.messenger.BuildConfig
import com.gizli.messenger.crypto.Crypto
import com.gizli.messenger.net.B64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val minSdk: Int,
    val notes: String
)

/**
 * İmzalı güncelleme sistemi.
 *
 * 1) update.json indirilir.
 * 2) İçeriğin Ed25519 imzası, uygulamaya gömülü AÇIK anahtarla doğrulanır.
 *    (Sunucu/hesap ele geçse bile sahte güncelleme yayınlanamaz.)
 * 3) APK indirilir, SHA-256 özeti doğrulanır.
 * 4) APK'nın imza sertifikası, kurulu uygulamanınkiyle birebir aynı mı kontrol edilir.
 * 5) Kurulum ekranı açılır.
 */
class UpdateManager(private val ctx: Context) {

    companion object {
        private const val CANON_VERSION = "gizli-update-v1"
    }

    private fun canonicalPayload(i: UpdateInfo): ByteArray {
        val s = buildString {
            append(CANON_VERSION).append('\n')
            append(i.versionCode).append('\n')
            append(i.versionName).append('\n')
            append(i.sha256).append('\n')
            append(i.apkUrl).append('\n')
            append(i.minSdk).append('\n')
            append(i.notes)
        }
        return s.toByteArray(Charsets.UTF_8)
    }

    /** Yeni sürüm varsa döndürür, yoksa null. */
    suspend fun checkForUpdate(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val manifest = httpGetText(BuildConfig.UPDATE_MANIFEST_URL)
            val o = JSONObject(manifest)
            val info = UpdateInfo(
                versionCode = o.getInt("versionCode"),
                versionName = o.getString("versionName"),
                apkUrl = o.getString("apkUrl"),
                sha256 = o.getString("sha256"),
                minSdk = o.optInt("minSdk", 26),
                notes = o.optString("notes", "")
            )
            val sig = B64.dec(o.getString("signature"))
            val pub = B64.dec(BuildConfig.UPDATE_PUBLIC_KEY)
            if (!Crypto.ed25519Verify(pub, canonicalPayload(info), sig)) {
                return@withContext Result.failure(Exception("Güncelleme imzası geçersiz — sahte olabilir!"))
            }
            val newer = info.versionCode > BuildConfig.VERSION_CODE && info.minSdk <= Build.VERSION.SDK_INT
            Result.success(if (newer) info else null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** APK'yı indirir, doğrular ve dosyayı döndürür. */
    suspend fun downloadApk(info: UpdateInfo, onProgress: (Int) -> Unit = {}): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "gizli-${info.versionCode}.apk")
                val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { input ->
                    val total = conn.contentLength
                    var read = 0
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            output.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress((read * 100 / total))
                        }
                    }
                }
                conn.disconnect()

                val digest = sha256Hex(out)
                if (!digest.equals(info.sha256, ignoreCase = true)) {
                    out.delete()
                    return@withContext Result.failure(Exception("APK özeti uyuşmadı — dosya bozuk/oynanmış."))
                }
                if (!signerMatches(out)) {
                    out.delete()
                    return@withContext Result.failure(Exception("APK imzası uygulamayla uyuşmuyor."))
                }
                Result.success(out)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun install(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, BuildConfig.APPLICATION_ID + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    /** Bilinmeyen kaynak kurulum izni verilmiş mi? */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    private fun signerMatches(apk: File): Boolean {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val self = pm.getPackageInfo(ctx.packageName, flags)
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
        val selfCerts = certsOf(self) ?: return false
        val apkCerts = certsOf(archive) ?: return false
        return selfCerts.any { s -> apkCerts.any { it.contentEquals(s) } }
    }

    private fun certsOf(pi: android.content.pm.PackageInfo): Array<ByteArray>? {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) {
            (pi.signingInfo?.apkContentsSigners)?.map { it.toByteArray() }?.toTypedArray()
        } else {
            @Suppress("DEPRECATION")
            pi.signatures?.map { it.toByteArray() }?.toTypedArray()
        }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun httpGetText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/json")
        return try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}

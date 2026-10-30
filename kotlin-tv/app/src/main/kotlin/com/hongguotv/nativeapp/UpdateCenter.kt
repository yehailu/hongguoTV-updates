// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * In-app update against our CNB releases.
 *
 * Android cannot install silently without a system signature, so the flow is: read the newest
 * release tag, pull its `update.json`, compare the build's own variant, stream the APK into cache
 * while verifying SHA-256, then hand it to the system installer.
 */
object UpdateCenter {
    private const val BASE = "https://cnb.cool/lzyr9010/hongguoTV-updates"
    private const val LATEST = "$BASE/-/releases/latest"
    private const val DOWNLOAD = "$BASE/-/releases/download"
    private const val MAX_APK_BYTES = 80L * 1024 * 1024

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val tag: String,
        val file: String,
        val size: Long,
        val sha256: String
    ) {
        fun downloadUrl() = "$DOWNLOAD/$tag/$file"
    }

    /** `/releases/latest` answers 307; the tag is the last path segment of its Location. */
    private fun latestTag(client: OkHttpClient): String? {
        val probe = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        return runCatching {
            probe.newCall(Request.Builder().url(LATEST).build()).execute().use { response ->
                val location = response.header("Location").orEmpty()
                Regex("/tag/([^/?#]+)").find(location)?.groupValues?.get(1)
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,100}")) && !it.contains("..") }
            }
        }.getOrNull()
    }

    /** Returns the release for [variantId] when it is newer than [currentCode]; null otherwise. */
    fun check(client: OkHttpClient, variantId: String, currentCode: Int): Info? {
        val tag = latestTag(client) ?: return null
        val manifest = runCatching {
            client.newBuilder().followSslRedirects(false).build()
                .newCall(Request.Builder().url("$DOWNLOAD/$tag/update.json").build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()
            }
        }.getOrNull() ?: return null
        val root = runCatching { JSONObject(manifest) }.getOrNull() ?: return null
        val entry = root.optJSONObject("variants")?.optJSONObject(variantId) ?: return null
        val code = entry.optInt("versionCode", 0)
        if (code <= currentCode) return null
        val file = entry.optString("file")
        val size = entry.optLong("size", 0)
        val sha = entry.optString("sha256").lowercase()
        if (!file.matches(Regex("[A-Za-z0-9._-]{1,120}\\.apk")) || size !in 1..MAX_APK_BYTES) return null
        if (!sha.matches(Regex("[0-9a-f]{64}"))) return null
        return Info(code, entry.optString("versionName", code.toString()), root.optString("notes").take(1500), tag, file, size, sha)
    }

    /** Streams the APK and verifies size + SHA-256; returns null on any mismatch (partial file removed). */
    fun download(client: OkHttpClient, info: Info, target: File, onPercent: (Int) -> Unit): File? {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var lastPercent = -1
        val ok = runCatching {
            client.newBuilder()
                .followSslRedirects(false)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES)
                .build()
                .newCall(Request.Builder().url(info.downloadUrl()).build()).execute().use { response ->
                    if (!response.isSuccessful) return null
                    val body = response.body ?: return null
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                written += read
                                if (written > MAX_APK_BYTES) return null
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                val percent = (written * 100 / info.size).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) { lastPercent = percent; onPercent(percent) }
                            }
                        }
                    }
                }
            written == info.size && digest.digest().joinToString("") { "%02x".format(it) } == info.sha256
        }.getOrDefault(false)
        if (!ok) { target.delete(); return null }
        return target
    }

    /** Handing the file to the system installer; returns false when the user must grant permission first. */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
}

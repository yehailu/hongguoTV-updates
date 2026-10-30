// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** API16–18 public Downloads, API19–23 external cache, API24+ FileProvider. Never silent install. */
object LegacyApkInstaller {
    private val preparing=AtomicBoolean(false)

    private fun sha256(file: File): ByteArray {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes=ByteArray(64*1024)
            while(true) { val read=input.read(bytes); if(read<0) break; digest.update(bytes,0,read) }
        }
        return digest.digest()
    }
    private fun preparedCopy(app: Context, apk: File): File? {
        if (android.os.Environment.getExternalStorageState()!=android.os.Environment.MEDIA_MOUNTED) return null
        val base=if(Build.VERSION.SDK_INT<=18) {
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        } else app.externalCacheDir ?: return null
        val folder=File(base,if(Build.VERSION.SDK_INT<=18) "HongguoTV-verified-updates" else "verified-updates")
            .apply { if(!isDirectory && !mkdirs()) return null }
        val external=File(folder,"hongguotv-a41.apk")
        val expectedSize=apk.length()
        if(!apk.isFile || expectedSize !in 1..(80L*1024*1024)) return null
        val expectedHash=sha256(apk)
        apk.inputStream().use { input -> external.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
        if(external.length()!=expectedSize || !sha256(external).contentEquals(expectedHash)) {
            // Fixed generated APK path only, never the source or private data directories.
            if(external.canonicalFile.parentFile==folder.canonicalFile) external.delete()
            return null
        }
        return external
    }
    private fun handoff(context: Context, apk: File): Boolean = runCatching {
        val uri=if(Build.VERSION.SDK_INT>=24) FileProvider.getUriForFile(context,"${context.packageName}.updates",apk)
            else Uri.fromFile(apk)
        val intent=Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if(Build.VERSION.SDK_INT>=24) intent.clipData=ClipData.newRawUri("verified update",uri)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** true means direct handoff or an accepted asynchronous preparation request, not an installed APK. */
    fun install(context: Context, apk: File): Boolean {
        if(Build.VERSION.SDK_INT>=26 && !context.packageManager.canRequestPackageInstalls()) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return false
        }
        if(Build.VERSION.SDK_INT>=24) return handoff(context,apk)
        if(android.os.Environment.getExternalStorageState()!=android.os.Environment.MEDIA_MOUNTED ||
            !apk.isFile || apk.length() !in 1..(80L*1024*1024)) return false
        if(!preparing.compareAndSet(false,true)) return true
        // No Activity captured: slow SD copy, SHA checks, and fsync never run on the UI thread.
        val app=context.applicationContext
        val started=runCatching {
            Thread({
                val ready=runCatching { preparedCopy(app,apk) }.getOrNull()
                Handler(Looper.getMainLooper()).post {
                    try {
                        if(ready==null || !handoff(app,ready)) {
                            Toast.makeText(app,"无法准备安装包，请检查外部存储并重试",Toast.LENGTH_LONG).show()
                        }
                    } finally { preparing.set(false) }
                }
            },"hongguotv-legacy-installer").start()
            true
        }.getOrDefault(false)
        if(!started) preparing.set(false)
        return started
    }
}

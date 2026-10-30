// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.util.Log
import com.hongguotv.core.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Debug-only opt-in smoke probe. Enable by adb am start --ez android41Smoke true. */
object LegacyDiagnostics {
    fun start(context: Context) {
        Thread({
            Log.i("HGTVCompat", "sdk=${android.os.Build.VERSION.SDK_INT} decoderTypes=" + LegacyPlatform.codecs()
                .filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() }.distinct().joinToString(","))
            val client=TlsSupport.client(OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS)
                .readTimeout(8,TimeUnit.SECONDS).callTimeout(12,TimeUnit.SECONDS).followSslRedirects(false),context).build()
            try {
                listOf("https://cnb.cool/lzyr9010/hongguoTV-updates", "https://example.com/",
                    "https://valid-isrgrootx1.letsencrypt.org/",
                    "https://self-signed.badssl.com/", "https://wrong.host.badssl.com/").forEach { url ->
                    try {
                        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                            Log.i("HGTVCompat", "tls=$url http=${response.code} handshake=${response.handshake()!=null}")
                        }
                    } catch (failure: Exception) {
                        val chain=generateSequence<Throwable>(failure) { it.cause }.take(5)
                            .joinToString(" <- ") { it.javaClass.simpleName+":"+it.message.orEmpty().take(150) }
                        Log.i("HGTVCompat", "tls=$url failed=$chain")
                    }
                }
            } finally {
                client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            }
        },"hgtv-api16-smoke").start()
    }
}

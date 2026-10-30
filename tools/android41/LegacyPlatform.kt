// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.View
import java.util.concurrent.atomic.AtomicInteger

/** Only copied into the isolated Android 4.1 build; no modern dependency changes. */
object LegacyPlatform {
    private val nextId = AtomicInteger(1)
    fun generateViewId(): Int {
        while (true) {
            val current = nextId.get()
            if (nextId.compareAndSet(current, if (current == 0x00ffffff) 1 else current + 1)) return current
        }
    }
    fun codecs(): List<MediaCodecInfo> = runCatching {
        // The static enumeration API exists since API16, unlike MediaCodecList(int).
        (0 until MediaCodecList.getCodecCount()).map { MediaCodecList.getCodecInfoAt(it) }
    }.getOrDefault(emptyList())
    fun lowRam(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        return if (Build.VERSION.SDK_INT >= 19) manager.isLowRamDevice else manager.memoryClass <= 128
    }
    fun bitmapBytes(bitmap: Bitmap): Int = if (Build.VERSION.SDK_INT >= 19) bitmap.allocationByteCount else bitmap.byteCount
    fun tintProgress(view: android.widget.ProgressBar, color: Int, indeterminate: Boolean) {
        val drawable = if (indeterminate) view.indeterminateDrawable else view.progressDrawable
        drawable?.mutate()?.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
    }
    fun scaledContext(base: Context, config: android.content.res.Configuration): Context {
        if (Build.VERSION.SDK_INT >= 17) return base.createConfigurationContext(config)
        @Suppress("DEPRECATION")
        base.resources.updateConfiguration(config, base.resources.displayMetrics)
        return base
    }
}

/** All supported systems use the broadcast API, avoiding API21 classes in Activity fields. */
class LegacyNetworkMonitor(private val context: Context, private val changed: () -> Unit) {
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { changed() }
    }
    @Suppress("DEPRECATION")
    fun start() {
        if (registered) return
        val filter = IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        registered = true
    }
    fun close() {
        if (registered) { runCatching { context.unregisterReceiver(receiver) }; registered = false }
    }
}

/** Name deliberately differs from the platform property to avoid Java-member resolution. */
val View.legacyAttached: Boolean get() = windowToken != null
val View.legacyLaidOut: Boolean get() = width > 0 && height > 0

// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.support.v4.media.session.MediaSessionCompat

/** A real component is mandatory for MediaSessionCompat's API16 RemoteControlClient backend. */
object LegacyMediaButtons {
    @Volatile var session: MediaSessionCompat? = null
}
class LegacyMediaButtonReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        LegacyMediaButtons.session?.controller?.dispatchMediaButtonEvent(event)
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory

/** hw flavor: platform decoders only, no native FFmpeg payload. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object DecodeSupport {
    const val softwareAvailable=false
    fun renderers(context: Context,preferSoftware: Boolean): RenderersFactory =
        DefaultRenderersFactory(context).setEnableDecoderFallback(true)
}

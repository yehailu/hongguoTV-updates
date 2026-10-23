// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/** sw flavor: FFmpeg software decoders (HEVC/H.264/VP9/AV1) as Media3 extension renderers. */
object DecodeSupport {
    const val softwareAvailable=true
    fun renderers(context: Context,preferSoftware: Boolean): RenderersFactory =
        NextRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                if(preferSoftware) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )
}

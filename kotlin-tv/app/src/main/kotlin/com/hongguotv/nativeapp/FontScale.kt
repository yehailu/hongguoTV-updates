// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** Seven UI text sizes: 100% plus three smaller and three larger steps, 8% apart. */
object FontScale {
    const val MIN_STEP=-3
    const val MAX_STEP=3
    private const val PERCENT=8
    val steps=List(MAX_STEP-MIN_STEP+1) { index -> 1f+(MIN_STEP+index)*PERCENT/100f }
    val defaultIndex=-MIN_STEP
    fun indexOf(scale: Float): Int {
        if(!scale.isFinite() || scale<=0f) return defaultIndex
        var best=defaultIndex
        var closest=Float.MAX_VALUE
        steps.forEachIndexed { index,value ->
            val distance=kotlin.math.abs(value-scale)
            if(distance<closest) { closest=distance; best=index }
        }
        return best
    }
    fun scaleOf(index: Int)=steps[index.coerceIn(0,steps.lastIndex)]
    fun stepOf(index: Int)=MIN_STEP+index.coerceIn(0,steps.lastIndex)
    fun label(index: Int): String {
        val step=stepOf(index)
        val percent=(scaleOf(index)*100).toInt()
        return when {
            step==0 -> "标准 · $percent%"
            step>0 -> "放大 +$step 档 · $percent%"
            else -> "缩小 $step 档 · $percent%"
        }
    }
}

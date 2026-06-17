package com.pda.app.ui.dockreceiving

import kotlin.math.max
import kotlin.math.roundToInt

/** 对齐 web imageCompress.ts。 */
const val MAX_EDGE = 1800
const val JPEG_QUALITY = 80  // web 用 0.8；Android Bitmap.compress 用 0..100

/**
 * BitmapFactory.Options.inSampleSize：最大的 2 的幂，使降采样后最长边仍 >= maxEdge，
 * 以便后续精确缩放到 maxEdge 时不放大。纯函数，可单元测试。
 */
fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, maxEdge: Int): Int {
    val longest = max(srcWidth, srcHeight)
    var sample = 1
    while (longest / (sample * 2) >= maxEdge) {
        sample *= 2
    }
    return sample
}

/** 保持宽高比，把最长边缩到 maxEdge（不放大）。返回至少 1×1。 */
fun scaledSize(srcWidth: Int, srcHeight: Int, maxEdge: Int): Pair<Int, Int> {
    val longest = max(srcWidth, srcHeight)
    if (longest <= maxEdge) return srcWidth to srcHeight
    val scale = maxEdge.toDouble() / longest
    val w = max(1, (srcWidth * scale).roundToInt())
    val h = max(1, (srcHeight * scale).roundToInt())
    return w to h
}

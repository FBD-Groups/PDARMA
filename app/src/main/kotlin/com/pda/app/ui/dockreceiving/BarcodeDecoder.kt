package com.pda.app.ui.dockreceiving

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** 从一张照片中解出运输标签的运单号条码；无可用条码返回 null。 */
interface BarcodeDecoder {
    suspend fun decodeTracking(file: File): String?
}

/**
 * 纯函数：从一张标签上解出的多个条码里挑出"运单号那个"。可单测，不依赖 Android。
 *
 * 标签常有多个条码（运单号、邮资、路由码）。规则：
 *  1) 用 [sanitizeTracking] 过滤出像运单号的（去空白/连字符后 8..40 位字母数字、≥6 位数字）；
 *  2) 优先已知承运商前缀（UPS 1Z、USPS 94/92/93/95）；
 *  3) 否则取最长的（运单号通常比路由码长）。
 * 返回 sanitize 后的紧凑串，挑不出返回 null。
 */
fun pickTrackingBarcode(candidates: List<String>): String? {
    val valid = candidates.mapNotNull { raw -> sanitizeTracking(raw).ifBlank { null } }
    if (valid.isEmpty()) return null
    return valid.firstOrNull { it.startsWith("1Z", ignoreCase = true) }
        ?: valid.firstOrNull { c -> USPS_PREFIXES.any { c.startsWith(it) } }
        ?: valid.maxByOrNull { it.length }
}

private val USPS_PREFIXES = listOf("94", "92", "93", "95")

@Module
@InstallIn(SingletonComponent::class)
abstract class BarcodeDecoderModule {
    @Binds
    @Singleton
    abstract fun bindBarcodeDecoder(impl: MlKitBarcodeDecoder): BarcodeDecoder
}

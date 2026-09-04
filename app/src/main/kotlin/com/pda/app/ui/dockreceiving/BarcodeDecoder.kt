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
 * USPS 标签常用 GS1-128，原始值形如 `]C1420912349400136110139348703814`：
 *  - `]C1` = AIM reader 前缀（非字母数字，需剥离）
 *  - `42091234` = GS1 AI 420 + ZIP
 *  - `9400136110139348703814` = 真正运单号（嵌入其中）
 * 用 [expandBarcode] 展开每个原始值后再走正常过滤流程。
 *
 * 规则：
 *  1) 展开后丢掉内部非运单前缀（如 FWD…，经 [sanitizeTracking]）；
 *  2) [sanitizeTracking] 过滤（含 FedEx `96…` 34 位长码 → 末 12 位）；
 *  3) 优先 UPS 1Z → USPS 94/92/93/95 → FedEx 12 位纯数字（含长码收短后）；
 *  4) 否则取最长合法候选。
 * 返回 sanitize 后的紧凑串，挑不出返回 null。
 */
fun pickTrackingBarcode(candidates: List<String>): String? {
    val valid = candidates
        .flatMap { expandBarcode(it) }
        .distinct()
        .mapNotNull { raw -> sanitizeTracking(raw).ifBlank { null } }
        .distinct()
    if (valid.isEmpty()) return null
    return valid.firstOrNull { it.startsWith("1Z", ignoreCase = true) }
        ?: valid.firstOrNull { c -> USPS_PREFIXES.any { c.startsWith(it) } }
        // FedEx：现场多为 96… 长码，sanitize 后已是末 12 位纯数字
        ?: valid.firstOrNull { it.length == 12 && it.all(Char::isDigit) }
        ?: valid.maxByOrNull { it.length }
}

/**
 * 将一个原始条码值展开成多个候选串，以覆盖 GS1-128 多段拼接的情况：
 *  1. 原始值本身；
 *  2. 剥掉 AIM 前缀（`]Xn` 形式）后的值；
 *  3. 从上述值中用正则提取嵌入的 22 位 USPS 运单号（9[2-5]xxxxx）。
 */
private fun expandBarcode(raw: String): List<String> {
    val result = mutableListOf(raw)
    // 剥掉 AIM reader 前缀（]Xn，例如 ]C1 / ]e0）
    val stripped = if (raw.length > 3 && raw[0] == ']' && raw[1].isLetter() && raw[2].isDigit()) {
        raw.substring(3).also { result.add(it) }
    } else raw
    // GS1-128 里可能嵌入 22 位 USPS 运单号（9[2-5] + 20 位数字）
    EMBEDDED_USPS.find(stripped)?.value?.let { result.add(it) }
    return result
}

private val USPS_PREFIXES = listOf("94", "92", "93", "95")
// USPS IMpb: 以 92/93/94/95 开头的 22 位纯数字
private val EMBEDDED_USPS = Regex("9[2-5]\\d{20}")

@Module
@InstallIn(SingletonComponent::class)
abstract class BarcodeDecoderModule {
    @Binds
    @Singleton
    abstract fun bindBarcodeDecoder(impl: MlKitBarcodeDecoder): BarcodeDecoder
}

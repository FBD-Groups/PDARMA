package com.pda.app.ui.dockreceiving

/** 抄自 web constants.ts，保持与 RMA web 端一致。 */
val CARRIERS = listOf("UPS", "FedEx", "USPS", "DHL", "Amazon", "OnTrac", "Other")
val CONDITIONS = listOf("Good", "Fair", "Damaged", "Unknown")

/** FedEx Ground/SmartPost 扫描长条码：34 位、96 开头；真实运单号为末 12 位（对齐 web carrierDetect）。 */
private val FEDEX_LONG_BARCODE = Regex("^96\\d{32}$")

/**
 * 大小写不敏感匹配 CARRIERS，命中返回标准写法；未命中返回原值（trim 后）；
 * null/空白返回 ""。对齐 web PhotoTab 的归一化逻辑。
 */
fun normalizeCarrier(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    return CARRIERS.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
}

/**
 * 校验 AI 返回的运单号：去掉空白/连字符后必须是 8..40 位字母数字、且至少含 6 位数字，
 * 才认为有效并返回紧凑串；否则（空、N/A、提示语、乱码、过短）返回 ""。
 *
 * FedEx 长条码（`96` + 32 位数字）自动收成末 12 位短码，避免入库 34 位扫描串。
 */
fun sanitizeTracking(raw: String?): String {
    val cleaned = raw?.trim().orEmpty()
    if (cleaned.isEmpty()) return ""
    val compact = cleaned.replace(Regex("[\\s-]"), "")
    val normalized = shortenFedExTracking(compact)
    val valid = normalized.length in 8..40 &&
        normalized.all { it.isLetterOrDigit() } &&
        normalized.count { it.isDigit() } >= 6
    return if (valid) normalized else ""
}

/**
 * FedEx 长条码（`96` + 32 位）→ 末 12 位短码；否则原样返回。
 * 不做「carrier=FedEx 且位数>15」的兜底截断——15 位 Ground 等标准号必须保留。
 */
fun shortenFedExTracking(compact: String): String {
    if (FEDEX_LONG_BARCODE.matches(compact)) return compact.takeLast(12)
    return compact
}

/** 原始串（去空白后）是否为 FedEx 34 位 96 长条码。 */
fun wasFedExLongBarcode(raw: String?): Boolean {
    val compact = raw?.trim()?.replace(Regex("[\\s-]"), "").orEmpty()
    return FEDEX_LONG_BARCODE.matches(compact)
}

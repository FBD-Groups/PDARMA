package com.pda.app.ui.dockreceiving

/** 抄自 web constants.ts，保持与 RMA web 端一致。 */
val CARRIERS = listOf("UPS", "FedEx", "USPS", "DHL", "Amazon", "OnTrac", "Other")
val CONDITIONS = listOf("Good", "Fair", "Damaged", "Unknown")

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
 * 才认为有效并返回原值（trim 后）；否则（空、N/A、提示语、乱码、过短）返回 ""。
 * 用于识别失败时不把垃圾值写进字段、避免 Confirm 被错误启用。纯函数，可单测。
 */
fun sanitizeTracking(raw: String?): String {
    val cleaned = raw?.trim().orEmpty()
    if (cleaned.isEmpty()) return ""
    val compact = cleaned.replace(Regex("[\\s-]"), "")
    val valid = compact.length in 8..40 &&
        compact.all { it.isLetterOrDigit() } &&
        compact.count { it.isDigit() } >= 6
    return if (valid) compact else ""
}

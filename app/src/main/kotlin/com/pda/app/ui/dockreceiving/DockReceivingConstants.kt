package com.pda.app.ui.dockreceiving

import com.pda.app.data.api.model.ActiveCustomer

/** 抄自 web constants.ts，保持与 RMA web 端一致。 */
val CARRIERS = listOf("UPS", "FedEx", "USPS", "DHL", "Amazon", "OnTrac", "Other")
val CONDITIONS = listOf("Good", "Fair", "Damaged", "Unknown")

/** FedEx Ground/SmartPost 扫描长条码：34 位、96 开头；真实运单号为末 12 位（对齐 web carrierDetect）。 */
private val FEDEX_LONG_BARCODE = Regex("^96\\d{32}$")

/** 标签内部码，永远不是承运商运单号（如 FWD 转发/RMA 参考条码）。 */
private val INTERNAL_NON_TRACKING_PREFIXES = listOf("FWD")

/** 标签上常见 `UF00162` 或 `UF00162-RMA`；取前缀 UF+数字做匹配。 */
private val UF_CODE_PREFIX = Regex("""^(UF\d+)""", RegexOption.IGNORE_CASE)

/**
 * 对齐 web PhotoTab：优先用 AI 的 customerCode 在活跃客户列表中精确匹配（忽略大小写）；
 * 未命中则显示规范化后的 UF 编码（如 UF00162）；再没有编码才用 AI 的 customerName。
 * 返回 (customerId?, displayName)。
 */
fun resolveCustomerFromAnalyze(
    customerCode: String?,
    customerName: String?,
    activeCustomers: List<ActiveCustomer>
): Pair<Long?, String> {
    val codeKey = normalizeCustomerCode(customerCode)
    if (codeKey != null) {
        val match = activeCustomers.firstOrNull { it.code.equals(codeKey, ignoreCase = true) }
        if (match != null) return match.id to match.name
        // 列表未命中：仍展示 UF 编码，避免客户栏空白（标签上往往只有编码没有公司名）。
        return null to codeKey
    }
    val name = customerName?.trim().orEmpty()
    return null to name
}

/** `UF00162-RMA` → `UF00162`；无 UF 前缀则原样 trim；空则 null。 */
fun normalizeCustomerCode(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val uf = UF_CODE_PREFIX.find(trimmed)?.groupValues?.get(1)
    return (uf ?: trimmed).uppercase()
}

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
 * 校验运单号：去掉空白/连字符后必须是 8..40 位字母数字、且至少含 6 位数字，
 * 才认为有效并返回紧凑串；否则（空、N/A、提示语、乱码、过短、内部 FWD 码）返回 ""。
 *
 * FedEx 长条码（`96` + 32 位数字）自动收成末 12 位短码，避免入库 34 位扫描串。
 */
fun sanitizeTracking(raw: String?): String {
    val cleaned = raw?.trim().orEmpty()
    if (cleaned.isEmpty()) return ""
    val compact = cleaned.replace(Regex("[\\s-]"), "")
    if (isInternalNonTrackingCode(compact)) return ""
    val normalized = shortenFedExTracking(compact)
    val valid = normalized.length in 8..40 &&
        normalized.all { it.isLetterOrDigit() } &&
        normalized.count { it.isDigit() } >= 6
    return if (valid) normalized else ""
}

/** `FWD…` 等内部参考码，不当作承运商运单号。 */
fun isInternalNonTrackingCode(compact: String): Boolean =
    INTERNAL_NON_TRACKING_PREFIXES.any { compact.startsWith(it, ignoreCase = true) }

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

package com.pda.app.data.api.model

import kotlinx.serialization.Serializable

/** GET /api/customers 行；字段名对齐后端 camelCase。 */
@Serializable
data class CustomerDto(
    val id: Long,
    val customerCode: String = "",
    val customerName: String = "",
    val isActive: Boolean = true,
    /** 逗号分隔的原始别名字符串（后端 Customers.Alias），未配置为 null。见 CustomerRepository.parseAliasField。 */
    val alias: String? = null
)

/** 活跃客户（UI / 匹配用，不含网络冗余字段）。 */
data class ActiveCustomer(
    val id: Long,
    val code: String,
    val name: String,
    /** alias 拆分成小写、trim 过的关键词，短于 MIN_ALIAS_LENGTH 的已过滤掉；见 CustomerRepository.parseAliasField。 */
    val aliases: List<String> = emptyList()
)

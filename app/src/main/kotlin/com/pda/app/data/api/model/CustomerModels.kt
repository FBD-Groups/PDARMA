package com.pda.app.data.api.model

import kotlinx.serialization.Serializable

/** GET /api/customers 行；字段名对齐后端 camelCase。 */
@Serializable
data class CustomerDto(
    val id: Long,
    val customerCode: String = "",
    val customerName: String = "",
    val isActive: Boolean = true
)

/** 活跃客户（UI / 匹配用，不含网络冗余字段）。 */
data class ActiveCustomer(
    val id: Long,
    val code: String,
    val name: String
)

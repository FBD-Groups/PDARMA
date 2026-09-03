package com.pda.app.data.repository

import android.util.Log
import com.pda.app.data.NetworkResult
import com.pda.app.data.api.CustomerApiService
import com.pda.app.data.api.model.ActiveCustomer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class CustomerRepository @Inject constructor(
    private val api: CustomerApiService
) {
    companion object {
        private const val TAG = "PDA/CustomerRepository"
    }

    /**
     * 活跃客户列表（对齐 web mapActiveCustomers）。
     * 失败时由调用方决定是否降级为空列表。
     */
    open fun getActiveCustomers(): Flow<NetworkResult<List<ActiveCustomer>>> = flow {
        emit(NetworkResult.Loading)
        try {
            val resp = api.getCustomers()
            if (resp.isSuccessful && resp.body() != null) {
                val list = resp.body()!!
                    .filter { it.isActive }
                    .map { ActiveCustomer(id = it.id, code = it.customerCode, name = it.customerName) }
                    .filter { it.id > 0 && it.name.isNotBlank() }
                emit(NetworkResult.Success(list))
            } else {
                val message = when (resp.code()) {
                    401 -> "登录已过期，请重新登录"
                    403 -> "无权限访问客户列表"
                    else -> "加载客户失败（${resp.code()}）"
                }
                Log.w(TAG, "getActiveCustomers: failed code=${resp.code()}")
                emit(NetworkResult.Error(message, resp.code()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getActiveCustomers: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: "网络连接失败，请检查网络设置"))
        }
    }.flowOn(Dispatchers.IO)
}

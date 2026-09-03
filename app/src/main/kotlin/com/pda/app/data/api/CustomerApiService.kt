package com.pda.app.data.api

import com.pda.app.data.api.model.CustomerDto
import retrofit2.Response
import retrofit2.http.GET

interface CustomerApiService {

    /** GET /api/customers — 需 JWT。 */
    @GET("api/customers")
    suspend fun getCustomers(): Response<List<CustomerDto>>
}

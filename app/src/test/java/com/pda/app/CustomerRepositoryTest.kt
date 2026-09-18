package com.pda.app

import com.pda.app.data.NetworkResult
import com.pda.app.data.api.CustomerApiService
import com.pda.app.data.api.model.CustomerDto
import com.pda.app.data.repository.CustomerRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

/** parseAliasField — 对齐 web returnClient.test.ts 的 "mapActiveCustomers — alias parsing"。 */
class CustomerRepositoryTest {

    @Test
    fun `parseAliasField splits the raw comma-separated string into a list`() {
        assertEquals(
            listOf("eco", "ecoflow", "ef tiktok"),
            CustomerRepository.parseAliasField("eco,ecoflow,ef tiktok")
        )
    }

    @Test
    fun `parseAliasField filters out segments shorter than 3 characters`() {
        assertEquals(listOf("dji"), CustomerRepository.parseAliasField("dji,ef,a"))
    }

    @Test
    fun `parseAliasField trims and lowercases each segment`() {
        assertEquals(
            listOf("geekbuy", "geek buy"),
            CustomerRepository.parseAliasField(" GeekBuy , Geek Buy ")
        )
    }

    @Test
    fun `parseAliasField returns empty list when alias is null or blank`() {
        assertEquals(emptyList<String>(), CustomerRepository.parseAliasField(null))
        assertEquals(emptyList<String>(), CustomerRepository.parseAliasField("  "))
    }

    @Test
    fun `getActiveCustomers populates aliases from the alias field`() = runTest {
        val api = object : CustomerApiService {
            override suspend fun getCustomers() = Response.success(
                listOf(
                    CustomerDto(id = 1, customerCode = "UF00073", customerName = "EcoFlow", alias = "eco,ecoflow"),
                    CustomerDto(id = 2, customerCode = "UF00175", customerName = "DJI", alias = null)
                )
            )
        }
        val repo = CustomerRepository(api)

        val success = repo.getActiveCustomers().toList()[1] as NetworkResult.Success
        assertEquals(listOf("eco", "ecoflow"), success.data[0].aliases)
        assertEquals(emptyList<String>(), success.data[1].aliases)
    }
}

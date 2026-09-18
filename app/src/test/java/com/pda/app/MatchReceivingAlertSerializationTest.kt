package com.pda.app

import com.pda.app.data.NetworkResult
import com.pda.app.data.api.ReceivingApiService
import com.pda.app.data.repository.ReceivingRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * `GET /api/receiving-alerts/match` 未命中时后端返回 HTTP 200、body 是字面量 `null`——
 * docs/pda对齐.md 第 1 节专门标注过这一步"要确认 Retrofit + kotlinx-serialization 这层能
 * 正确解析成 null 而不是抛异常"。之前的单测全部走 FakeReceivingApiService（连 Retrofit/OkHttp
 * 都没真正跑过一次），没有验证到这一步——而这一步实测过是真会炸的：
 * `ReceivingApiService.matchReceivingAlert` 如果直接声明成 `Response<ReceivingAlertDto?>`，
 * Kotlin 的可空类型编译后被类型擦除，Retrofit 的 kotlinx-serialization 转换器解析出来的
 * `Type` 反射不出这个 `?`，实际总是按非空 `ReceivingAlertDto` 的 serializer 解码，遇到
 * 字面量 `null` 会直接抛 `JsonDecodingException`（这份文件最早的版本就是这样写的，
 * 用 MockWebServer 一测就炸了）。
 *
 * 修复后的设计是：接口层返回原始 `ResponseBody`（Retrofit 原生识别，不经过 Converter），
 * [ReceivingRepository.matchAlert] 自己用注入的 `Json` 实例、显式的 `ReceivingAlertDto?`
 * 可空 serializer 手动解析字符串——这里用 MockWebServer 起一个真实的本地 HTTP server，
 * 配上跟 NetworkModule 完全一致的 Retrofit 配置，从 API 接口一路测到 Repository 的
 * `NetworkResult`，验证真正的端到端行为，而不是像之前那样只测到"接口层被 Fake 替换掉"
 * 为止的一半路径。
 */
class MatchReceivingAlertSerializationTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: ReceivingRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // 跟 NetworkModule.provideJson() 保持一致。
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        val api = retrofit.create(ReceivingApiService::class.java)
        repo = ReceivingRepository(api, json)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `HTTP 200 with a literal null body resolves to Success(null), not a decode exception`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("null")
                .setHeader("Content-Type", "application/json")
        )

        val result = repo.matchAlert("1Z999AA10123456784").toList()[1]

        assertEquals(true, result is NetworkResult.Success)
        assertNull((result as NetworkResult.Success).data)
    }

    @Test
    fun `HTTP 200 with a real alert object resolves to a populated hit`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"id":"abc-123","trackingNumber":"1Z999","instruction":"Please send to DJI","status":"O"}"""
                )
                .setHeader("Content-Type", "application/json")
        )

        val result = repo.matchAlert("1Z999AA10123456784").toList()[1] as NetworkResult.Success

        assertEquals("abc-123", result.data!!.id)
        assertEquals("Please send to DJI", result.data!!.instruction)
    }
}

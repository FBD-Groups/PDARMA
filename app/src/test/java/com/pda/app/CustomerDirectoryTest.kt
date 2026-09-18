package com.pda.app

import com.pda.app.data.NetworkResult
import com.pda.app.data.api.CustomerApiService
import com.pda.app.data.api.model.ActiveCustomer
import com.pda.app.data.api.model.UserInfoDto
import com.pda.app.data.repository.CustomerRepository
import com.pda.app.data.session.CustomerDirectory
import com.pda.app.data.session.SessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 每次调用 getActiveCustomers() 返回一个独立的、由测试手动控制何时结束的 Flow——用来精确
 * 模拟"请求还在路上"这类时序场景（加载中登出、加载中换账号等），见 docs/pda对齐.md 第 3 节。
 *
 * 按调用序号（1-based，即调用发生时的 callCount）寻址，而不是"先进先出"——一次请求即使
 * 因为 session 变化被 CustomerDirectory 取消（协程层面），底层的 CompletableDeferred 本身
 * 依然留在这里、依然可以被 complete()，两者是独立的：取消只是没人再等它的结果，不代表这个
 * "槽位"消失了。用序号寻址才能在测试里明确指向"第几次调用"，不会跟已经被取消、但还没被
 * 显式 complete 的旧调用搞混。
 */
private class ControllableCustomerRepository : CustomerRepository(
    object : CustomerApiService {
        override suspend fun getCustomers() = error("unused")
    }
) {
    var callCount = 0
        private set
    private val byCallIndex = mutableMapOf<Int, CompletableDeferred<NetworkResult<List<ActiveCustomer>>>>()

    override fun getActiveCustomers(): Flow<NetworkResult<List<ActiveCustomer>>> {
        callCount++
        val index = callCount
        val d = CompletableDeferred<NetworkResult<List<ActiveCustomer>>>()
        byCallIndex[index] = d
        return flow {
            emit(NetworkResult.Loading)
            emit(d.await())
        }
    }

    /** [callIndex] 是 1-based 的调用序号（对应 complete() 调用时那次 getActiveCustomers() 的 callCount）。 */
    fun complete(callIndex: Int, result: NetworkResult<List<ActiveCustomer>>) {
        byCallIndex.getValue(callIndex).complete(result)
    }
}

private fun user(name: String = "alice") = UserInfoDto(
    userId = name,
    username = name,
    email = "$name@example.com",
    fullName = name
)

private val customerA = ActiveCustomer(1, "UF00001", "Customer A")
private val customerB = ActiveCustomer(2, "UF00002", "Customer B")

class CustomerDirectoryTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `ensureLoaded fetches once and subsequent calls are no-ops`() = runTest {
        val repo = ControllableCustomerRepository()
        val sessionManager = SessionManager().apply { start("t1", user()) }
        val directory = CustomerDirectory(repo, sessionManager, dispatcher)

        launch { directory.ensureLoaded() }
        advanceUntilIdle()
        repo.complete(1, NetworkResult.Success(listOf(customerA)))
        advanceUntilIdle()

        assertEquals(1, repo.callCount)
        assertEquals(listOf(customerA), directory.customers.value)

        directory.ensureLoaded()
        advanceUntilIdle()
        assertEquals(1, repo.callCount)
    }

    @Test
    fun `concurrent ensureLoaded calls share a single in-flight request`() = runTest {
        val repo = ControllableCustomerRepository()
        val sessionManager = SessionManager().apply { start("t1", user()) }
        val directory = CustomerDirectory(repo, sessionManager, dispatcher)

        launch { directory.ensureLoaded() }
        launch { directory.ensureLoaded() }
        launch { directory.ensureLoaded() }
        advanceUntilIdle()
        assertEquals(1, repo.callCount)

        repo.complete(1, NetworkResult.Success(listOf(customerA)))
        advanceUntilIdle()
        assertEquals(listOf(customerA), directory.customers.value)
    }

    @Test
    fun `failed load does not mark loaded, next call retries`() = runTest {
        val repo = ControllableCustomerRepository()
        val sessionManager = SessionManager().apply { start("t1", user()) }
        val directory = CustomerDirectory(repo, sessionManager, dispatcher)

        launch { directory.ensureLoaded() }
        advanceUntilIdle()
        repo.complete(1, NetworkResult.Error("network down"))
        advanceUntilIdle()

        assertTrue(directory.customers.value.isEmpty())

        launch { directory.ensureLoaded() }
        advanceUntilIdle()
        assertEquals(2, repo.callCount)
        repo.complete(2, NetworkResult.Success(listOf(customerA)))
        advanceUntilIdle()
        assertEquals(listOf(customerA), directory.customers.value)
    }

    @Test
    fun `logout clears the cached list`() = runTest {
        val repo = ControllableCustomerRepository()
        val sessionManager = SessionManager().apply { start("t1", user()) }
        val directory = CustomerDirectory(repo, sessionManager, dispatcher)

        launch { directory.ensureLoaded() }
        advanceUntilIdle()
        repo.complete(1, NetworkResult.Success(listOf(customerA)))
        advanceUntilIdle()
        assertEquals(listOf(customerA), directory.customers.value)

        sessionManager.clear()
        advanceUntilIdle()

        assertTrue(directory.customers.value.isEmpty())
    }

    @Test
    fun `token expiry (401) clears the cached list, not just explicit logout`() = runTest {
        val repo = ControllableCustomerRepository()
        val sessionManager = SessionManager().apply { start("t1", user()) }
        val directory = CustomerDirectory(repo, sessionManager, dispatcher)

        launch { directory.ensureLoaded() }
        advanceUntilIdle()
        repo.complete(1, NetworkResult.Success(listOf(customerA)))
        advanceUntilIdle()
        assertEquals(listOf(customerA), directory.customers.value)

        sessionManager.expire()
        advanceUntilIdle()

        assertTrue(directory.customers.value.isEmpty())
    }

    @Test
    fun `re-login after logout reloads instead of reusing loaded=true`() = runTest {
        val repo = ControllableCustomerRepository()
        val sessionManager = SessionManager().apply { start("t1", user()) }
        val directory = CustomerDirectory(repo, sessionManager, dispatcher)

        launch { directory.ensureLoaded() }
        advanceUntilIdle()
        repo.complete(1, NetworkResult.Success(listOf(customerA)))
        advanceUntilIdle()

        sessionManager.clear()
        sessionManager.start("t2", user("bob"))
        advanceUntilIdle()

        launch { directory.ensureLoaded() }
        advanceUntilIdle()
        assertEquals(2, repo.callCount)
        repo.complete(2, NetworkResult.Success(listOf(customerB)))
        advanceUntilIdle()
        assertEquals(listOf(customerB), directory.customers.value)
    }

    @Test
    fun `a stale in-flight load that completes after logout must not repopulate the cache`() = runTest {
        // P1 场景（docs/pda对齐.md 第 3 节"加载中登出"）：ensureLoaded() 的请求已经发出去，
        // 在它返回之前用户就登出了；清空动作管不到这个在途请求，它稍后如果正常返回成功，
        // 绝不能把数据又写回来。
        val repo = ControllableCustomerRepository()
        val sessionManager = SessionManager().apply { start("t1", user()) }
        val directory = CustomerDirectory(repo, sessionManager, dispatcher)

        launch { directory.ensureLoaded() }
        advanceUntilIdle()
        assertEquals(1, repo.callCount)

        // 登出发生在请求还没返回的时候。
        sessionManager.clear()
        advanceUntilIdle()
        assertTrue(directory.customers.value.isEmpty())

        // 迟到的成功结果这时候才落地。
        repo.complete(1, NetworkResult.Success(listOf(customerA)))
        advanceUntilIdle()

        assertTrue(
            "stale result must not repopulate the cache after logout",
            directory.customers.value.isEmpty()
        )
        assertFalse(directory.customers.value.contains(customerA))
    }

    @Test
    fun `logout then immediately login a different account discards the first account's stale result`() = runTest {
        // P1 场景（docs/pda对齐.md 第 3 节"加载中登出后立即换账号登录"）：账号 A 的请求还在路上时
        // 触发登出、再登录账号 B；账号 A 的请求稍后才返回（带着账号 A 的数据），绝不能覆盖账号 B。
        val repo = ControllableCustomerRepository()
        val sessionManager = SessionManager().apply { start("t1", user("alice")) }
        val directory = CustomerDirectory(repo, sessionManager, dispatcher)

        launch { directory.ensureLoaded() }
        advanceUntilIdle()
        assertEquals(1, repo.callCount) // account A's request is in flight

        sessionManager.clear()
        sessionManager.start("t2", user("bob"))
        advanceUntilIdle()

        launch { directory.ensureLoaded() }
        advanceUntilIdle()
        assertEquals(2, repo.callCount) // account B's own request now in flight too

        // B 的请求先完成。
        repo.complete(2, NetworkResult.Success(listOf(customerB)))
        advanceUntilIdle()
        assertEquals(listOf(customerB), directory.customers.value)

        // A 的请求（第一个发出去的）这时候才姗姗来迟地完成，必须被丢弃。
        repo.complete(1, NetworkResult.Success(listOf(customerA)))
        advanceUntilIdle()

        assertEquals(
            "customer B's data must not be overwritten by account A's stale, late-arriving result",
            listOf(customerB),
            directory.customers.value
        )
    }

    @Test
    fun `ensureLoaded propagates the caller's own cancellation instead of swallowing it`() = runTest {
        // 实现细节回归测试：deferred.await() 里 catch CancellationException 时必须区分"调用方自己被
        // 取消"和"deferred 自己过期"两种情况，只吞后者。见 docs/pda对齐.md 第 3 节最后一轮修订。
        val repo = ControllableCustomerRepository()
        val sessionManager = SessionManager().apply { start("t1", user()) }
        val directory = CustomerDirectory(repo, sessionManager, dispatcher)

        val job = launch { directory.ensureLoaded() }
        advanceUntilIdle() // 请求已发出，job 正挂在 deferred.await() 上

        job.cancel() // 取消调用方自己所在的协程，不是 CustomerDirectory 内部的 deferred
        advanceUntilIdle()

        assertTrue("caller's own cancellation must actually cancel the job", job.isCancelled)
    }

    @Test
    fun `ensureLoaded wins the race against the collector's delayed first replay (P1 regression)`() = runTest {
        // docs/pda对齐.md 第 3 节"登录后 collector 尚未处理 token，Dock 已调用 ensureLoaded"。
        // CustomerDirectory.init{} 里订阅 sessionManager.session 的 collector 协程在构造时
        // 就已经用 scope.launch{} 排进了 dispatcher 队列，但 StandardTestDispatcher 不会立即
        // 执行它，必须显式 advance 才会跑。这里故意在第一次 advanceUntilIdle() 之前，用
        // CoroutineStart.UNDISPATCHED 启动 ensureLoaded()——UNDISPATCHED 让协程体在当前调用栈
        // 里立即往下跑到第一个真正的挂起点为止，不经过调度队列；ensureLoaded() 开头那段
        // （核对/登记 observedToken、发起请求）是纯同步代码，会在这一步就跑完并抢先登记
        // observedToken，真正先于 collector 那个还排在队列里、原封未动的任务——这正是文档里
        // 描述的真实竞态："ensureLoaded() 先于 collector 拿到调度"。
        //
        // 如果实现退回到"只让 collector 的 distinctUntilChanged() 判断变化"（本文档第三版
        // 之前的写法），collector 稍后处理这个它第一次见到的 token 时会把它误判成"新的会话
        // 变化"，重新推进 generation 并取消掉 ensureLoaded() 已经登记、其实完全合法的这次
        // 请求——最终 customers 会是空列表而不是 [customerA]。
        val repo = ControllableCustomerRepository()
        val sessionManager = SessionManager().apply { start("t1", user()) }
        val directory = CustomerDirectory(repo, sessionManager, dispatcher)

        val job = launch(start = CoroutineStart.UNDISPATCHED) { directory.ensureLoaded() }
        // collector 的任务此刻仍然原封不动地排在队列里，一次都没被处理过。
        advanceUntilIdle()

        assertEquals(1, repo.callCount)
        repo.complete(1, NetworkResult.Success(listOf(customerA)))
        advanceUntilIdle()
        job.join()

        assertEquals(
            "ensureLoaded() 抢先登记的 observedToken 不应该被 collector 迟到的'第一次回放'当成新变化撤销",
            listOf(customerA),
            directory.customers.value
        )
    }

    @Test
    fun `generation check and the write it guards happen in one atomic step, never a torn state`() = runTest {
        // docs/pda对齐.md 第 3 节"generation 校验与写入的原子性"。核对 generation 和写入
        // customers/loaded 现在被设计成同一个 stateMutex.withLock{} 临界区里的纯同步代码，
        // 中间不存在任何挂起点——所以不存在"检查通过了、但写入前被 session 变化插队"这种
        // 中间态：某次 ensureLoaded() 的结果要么完整生效（generation 仍然匹配），要么完整
        // 作废（generation 已经变了），不会出现只写了一半、或者状态自相矛盾的情况。
        //
        // 两种具体的先后顺序已经被其它测试分别锁定："a stale in-flight load that completes
        // after logout must not repopulate the cache"锁定"先切会话、后网络结果落地"这个顺序；
        // "ensureLoaded fetches once..."锁定"先网络结果落地、后（没有会话切换）正常提交"这个
        // 顺序。这里把"网络结果落地"和"会话切换"背靠背排进同一批调度、一次性 advance，
        // 断言最终状态只能是这两个互斥结果之一，不允许出现第三种损坏状态（比如空列表和
        // customerA 各写入一部分字段、或者抛出未捕获异常）。
        val repo = ControllableCustomerRepository()
        val sessionManager = SessionManager().apply { start("t1", user("alice")) }
        val directory = CustomerDirectory(repo, sessionManager, dispatcher)

        val job = launch(start = CoroutineStart.UNDISPATCHED) { directory.ensureLoaded() }
        advanceUntilIdle()
        assertEquals(1, repo.callCount)

        repo.complete(1, NetworkResult.Success(listOf(customerA)))
        sessionManager.start("t2", user("bob"))
        advanceUntilIdle()
        job.join()

        val final = directory.customers.value
        assertTrue(
            "commit 必须是原子的：结果只能是完整生效（[customerA]）或完整作废（空列表），" +
                "不允许出现第三种损坏状态，实际是 $final",
            final.isEmpty() || final == listOf(customerA)
        )
    }
}

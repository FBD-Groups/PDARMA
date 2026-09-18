package com.pda.app.data.session

import android.util.Log
import com.pda.app.data.NetworkResult
import com.pda.app.data.api.model.ActiveCustomer
import com.pda.app.data.repository.CustomerRepository
import com.pda.app.di.DefaultDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 活跃客户列表的进程级缓存：登录后加载一次，Dock Receiving 每次开批不再重新拉
 * `GET /api/customers`。见 docs/pda对齐.md 第 3 节。
 *
 * 单向依赖 [SessionManager]（只读它的 session 流），绝不能反过来让 SessionManager 持有
 * CustomerDirectory——CustomerRepository → CustomerApiService → Retrofit → OkHttpClient →
 * AuthInterceptor → SessionManager 这条依赖链已经存在，反向持有会在 Hilt 图里绕出一个环。
 */
@Singleton
class CustomerDirectory @Inject constructor(
    private val customerRepo: CustomerRepository,
    private val sessionManager: SessionManager,
    /** 可注入测试用的 TestDispatcher，让内部 scope 跑在跟测试相同的（虚拟时间）调度器上，
     *  这样 `advanceUntilIdle()` 才能正确等到这里的协程完成，而不是悬在真实的 Default 线程池上。
     *  Dagger/Hilt 不认 Kotlin 默认参数值，生产环境的绑定见 [com.pda.app.di.DispatcherModule]。 */
    @DefaultDispatcher dispatcher: CoroutineDispatcher
) {
    private companion object {
        const val TAG = "PDA/CustomerDirectory"
    }

    private val _customers = MutableStateFlow<List<ActiveCustomer>>(emptyList())
    val customers: StateFlow<List<ActiveCustomer>> = _customers.asStateFlow()

    // stateMutex 只保护下面这几个字段的读写，绝不跨越 deferred.await() 这种网络等待——
    // 加锁的代码块必须是纯同步、瞬间完成的，这样"session 变化清空缓存"和"加载结果落地"
    // 才能真正互斥，不会有中间窗口。
    private val stateMutex = Mutex()
    private var generation = 0
    private var loaded = false
    private var inFlight: Deferred<List<ActiveCustomer>?>? = null   // null 结果代表这一次加载失败

    // "当前状态是围绕哪个 token 建立的"——ensureLoaded() 和下面的 session collector 都要经过
    // syncObservedTokenLocked() 跟这个字段比较，不能各自维护一套"变没变"的判断逻辑。初始值 null
    // 正好对应"还没登录"这个默认状态，不需要额外哨兵值。
    private var observedToken: String? = null

    // Hilt Singleton 存活期跟进程一样长，这里用一个自持的 scope。
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        scope.launch {
            sessionManager.session
                .map { it?.token }
                .collect { token -> stateMutex.withLock { syncObservedTokenLocked(token) } }
        }
    }

    /**
     * 必须在持有 stateMutex 时调用。核对 [token] 是否跟 [observedToken] 不同——不同才是一次真正的
     * 会话变化（登出/401 过期/重新登录切换成另一个非空 session），推进 generation、清空缓存、取消
     * 在途请求；跟 [observedToken] 相同就什么都不做（这正是修复"collector 第一次回放被误判成变化"
     * 的关键：谁先观察到某个 token，谁就把它记下来，后来者不会重复处理同一次变化）。
     */
    private fun syncObservedTokenLocked(token: String?) {
        if (observedToken == token) return
        observedToken = token
        generation++
        loaded = false
        _customers.value = emptyList()
        inFlight?.cancel()   // 非阻塞信号，不等它跑完；就算这行完全不生效，下面 generation 核对仍保证正确性
        inFlight = null
    }

    /**
     * 已加载过就直接返回；未加载/`forceRefresh` 时发起（或复用别的调用者已经发起的）一次加载，
     * 挂起到结果落地（或被判定过期丢弃）再返回。多个调用者并发调用只会触发一次真正的网络请求。
     */
    suspend fun ensureLoaded(forceRefresh: Boolean = false) {
        // 第一步：在锁内决定"用谁的 Deferred"，不等待网络——这一步必须极快，不能把网络 await 放进来。
        val claim = stateMutex.withLock {
            // 不依赖"反应式 collector 迟早会追上"：每次调用先自己核对一次当前 token，覆盖
            // "登录成功、ensureLoaded() 先于 collector 拿到调度"这种情况——collector 后面再收到
            // 同一个 token 时，会在 syncObservedTokenLocked() 里发现 observedToken 已经是这个值，
            // 直接跳过，不会把这次请求误判成旧会话的请求给取消掉。
            syncObservedTokenLocked(sessionManager.currentToken)
            if (loaded && !forceRefresh) return
            val reusable = inFlight
            if (reusable != null && !forceRefresh) {
                generation to reusable
            } else {
                val myGeneration = generation
                // customerRepo.getActiveCustomers() 是 Loading → 一个终结 Success/Error 的 Flow
                // （既有 Repository 的通用模式），这里只关心那个终结结果。
                val deferred = scope.async {
                    val result = customerRepo.getActiveCustomers()
                        .first { it !is NetworkResult.Loading }
                    (result as? NetworkResult.Success)?.data
                }
                inFlight = deferred
                myGeneration to deferred
            }
        }
        val (myGeneration, deferred) = claim

        // 第二步：网络等待完全在锁外，不持锁等 IO，不会跟 session 变化的清空逻辑产生死锁或长时间互斥。
        //
        // 不能直接写 `runCatching { deferred.await() }.getOrNull()`：deferred 虽然是
        // CustomerDirectory.scope 的子协程、结构上跟调用方所在的协程无关，但 await() 本身是在
        // 调用方的协程里挂起的——如果调用方自己被取消（比如这是从 viewModelScope 发起的调用，
        // ViewModel 被清除了），await() 同样会抛 CancellationException，这跟"deferred 自己被
        // syncObservedTokenLocked() cancel 掉"是两种不同来源、但外观相同的异常，笼统 catch 会
        // 不分青红皂白全部吞掉——这样一来会在调用方已经被取消的情况下继续往下跑、最后"正常返回"，
        // 破坏协程取消语义。要显式区分，只吞"deferred 自己过期"这一种，调用方自己被取消要照常
        // 重新抛出去。
        val data = try {
            deferred.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e   // 调用方自己被取消：重新抛出，别吞
            null   // 调用方仍然 active，说明是 deferred 自己被取消（过期请求）——按失败处理，继续往下走
        } catch (e: Exception) {
            Log.w(TAG, "ensureLoaded: ${e.message}")
            null   // 其它异常（网络错误等）按失败处理
        }

        // 第三步：核对 generation + 写入结果 + 清理 inFlight，三件事在同一个临界区内原子完成——
        // 不会被 session 变化的清空逻辑插到"核对通过"和"写入"之间。
        stateMutex.withLock {
            if (inFlight === deferred) inFlight = null
            if (myGeneration != generation) return@withLock   // 这一代已经作废，结果直接丢弃
            if (data != null) {
                _customers.value = data
                loaded = true
            }
            // data == null（失败）：不覆盖、不置 loaded=true，下次 ensureLoaded() 会重试。
        }
    }
}

package com.pda.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 标记"默认调度器"依赖，让 [com.pda.app.data.session.CustomerDirectory] 这类持有自己
 * CoroutineScope 的单例可以注入调度器（生产用 Dispatchers.Default），测试里直接构造类时
 * 换成 TestDispatcher——Dagger/Hilt 不认 Kotlin 默认参数值，构造函数每个参数都必须有
 * 显式绑定，所以不能只靠 `dispatcher: CoroutineDispatcher = Dispatchers.Default` 这种默认值。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}

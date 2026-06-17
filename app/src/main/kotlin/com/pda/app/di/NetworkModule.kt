package com.pda.app.di

import android.util.Log
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.pda.app.BuildConfig
import com.pda.app.data.api.AuthApiService
import com.pda.app.data.api.AuthInterceptor
import com.pda.app.data.api.ReceivingApiService
import com.pda.app.data.api.WarehouseApiService
import com.pda.app.data.session.SessionManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(sessionManager: SessionManager): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(sessionManager))
        .addInterceptor(
            HttpLoggingInterceptor { message ->
                Log.d("PDA/OkHttp", message)
            }.apply {
                level = if (BuildConfig.DEBUG)
                    HttpLoggingInterceptor.Level.BODY
                else
                    HttpLoggingInterceptor.Level.NONE
            }
        )
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.RMA_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideAuthApiService(retrofit: Retrofit): AuthApiService =
        retrofit.create(AuthApiService::class.java)

    @Provides
    @Singleton
    fun provideWarehouseApiService(retrofit: Retrofit): WarehouseApiService =
        retrofit.create(WarehouseApiService::class.java)

    @Provides
    @Singleton
    fun provideReceivingApiService(retrofit: Retrofit): ReceivingApiService =
        retrofit.create(ReceivingApiService::class.java)
}

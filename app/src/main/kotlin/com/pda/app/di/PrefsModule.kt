package com.pda.app.di

import com.pda.app.data.prefs.DataStoreUserPreferences
import com.pda.app.data.prefs.UserPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PrefsModule {
    @Binds
    @Singleton
    abstract fun bindUserPreferences(impl: DataStoreUserPreferences): UserPreferences
}

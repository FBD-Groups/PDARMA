package com.pda.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

/** 持久化：上次用户名 / 密码 / 记住开关 / 选中仓库 id / Dock 录入方式。 */
interface UserPreferences {
    val lastUsername: Flow<String?>
    val lastPassword: Flow<String?>
    val rememberUsername: Flow<Boolean>
    val selectedWarehouseId: Flow<Int?>
    /** Dock Receiving 上次选择的录入方式（InputMethod 枚举名）。 */
    val dockInputMethod: Flow<String?>

    /** 登录成功后调用：记住则存用户名与密码，否则清除；同时保存复选框状态。 */
    suspend fun saveLoginCredentials(username: String, password: String, remember: Boolean)
    suspend fun setSelectedWarehouseId(id: Int)
    suspend fun setDockInputMethod(name: String)
}

@Singleton
class DataStoreUserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) : UserPreferences {
    private companion object {
        val KEY_LAST_USERNAME = stringPreferencesKey("last_username")
        val KEY_LAST_PASSWORD = stringPreferencesKey("last_password")
        val KEY_REMEMBER_USERNAME = booleanPreferencesKey("remember_username")
        val KEY_SELECTED_WAREHOUSE_ID = intPreferencesKey("selected_warehouse_id")
        val KEY_INPUT_METHOD = stringPreferencesKey("dock_input_method")
    }

    override val lastUsername: Flow<String?> =
        context.dataStore.data.map { it[KEY_LAST_USERNAME] }

    override val lastPassword: Flow<String?> =
        context.dataStore.data.map { it[KEY_LAST_PASSWORD] }

    override val rememberUsername: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_REMEMBER_USERNAME] ?: true }

    override val selectedWarehouseId: Flow<Int?> =
        context.dataStore.data.map { it[KEY_SELECTED_WAREHOUSE_ID] }

    override val dockInputMethod: Flow<String?> =
        context.dataStore.data.map { it[KEY_INPUT_METHOD] }

    override suspend fun saveLoginCredentials(username: String, password: String, remember: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_REMEMBER_USERNAME] = remember
            if (remember) {
                prefs[KEY_LAST_USERNAME] = username
                prefs[KEY_LAST_PASSWORD] = password
            } else {
                prefs.remove(KEY_LAST_USERNAME)
                prefs.remove(KEY_LAST_PASSWORD)
            }
        }
    }

    override suspend fun setSelectedWarehouseId(id: Int) {
        context.dataStore.edit { it[KEY_SELECTED_WAREHOUSE_ID] = id }
    }

    override suspend fun setDockInputMethod(name: String) {
        context.dataStore.edit { it[KEY_INPUT_METHOD] = name }
    }
}

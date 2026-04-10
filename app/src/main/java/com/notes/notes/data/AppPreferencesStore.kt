package com.notes.notes.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.notes.notes.core.AppLanguage
import com.notes.notes.core.ThemeMode
import com.notes.notes.core.ThemePalette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.preferencesDataStore by preferencesDataStore(name = "notes_preferences")

data class StoredPreferences(
    val language: AppLanguage = AppLanguage.ZH_CN,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themePalette: ThemePalette = ThemePalette.BLUE,
    val savedUsername: String = "",
    val savedAccessToken: String = "",
)

class AppPreferencesStore(private val context: Context) {

    private object Keys {
        val language = stringPreferencesKey("language")
        val themeMode = stringPreferencesKey("theme_mode")
        val themePalette = stringPreferencesKey("theme_palette")
        val savedUsername = stringPreferencesKey("saved_username")
        val savedAccessToken = stringPreferencesKey("saved_access_token")
    }

    val preferences: Flow<StoredPreferences> = context.preferencesDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map(::mapPreferences)

    suspend fun setLanguage(language: AppLanguage) {
        context.preferencesDataStore.edit { it[Keys.language] = language.code }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.preferencesDataStore.edit { it[Keys.themeMode] = mode.storageValue }
    }

    suspend fun setThemePalette(palette: ThemePalette) {
        context.preferencesDataStore.edit { it[Keys.themePalette] = palette.storageValue }
    }

    suspend fun saveCredentials(username: String, accessToken: String) {
        context.preferencesDataStore.edit {
            it[Keys.savedUsername] = username
            it[Keys.savedAccessToken] = accessToken
        }
    }

    suspend fun clearCredentials() {
        context.preferencesDataStore.edit {
            it.remove(Keys.savedUsername)
            it.remove(Keys.savedAccessToken)
        }
    }

    private fun mapPreferences(preferences: Preferences): StoredPreferences = StoredPreferences(
        language = AppLanguage.fromCode(preferences[Keys.language] ?: systemLanguageCode()),
        themeMode = ThemeMode.fromStorage(preferences[Keys.themeMode]),
        themePalette = ThemePalette.fromStorage(preferences[Keys.themePalette]),
        savedUsername = preferences[Keys.savedUsername].orEmpty(),
        savedAccessToken = preferences[Keys.savedAccessToken].orEmpty(),
    )

    private fun systemLanguageCode(): String? {
        val locales = context.resources.configuration.locales
        return if (locales.isEmpty) null else locales[0]?.toLanguageTag()
    }
}

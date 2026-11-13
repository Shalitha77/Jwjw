package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.forgePrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "forge_settings")

data class SigningProfileConfig(
    val commonName: String = "APK Forge Developer",
    val organization: String = "Android Studio Mobile",
    val organizationalUnit: String = "Release Engineering",
    val country: String = "US",
    val alignUncompressedEntries: Boolean = true,
    val includeSourceMetadata: Boolean = true
)

class SigningPreferencesRepository(private val context: Context) {
    private object Keys {
        val CERT_CN = stringPreferencesKey("cert_cn")
        val CERT_O = stringPreferencesKey("cert_o")
        val CERT_OU = stringPreferencesKey("cert_ou")
        val CERT_C = stringPreferencesKey("cert_c")
        val ALIGN_ENTRIES = booleanPreferencesKey("align_entries")
        val INCLUDE_METADATA = booleanPreferencesKey("include_metadata")
    }

    val signingConfig: Flow<SigningProfileConfig> = context.forgePrefsDataStore.data.map { prefs ->
        SigningProfileConfig(
            commonName = prefs[Keys.CERT_CN] ?: "APK Forge Developer",
            organization = prefs[Keys.CERT_O] ?: "Android Studio Mobile",
            organizationalUnit = prefs[Keys.CERT_OU] ?: "Release Engineering",
            country = prefs[Keys.CERT_C] ?: "US",
            alignUncompressedEntries = prefs[Keys.ALIGN_ENTRIES] ?: true,
            includeSourceMetadata = prefs[Keys.INCLUDE_METADATA] ?: true
        )
    }

    suspend fun updateProfile(config: SigningProfileConfig) {
        context.forgePrefsDataStore.edit { prefs ->
            prefs[Keys.CERT_CN] = config.commonName.ifBlank { "APK Forge Developer" }
            prefs[Keys.CERT_O] = config.organization.ifBlank { "Android Studio Mobile" }
            prefs[Keys.CERT_OU] = config.organizationalUnit.ifBlank { "Release Engineering" }
            prefs[Keys.CERT_C] = config.country.take(2).uppercase().ifBlank { "US" }
            prefs[Keys.ALIGN_ENTRIES] = config.alignUncompressedEntries
            prefs[Keys.INCLUDE_METADATA] = config.includeSourceMetadata
        }
    }
}

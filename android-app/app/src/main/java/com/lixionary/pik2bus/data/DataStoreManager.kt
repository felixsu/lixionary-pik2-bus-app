package com.lixionary.pik2bus.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bus_tracker_prefs")

class DataStoreManager(private val context: Context) {
    companion object {
        private val SELECTED_BUSES_KEY = stringPreferencesKey("selected_buses")
        private val BACKEND_URL_KEY = stringPreferencesKey("backend_url")
        const val DEFAULT_BACKEND_URL = "http://10.0.2.2:8000/" // Android emulator host local loopback
    }

    val selectedBusesFlow: Flow<List<String>> = context.dataStore.data.map { preferences ->
        val busesString = preferences[SELECTED_BUSES_KEY] ?: ""
        if (busesString.isEmpty()) {
            emptyList()
        } else {
            busesString.split(",").map { it.trim() }
        }
    }

    val backendUrlFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[BACKEND_URL_KEY] ?: DEFAULT_BACKEND_URL
    }

    suspend fun saveSelectedBuses(buses: List<String>) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_BUSES_KEY] = buses.joinToString(",")
        }
    }

    suspend fun saveBackendUrl(url: String) {
        val sanitizedUrl = if (url.endsWith("/")) url else "$url/"
        context.dataStore.edit { preferences ->
            preferences[BACKEND_URL_KEY] = sanitizedUrl
        }
    }
}

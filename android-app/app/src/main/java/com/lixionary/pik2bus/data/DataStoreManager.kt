package com.lixionary.pik2bus.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bus_tracker_prefs")

private const val TAG = "Pik2Bus_Prefs"

class DataStoreManager(private val context: Context) {
    companion object {
        private val SELECTED_BUSES_KEY = stringPreferencesKey("selected_buses")
        private val BACKEND_URL_KEY = stringPreferencesKey("backend_url")
        private val LAST_USER_LAT_KEY = stringPreferencesKey("last_user_lat")
        private val LAST_USER_LNG_KEY = stringPreferencesKey("last_user_lng")
        const val DEFAULT_BACKEND_URL = "http://10.0.2.2:8000/" // Android emulator host local loopback
    }

    val lastUserLocationFlow: Flow<Pair<Double, Double>?> = context.dataStore.data.map { preferences ->
        val latStr = preferences[LAST_USER_LAT_KEY]
        val lngStr = preferences[LAST_USER_LNG_KEY]
        if (latStr != null && lngStr != null) {
            val lat = latStr.toDoubleOrNull()
            val lng = lngStr.toDoubleOrNull()
            if (lat != null && lng != null) {
                Pair(lat, lng)
            } else null
        } else null
    }

    suspend fun saveLastUserLocation(lat: Double, lng: Double) {
        context.dataStore.edit { preferences ->
            preferences[LAST_USER_LAT_KEY] = lat.toString()
            preferences[LAST_USER_LNG_KEY] = lng.toString()
        }
    }

    val selectedBusesFlow: Flow<List<String>> = context.dataStore.data.map { preferences ->
        val busesString = preferences[SELECTED_BUSES_KEY] ?: ""
        Log.d(TAG, "selectedBusesFlow: Read raw value: '$busesString'")
        if (busesString.isEmpty()) {
            emptyList()
        } else {
            busesString.split(",").map { it.trim() }
        }
    }

    val backendUrlFlow: Flow<String> = context.dataStore.data.map { preferences ->
        val url = preferences[BACKEND_URL_KEY] ?: DEFAULT_BACKEND_URL
        Log.d(TAG, "backendUrlFlow: Read backend URL: '$url'")
        url
    }

    suspend fun saveSelectedBuses(buses: List<String>) {
        val value = buses.joinToString(",")
        Log.i(TAG, "saveSelectedBuses: Saving list: '$value'")
        context.dataStore.edit { preferences ->
            preferences[SELECTED_BUSES_KEY] = value
        }
    }

    suspend fun saveBackendUrl(url: String) {
        val sanitizedUrl = BusTrackerClient.sanitizeUrl(url)
        Log.i(TAG, "saveBackendUrl: Saving backend URL: '$sanitizedUrl'")
        context.dataStore.edit { preferences ->
            preferences[BACKEND_URL_KEY] = sanitizedUrl
        }
    }
}

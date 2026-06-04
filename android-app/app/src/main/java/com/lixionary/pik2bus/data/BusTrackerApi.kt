package com.lixionary.pik2bus.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.io.IOException
import java.util.concurrent.TimeUnit

data class LatLng(val lat: Double, val lng: Double)

data class Stop(
    val id: String,
    val name: String,
    val location: LatLng,
    val seq: Int?,
    val polyline: List<LatLng>?,
    val schedule: List<Any>?
)

data class Route(
    val id: String?,
    val slug: String,
    val code: String,
    val name: String,
    val type: String,
    val operator: String,
    val is_active: Boolean,
    val color: String,
    val initial_map_center: LatLng?,
    val initial_zoom: Int?
)

data class BusPosition(
    val position_id: String?,
    val vehicle_id: String,
    val route_slug: String,
    val route_id: String?,
    val plate_number: String,
    val operator: String,
    val location: LatLng,
    val speed_kmh: Double,
    val bearing: Int?,
    val last_seen_at: String,
    val last_stop_id: String?,
    val last_stop_location: LatLng?,
    val next_stop_id: String?,
    val next_stop_location: LatLng?,
    val next_stop_seq: Int?,
    val eta_seconds: Int?,
    val distance_to_next_m: Double?,
    val trip_id: String?,
    val trip_headsign: String?,
    val image_url: String?
)

interface BusTrackerService {
    @GET("routes")
    suspend fun getRoutes(): List<Route>

    @GET("routes/{slug}/stops")
    suspend fun getStops(@Path("slug") slug: String): List<Stop>
}

class BusTrackerClient(private val baseUrl: String) {
    private val gson = Gson()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    val service: BusTrackerService = retrofit.create(BusTrackerService::class.java)

    /**
     * Connect to the SSE /track endpoint and emit lists of live bus positions.
     */
    fun trackBuses(buses: List<String>): Flow<List<BusPosition>> = flow {
        if (buses.isEmpty()) {
            emit(emptyList())
            return@flow
        }

        val busesParam = buses.joinToString(",")
        val url = "${baseUrl}track?buses=$busesParam"
        
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // Infinite timeout for SSE stream
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .build()

        val listType = object : TypeToken<List<BusPosition>>() {}.type

        while (currentCoroutineContext().isActive) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Server returned error: $response")
                    
                    val body = response.body ?: throw IOException("Empty response body")
                    val reader = body.charStream().buffered()
                    
                    var line: String? = reader.readLine()
                    while (currentCoroutineContext().isActive && line != null) {
                        if (line.startsWith("data:")) {
                            val dataJson = line.removePrefix("data:").trim()
                            if (dataJson.isNotEmpty()) {
                                try {
                                    val positions: List<BusPosition> = gson.fromJson(dataJson, listType)
                                    emit(positions)
                                } catch (e: Exception) {
                                    // Ignore parse errors on malformed payloads
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                // Emit empty list or handle errors
                emit(emptyList())
                // Wait before retrying connection
                delay(5000)
            }
        }
    }.flowOn(Dispatchers.IO)
}

package com.lixionary.pik2bus.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lixionary.pik2bus.data.BusPosition
import com.lixionary.pik2bus.data.DataStoreManager
import com.lixionary.pik2bus.data.Route
import com.lixionary.pik2bus.data.Stop
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.IconFactory
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.annotations.PolylineOptions
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng as MapboxLatLng
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import kotlinx.coroutines.launch

// Custom inline OSM raster style to remain completely free and self-contained
private val OSM_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": [
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
      ],
      "tileSize": 256,
      "attribution": "Map data © OpenStreetMap contributors"
    }
  },
  "layers": [
    {
      "id": "osm",
      "type": "raster",
      "source": "osm",
      "minzoom": 0,
      "maxzoom": 19
    }
  ]
}
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    route: Route?,
    stops: List<Stop>,
    busPositions: List<BusPosition>,
    isLocationPermissionGranted: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var mapboxMapState by remember { mutableStateOf<MapboxMap?>(null) }
    
    val routePolylines = remember { mutableListOf<com.mapbox.mapboxsdk.annotations.Polyline>() }
    val stopMarkers = remember { mutableListOf<com.mapbox.mapboxsdk.annotations.Marker>() }
    val busMarkers = remember { mutableListOf<com.mapbox.mapboxsdk.annotations.Marker>() }

    val coroutineScope = rememberCoroutineScope()
    val dataStoreManager = remember { DataStoreManager(context) }
    val locationManager = remember { context.getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager }

    var initialCameraCenter by remember { mutableStateOf<MapboxLatLng?>(null) }
    var initialCameraSet by remember { mutableStateOf(false) }
    var hasCenteredOnNewLocation by remember { mutableStateOf(false) }
    var currentUserLocation by remember { mutableStateOf<Location?>(null) }
    var selectedPlateNumber by remember { mutableStateOf<String?>(null) }

    // Reset selected bus plate number on tab/route switch
    LaunchedEffect(route) {
        selectedPlateNumber = null
    }

    // Listen for marker clicks to track selected bus plate number (persists info window on SSE updates)
    LaunchedEffect(mapboxMapState) {
        val map = mapboxMapState ?: return@LaunchedEffect
        map.setOnMarkerClickListener { marker ->
            val title = marker.title ?: ""
            if (title.startsWith("Bus ")) {
                val plate = title.substringAfter("Bus ").substringBefore(" (").trim()
                selectedPlateNumber = plate
                Log.d("MapScreen", "Marker clicked. Tracking selected bus plate: '$plate'")
            } else {
                selectedPlateNumber = null
            }
            false // return false so Mapbox default behavior (show info window) still runs
        }
        map.addOnMapClickListener {
            selectedPlateNumber = null
            true
        }
    }

    // Load initial user location from DataStore on startup (with Jakarta default fallback)
    LaunchedEffect(Unit) {
        dataStoreManager.lastUserLocationFlow.collect { pair ->
            if (initialCameraCenter == null) {
                if (pair != null) {
                    initialCameraCenter = MapboxLatLng(pair.first, pair.second)
                    Log.d("MapScreen", "Loaded last user location from DataStore: $pair")
                } else {
                    initialCameraCenter = MapboxLatLng(-6.2088, 106.8456) // Jakarta default
                    Log.d("MapScreen", "No stored user location, defaulting to Jakarta center")
                }
            }
        }
    }

    // Set camera to initial user location once loaded
    LaunchedEffect(initialCameraCenter, mapboxMapState) {
        val map = mapboxMapState ?: return@LaunchedEffect
        val center = initialCameraCenter ?: return@LaunchedEffect
        if (!initialCameraSet) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(center, 12.0))
            initialCameraSet = true
            Log.d("MapScreen", "Centered initial map camera to: ${center.latitude}, ${center.longitude}")
        }
    }

    // Handle location listener to update DataStore and auto-center once
    DisposableEffect(isLocationPermissionGranted, mapboxMapState) {
        val map = mapboxMapState
        if (!isLocationPermissionGranted || map == null) return@DisposableEffect onDispose {}
        
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Log.d("MapScreen", "Location updated: ${location.latitude}, ${location.longitude}")
                currentUserLocation = location
                // Save coordinates to DataStore
                coroutineScope.launch {
                    dataStoreManager.saveLastUserLocation(location.latitude, location.longitude)
                }
                // Zoom automatically to user location once when first coordinates are received
                if (!hasCenteredOnNewLocation) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            MapboxLatLng(location.latitude, location.longitude),
                            14.0
                        )
                    )
                    hasCenteredOnNewLocation = true
                    Log.d("MapScreen", "Auto-centered camera on new live user location")
                }
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        
        try {
            val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else {
                LocationManager.NETWORK_PROVIDER
            }
            
            // Get last known location to set initial center if nothing is stored
            val lastKnown = locationManager.getLastKnownLocation(provider)
            if (lastKnown != null) {
                currentUserLocation = lastKnown
                if (initialCameraCenter == null) {
                    initialCameraCenter = MapboxLatLng(lastKnown.latitude, lastKnown.longitude)
                }
            }
            
            locationManager.requestLocationUpdates(provider, 5000L, 5f, listener)
            Log.d("MapScreen", "Registered location updates listener with provider: $provider")
        } catch (e: SecurityException) {
            Log.e("MapScreen", "SecurityException requesting location updates", e)
        }
        
        onDispose {
            locationManager.removeUpdates(listener)
            Log.d("MapScreen", "Removed location updates listener")
        }
    }

    // Enable Mapbox LocationComponent (draws user blue dot on the map)
    LaunchedEffect(mapboxMapState, isLocationPermissionGranted) {
        val map = mapboxMapState ?: return@LaunchedEffect
        if (isLocationPermissionGranted) {
            map.getStyle { style ->
                try {
                    val locationComponent = map.locationComponent
                    locationComponent.activateLocationComponent(
                        com.mapbox.mapboxsdk.location.LocationComponentActivationOptions
                            .builder(context, style)
                            .useDefaultLocationEngine(true)
                            .build()
                    )
                    locationComponent.isLocationComponentEnabled = true
                    Log.d("MapScreen", "Mapbox LocationComponent activated successfully")
                } catch (e: Exception) {
                    Log.e("MapScreen", "Error activating LocationComponent", e)
                }
            }
        }
    }

    // Nearest bus calculation
    val approachingBus = remember(busPositions) {
        busPositions
            .filter { it.eta_seconds != null && it.eta_seconds > 0 }
            .minByOrNull { it.eta_seconds!! }
    }

    // Draw route polylines and stops only when route, stops or map change (removes blinking)
    LaunchedEffect(route, stops, mapboxMapState) {
        val map = mapboxMapState ?: return@LaunchedEffect
        
        // Remove existing static polylines and stop markers
        routePolylines.forEach { map.removePolyline(it) }
        routePolylines.clear()
        
        stopMarkers.forEach { map.removeMarker(it) }
        stopMarkers.clear()
        
        // Draw route polylines in solid single color (Indigo #3F51B5)
        stops.forEach { stop ->
            stop.polyline?.let { points ->
                if (points.isNotEmpty()) {
                    val lineOptions = PolylineOptions()
                        .addAll(points.map { MapboxLatLng(it.lat, it.lng) })
                        .color(Color.parseColor("#3F51B5"))
                        .width(4f)
                    val polyline = map.addPolyline(lineOptions)
                    routePolylines.add(polyline)
                }
            }
        }
        
        // Create custom stop icon
        val stopIcon = createStopIcon(context)
        
        // Add stops markers
        stops.forEach { stop ->
            val marker = map.addMarker(
                MarkerOptions()
                    .position(MapboxLatLng(stop.location.lat, stop.location.lng))
                    .title(stop.name)
                    .snippet("Stop Seq: ${stop.seq ?: 0}")
                    .icon(stopIcon)
            )
            stopMarkers.add(marker)
        }

        // Adjust camera to route's initial center once the route catalog/stops load
        route?.initial_map_center?.let { center ->
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    MapboxLatLng(center.lat, center.lng),
                    (route.initial_zoom ?: 12).toDouble()
                )
            )
            Log.d("MapScreen", "Animated camera to route's center: ${center.lat}, ${center.lng}")
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                Mapbox.getInstance(context)
                MapView(context).apply {
                    onCreate(null)
                    getMapAsync { mapboxMap ->
                        mapboxMapState = mapboxMap

                        // Set strict boundaries for Greater Jakarta
                        val jakartaBounds = com.mapbox.mapboxsdk.geometry.LatLngBounds.Builder()
                            .include(MapboxLatLng(-5.90, 106.60)) // NE limit
                            .include(MapboxLatLng(-6.45, 107.10)) // SW limit
                            .build()
                        mapboxMap.setLatLngBoundsForCameraTarget(jakartaBounds)
                        mapboxMap.setMinZoomPreference(10.0)

                        mapboxMap.setStyle(Style.Builder().fromJson(OSM_STYLE_JSON)) {
                            // Style loaded
                        }
                    }
                }
            },
            update = { mapView ->
                val map = mapboxMapState ?: return@AndroidView
                
                // Remove previous bus markers only (never clear the map completely)
                busMarkers.forEach { map.removeMarker(it) }
                busMarkers.clear()
                
                // Add active bus position markers
                busPositions.forEach { bus ->
                    // Determine route direction (return trip vs outbound)
                    val stopIndex = stops.indexOfFirst { it.id == (bus.next_stop_id ?: bus.last_stop_id) }
                    val isReturnTrip = if (stopIndex != -1 && stops.size > 1) {
                        stopIndex >= stops.size / 2
                    } else {
                        // Fallback: check trip_headsign keywords for TransJakarta
                        bus.trip_headsign?.contains("Blok M", ignoreCase = true) == true ||
                        bus.trip_headsign?.contains("Balai Kota", ignoreCase = true) == true ||
                        bus.trip_headsign?.contains("Kota", ignoreCase = true) == true
                    }

                    // Get color for direction
                    val routeColorStr = route?.color ?: "#FFEC792D"
                    val busColorInt = getDirectionColor(routeColorStr, isReturnTrip)

                    // Create dynamic bus icon with direction color and bearing (triangle symbol without dot)
                    val busIcon = createBusIcon(context, busColorInt, bus.bearing)

                    val directionLabel = if (isReturnTrip) " (Return)" else " (Outbound)"

                    val marker = map.addMarker(
                        MarkerOptions()
                            .position(MapboxLatLng(bus.location.lat, bus.location.lng))
                            .title("Bus ${bus.plate_number}$directionLabel")
                            .snippet("Speed: ${bus.speed_kmh} km/h | Operator: ${bus.operator}")
                            .icon(busIcon)
                    )
                    busMarkers.add(marker)

                    // Restore selected info window if this bus was selected
                    if (bus.plate_number == selectedPlateNumber) {
                        map.selectMarker(marker)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Calculate the closest 4 buses to the user's location
        val closestBuses = remember(busPositions, currentUserLocation) {
            val userLoc = currentUserLocation
            if (userLoc == null || busPositions.isEmpty()) {
                emptyList()
            } else {
                busPositions.map { bus ->
                    val results = FloatArray(1)
                    Location.distanceBetween(
                        userLoc.latitude,
                        userLoc.longitude,
                        bus.location.lat,
                        bus.location.lng,
                        results
                    )
                    val distanceMeters = results[0]

                    val stopIndex = stops.indexOfFirst { it.id == (bus.next_stop_id ?: bus.last_stop_id) }
                    val isReturnTrip = if (stopIndex != -1 && stops.size > 1) {
                        stopIndex >= stops.size / 2
                    } else {
                        bus.trip_headsign?.contains("Blok M", ignoreCase = true) == true ||
                        bus.trip_headsign?.contains("Balai Kota", ignoreCase = true) == true ||
                        bus.trip_headsign?.contains("Kota", ignoreCase = true) == true
                    }
                    val directionLabel = if (isReturnTrip) "Return" else "Outbound"

                    bus to Triple(bus.plate_number, distanceMeters, directionLabel)
                }
                .sortedBy { it.second.second }
                .take(4)
            }
        }

        // Floating action button for GPS center-on-my-location
        if (isLocationPermissionGranted && mapboxMapState != null) {
            FloatingActionButton(
                onClick = {
                    val map = mapboxMapState ?: return@FloatingActionButton
                    val loc = map.locationComponent.lastKnownLocation
                    if (loc != null) {
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                MapboxLatLng(loc.latitude, loc.longitude),
                                14.0
                            )
                        )
                    } else {
                        Toast.makeText(context, "Waiting for GPS signal...", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 16.dp,
                        bottom = if (busPositions.isEmpty() || currentUserLocation == null) {
                            120.dp
                        } else {
                            (120 + closestBuses.size * 56).dp
                        }
                    ),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "My Location"
                )
            }
        }

        // Overlay card showing info of the 4 closest buses to the user's location
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Closest Buses (Active Tab)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                when {
                    busPositions.isEmpty() -> {
                        Text(
                            text = "No active buses currently tracking.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    currentUserLocation == null -> {
                        Text(
                            text = "Waiting for GPS signal to calculate distances...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    else -> {
                        closestBuses.forEachIndexed { index, (_, info) ->
                            val (plate, distance, direction) = info
                            val distanceText = if (distance >= 1000f) {
                                "%.1f km".format(distance / 1000f)
                            } else {
                                "%.0f m".format(distance)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = plate,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Direction: $direction",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                Text(
                                    text = distanceText,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (index < closestBuses.size - 1) {
                                Divider(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getDirectionColor(baseColorStr: String, isReturn: Boolean): Int {
    val baseColor = try {
        Color.parseColor(baseColorStr)
    } catch (e: Exception) {
        Color.parseColor("#FFEC792D") // default orange
    }
    if (!isReturn) return baseColor
    
    val hsv = FloatArray(3)
    Color.colorToHSV(baseColor, hsv)
    hsv[0] = (hsv[0] + 180f) % 360f // shift hue by 180 degrees
    return Color.HSVToColor(hsv)
}

private fun createStopIcon(context: android.content.Context): com.mapbox.mapboxsdk.annotations.Icon {
    val size = 84
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        isAntiAlias = true
    }

    // Outer white circle
    paint.color = Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

    // Inner blue circle representing bus stop
    paint.color = Color.parseColor("#1976D2") // Accent blue
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 9f, paint)

    // Center white dot
    paint.color = Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size / 5f, paint)

    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

private fun createBusIcon(context: android.content.Context, colorInt: Int, bearing: Int?): com.mapbox.mapboxsdk.annotations.Icon {
    val size = 144
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        isAntiAlias = true
    }

    val cx = size / 2f
    val cy = size / 2f

    if (bearing != null) {
        canvas.save()
        canvas.rotate(bearing.toFloat(), cx, cy)
    }

    // Centroid of this triangle is exactly at (cx, cy)
    val path = Path().apply {
        moveTo(cx, cy - 50f)
        lineTo(cx - 38f, cy + 25f)
        lineTo(cx + 38f, cy + 25f)
        close()
    }

    // 1. Draw the white outline (stroke) first to ensure high visibility
    paint.color = Color.WHITE
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 12f
    paint.strokeJoin = Paint.Join.ROUND
    canvas.drawPath(path, paint)

    // 2. Draw the inner colored fill
    paint.color = colorInt
    paint.style = Paint.Style.FILL
    canvas.drawPath(path, paint)

    if (bearing != null) {
        canvas.restore()
    }

    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

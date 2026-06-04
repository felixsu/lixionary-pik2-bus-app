package com.lixionary.pik2bus.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lixionary.pik2bus.data.BusPosition
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
    modifier: Modifier = Modifier
) {
    var mapboxMapState by remember { mutableStateOf<MapboxMap?>(null) }
    
    // Nearest bus calculation
    val approachingBus = remember(busPositions) {
        busPositions
            .filter { it.eta_seconds != null && it.eta_seconds > 0 }
            .minByOrNull { it.eta_seconds!! }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                Mapbox.getInstance(context)
                MapView(context).apply {
                    onCreate(null)
                    getMapAsync { mapboxMap ->
                        mapboxMapState = mapboxMap
                        mapboxMap.setStyle(Style.Builder().fromJson(OSM_STYLE_JSON)) {
                            // Style loaded
                        }
                        
                        // Center camera if route coordinates are specified
                        route?.initial_map_center?.let { center ->
                            mapboxMap.moveCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    MapboxLatLng(center.lat, center.lng),
                                    (route.initial_zoom ?: 12).toDouble()
                                )
                            )
                        }
                    }
                }
            },
            update = { mapView ->
                val map = mapboxMapState ?: return@AndroidView
                val context = mapView.context
                
                // Clear existing markers/polylines
                map.clear()
                
                // Draw route polylines
                stops.forEach { stop ->
                    stop.polyline?.let { points ->
                        if (points.isNotEmpty()) {
                            val lineOptions = PolylineOptions()
                                .addAll(points.map { MapboxLatLng(it.lat, it.lng) })
                                .color(Color.parseColor(route?.color ?: "#FF888888"))
                                .width(4f)
                            map.addPolyline(lineOptions)
                        }
                    }
                }

                // Create custom stop icon
                val stopIcon = createStopIcon(context)

                // Add stops markers
                stops.forEach { stop ->
                    map.addMarker(
                        MarkerOptions()
                            .position(MapboxLatLng(stop.location.lat, stop.location.lng))
                            .title(stop.name)
                            .snippet("Stop Seq: ${stop.seq ?: 0}")
                            .icon(stopIcon)
                    )
                }

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

                    // Create dynamic bus icon with direction color and bearing
                    val busIcon = createBusIcon(context, busColorInt, bus.bearing)

                    val directionLabel = if (isReturnTrip) " (Return)" else " (Outbound)"

                    map.addMarker(
                        MarkerOptions()
                            .position(MapboxLatLng(bus.location.lat, bus.location.lng))
                            .title("Bus ${bus.plate_number}$directionLabel")
                            .snippet("Speed: ${bus.speed_kmh} km/h | Operator: ${bus.operator}")
                            .icon(busIcon)
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay card showing info of closest approaching bus
        approachingBus?.let { bus ->
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
                        text = "Approaching Bus: ${bus.plate_number} (${bus.operator})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "ETA: ${bus.eta_seconds?.let { "${it / 60}m ${it % 60}s" } ?: "Unknown"}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    bus.distance_to_next_m?.let { dist ->
                        Text(
                            text = "Distance: ${"%.0f".format(dist)} m to next stop",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

    // Draw main circle shadow/border (white outline)
    paint.color = Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size / 3.2f, paint)

    // Draw main circle with bus color
    paint.color = colorInt
    canvas.drawCircle(size / 2f, size / 2f, size / 3.2f - 9f, paint)

    // Draw inner bus indicator (e.g. small white dot or symbol)
    paint.color = Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, size / 8f, paint)

    // If bearing is present, draw a directional pointer arrow
    if (bearing != null) {
        val path = Path()
        val radius = size / 3.2f
        val cx = size / 2f
        val cy = size / 2f
        
        // Convert bearing to radians and calculate coordinates
        // 0 degrees is North in Mapbox, which is up (-Y in canvas)
        val angleRad = Math.toRadians((bearing - 90).toDouble())
        
        // Tip of the arrow (outside the circle)
        val tipX = cx + (radius + 18f) * Math.cos(angleRad).toFloat()
        val tipY = cy + (radius + 18f) * Math.sin(angleRad).toFloat()
        
        // Base corners of the arrow triangle
        val baseLeftAngle = angleRad + Math.toRadians(140.0)
        val baseRightAngle = angleRad - Math.toRadians(140.0)
        
        val leftX = cx + (radius - 6f) * Math.cos(baseLeftAngle).toFloat()
        val leftY = cy + (radius - 6f) * Math.sin(baseLeftAngle).toFloat()
        
        val rightX = cx + (radius - 6f) * Math.cos(baseRightAngle).toFloat()
        val rightY = cy + (radius - 6f) * Math.sin(baseRightAngle).toFloat()
        
        path.moveTo(tipX, tipY)
        path.lineTo(leftX, leftY)
        path.lineTo(rightX, rightY)
        path.close()
        
        // Draw white outline for arrow
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL_AND_STROKE
        canvas.drawPath(path, paint)
        
        // Draw colored inner arrow
        paint.color = colorInt
        // Re-scale slightly smaller for outline effect
        val innerTipX = cx + (radius + 12f) * Math.cos(angleRad).toFloat()
        val innerTipY = cy + (radius + 12f) * Math.sin(angleRad).toFloat()
        val innerLeftX = cx + (radius - 3f) * Math.cos(baseLeftAngle).toFloat()
        val innerLeftY = cy + (radius - 3f) * Math.sin(baseLeftAngle).toFloat()
        val innerRightX = cx + (radius - 3f) * Math.cos(baseRightAngle).toFloat()
        val innerRightY = cy + (radius - 3f) * Math.sin(baseRightAngle).toFloat()
        
        val innerPath = Path().apply {
            moveTo(innerTipX, innerTipY)
            lineTo(innerLeftX, innerLeftY)
            lineTo(innerRightX, innerRightY)
            close()
        }
        canvas.drawPath(innerPath, paint)
    }

    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

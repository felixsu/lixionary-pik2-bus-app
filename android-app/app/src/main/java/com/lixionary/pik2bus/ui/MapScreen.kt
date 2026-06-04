package com.lixionary.pik2bus.ui

import android.graphics.Color
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

                // Add stops markers
                stops.forEach { stop ->
                    map.addMarker(
                        MarkerOptions()
                            .position(MapboxLatLng(stop.location.lat, stop.location.lng))
                            .title(stop.name)
                            .snippet("Stop Seq: ${stop.seq ?: 0}")
                    )
                }

                // Add active bus position markers
                busPositions.forEach { bus ->
                    map.addMarker(
                        MarkerOptions()
                            .position(MapboxLatLng(bus.location.lat, bus.location.lng))
                            .title("Bus ${bus.plate_number}")
                            .snippet("Speed: ${bus.speed_kmh} km/h | Operator: ${bus.operator}")
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

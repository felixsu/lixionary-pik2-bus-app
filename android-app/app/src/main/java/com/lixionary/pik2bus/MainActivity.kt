package com.lixionary.pik2bus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.lixionary.pik2bus.data.*
import com.lixionary.pik2bus.ui.MapScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "Pik2Bus_Main"

class MainActivity : ComponentActivity() {
    private lateinit var dataStoreManager: DataStoreManager
    private var trackingJob: Job? = null
    private val isLocationPermissionGrantedState = mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineLocationGranted || coarseLocationGranted) {
            Log.i(TAG, "Location permission granted by user")
            isLocationPermissionGrantedState.value = true
        } else {
            Log.w(TAG, "Location permission denied by user")
            isLocationPermissionGrantedState.value = false
            Toast.makeText(
                this,
                "Location permission denied. Map will start at Jakarta center.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataStoreManager = DataStoreManager(this)

        // Check if permissions are already granted
        isLocationPermissionGrantedState.value = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // Request permissions if not granted
        if (!isLocationPermissionGrantedState.value) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        setContent {
            val isLocationPermissionGranted by isLocationPermissionGrantedState
            var backendUrl by remember { mutableStateOf(DataStoreManager.DEFAULT_BACKEND_URL) }
            var selectedBuses by remember { mutableStateOf<List<String>>(emptyList()) }
            var availableRoutes by remember { mutableStateOf<List<Route>>(emptyList()) }
            var activeStops by remember { mutableStateOf<List<Stop>>(emptyList()) }
            var busPositions by remember { mutableStateOf<List<BusPosition>>(emptyList()) }
            
            var activeTabSlug by remember { mutableStateOf("") }
            var isEditingBuses by remember { mutableStateOf(false) }
            var isLoading by remember { mutableStateOf(true) }

            // Load saved settings on startup
            LaunchedEffect(Unit) {
                Log.d(TAG, "onCreate: Launched startup configuration loads")
                lifecycleScope.launch {
                    dataStoreManager.backendUrlFlow.collectLatest { url ->
                        Log.d(TAG, "Startup: Loaded backendUrl from preferences: '$url'")
                        backendUrl = url
                    }
                }
                lifecycleScope.launch {
                    dataStoreManager.selectedBusesFlow.collectLatest { buses ->
                        Log.d(TAG, "Startup: Loaded selectedBuses from preferences: $buses")
                        selectedBuses = buses
                        if (buses.isEmpty()) {
                            Log.i(TAG, "Startup: Selected buses list is empty. Redirecting to settings screen.")
                            isEditingBuses = true
                        } else {
                            if (activeTabSlug.isEmpty() || !buses.contains(activeTabSlug)) {
                                activeTabSlug = buses.first()
                                Log.d(TAG, "Startup: Set active tab slug to: '$activeTabSlug'")
                            }
                        }
                    }
                }
            }

            // Fetch available routes and stops based on selected config
            LaunchedEffect(backendUrl, isEditingBuses) {
                Log.d(TAG, "LaunchedEffect(backendUrl, isEditingBuses) triggered: url='$backendUrl', isEditingBuses=$isEditingBuses")
                if (backendUrl.isNotEmpty() && !isEditingBuses) {
                    isLoading = true
                    try {
                        Log.i(TAG, "Fetching available routes from backend URL: '$backendUrl'")
                        val client = BusTrackerClient(backendUrl)
                        availableRoutes = client.service.getRoutes()
                        Log.i(TAG, "Successfully loaded ${availableRoutes.size} available routes from backend")
                        isLoading = false
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching available routes from backend URL '$backendUrl'", e)
                        isLoading = false
                        Toast.makeText(
                            this@MainActivity,
                            "Failed to connect to backend: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            // Fetch stops for the active focused tab route
            LaunchedEffect(backendUrl, activeTabSlug) {
                Log.d(TAG, "LaunchedEffect(backendUrl, activeTabSlug) triggered: url='$backendUrl', activeTabSlug='$activeTabSlug'")
                if (backendUrl.isNotEmpty() && activeTabSlug.isNotEmpty()) {
                    try {
                        Log.i(TAG, "Fetching stops for route: '$activeTabSlug'")
                        val client = BusTrackerClient(backendUrl)
                        activeStops = client.service.getStops(activeTabSlug)
                        Log.i(TAG, "Successfully loaded ${activeStops.size} stops for route '$activeTabSlug'")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching stops for route '$activeTabSlug' from URL '$backendUrl'", e)
                        activeStops = emptyList()
                    }
                }
            }

            // Connect to real-time SSE stream for only the active focused tab route
            LaunchedEffect(backendUrl, activeTabSlug, isEditingBuses) {
                Log.d(TAG, "LaunchedEffect(backendUrl, activeTabSlug, isEditingBuses) triggered: url='$backendUrl', activeTabSlug='$activeTabSlug', isEditingBuses=$isEditingBuses")
                trackingJob?.cancel()
                if (backendUrl.isNotEmpty() && activeTabSlug.isNotEmpty() && !isEditingBuses) {
                    Log.i(TAG, "Starting SSE tracking job for active bus: '$activeTabSlug' at URL: '$backendUrl'")
                    trackingJob = lifecycleScope.launch {
                        val client = BusTrackerClient(backendUrl)
                        client.trackBuses(listOf(activeTabSlug)).collectLatest { positions ->
                            Log.d(TAG, "SSE emitted ${positions.size} positions for active bus: $activeTabSlug")
                            busPositions = positions
                        }
                    }
                } else {
                    Log.i(TAG, "SSE tracking job skipped or stopped. (URL empty, or no active tab, or editing preferences)")
                    busPositions = emptyList()
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFFEC792D),
                    background = androidx.compose.ui.graphics.Color(0xFF121212),
                    surface = androidx.compose.ui.graphics.Color(0xFF1E1E1E)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        isEditingBuses -> {
                            SettingsScreen(
                                currentUrl = backendUrl,
                                selectedBuses = selectedBuses,
                                onSave = { newUrl, newBuses ->
                                    Log.i(TAG, "Saving new configuration: URL='$newUrl', buses=$newBuses")
                                    lifecycleScope.launch {
                                        dataStoreManager.saveBackendUrl(newUrl)
                                        dataStoreManager.saveSelectedBuses(newBuses)
                                        isEditingBuses = false
                                    }
                                },
                                onCancel = {
                                    Log.d(TAG, "Cancel configuration edit clicked. Selected buses: $selectedBuses")
                                    if (selectedBuses.isNotEmpty()) {
                                        isEditingBuses = false
                                    } else {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Please configure at least one bus to track",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )
                        }
                        else -> {
                            MainTrackerScreen(
                                selectedBuses = selectedBuses,
                                activeTabSlug = activeTabSlug,
                                availableRoutes = availableRoutes,
                                stops = activeStops,
                                busPositions = busPositions,
                                isLocationPermissionGranted = isLocationPermissionGranted,
                                onTabSelect = { activeTabSlug = it },
                                onOpenSettings = { isEditingBuses = true }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        trackingJob?.cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentUrl: String,
    selectedBuses: List<String>,
    onSave: (String, List<String>) -> Unit,
    onCancel: () -> Unit
) {
    var urlInput by remember { mutableStateOf(currentUrl) }
    var isCheckingHealth by remember { mutableStateOf(false) }
    var isBackendHealthy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val fetchedRoutes = remember { mutableStateListOf<Route>() }
    val chosenBuses = remember { mutableStateListOf<String>().apply { addAll(selectedBuses) } }
    val scope = rememberCoroutineScope()

    // Automatically check health of the current saved URL on load
    LaunchedEffect(Unit) {
        Log.d(TAG, "SettingsScreen: Auto healthcheck started. currentUrl='$currentUrl'")
        if (currentUrl.isNotEmpty()) {
            isCheckingHealth = true
            try {
                val client = BusTrackerClient(currentUrl)
                Log.d(TAG, "SettingsScreen: Fetching healthcheck for currentUrl='$currentUrl' (sanitized to '${client.baseUrl}')...")
                val healthRes = client.service.checkHealth()
                Log.i(TAG, "SettingsScreen: Health check response for '$currentUrl': $healthRes")
                if (healthRes["status"] == "ok") {
                    Log.d(TAG, "SettingsScreen: Health check successful. Fetching routes...")
                    val routes = client.service.getRoutes()
                    fetchedRoutes.clear()
                    fetchedRoutes.addAll(routes)
                    isBackendHealthy = true
                    Log.i(TAG, "SettingsScreen: Auto healthcheck successful. Loaded ${routes.size} routes.")
                } else {
                    errorMessage = "Backend healthcheck failed: status is not ok"
                    Log.w(TAG, "SettingsScreen: Health check response status is not 'ok': $healthRes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "SettingsScreen: Exception in auto healthcheck for URL: '$currentUrl'", e)
                errorMessage = "Saved backend unreachable: ${e.localizedMessage ?: e.message}"
            } finally {
                isCheckingHealth = false
            }
        } else {
            Log.d(TAG, "SettingsScreen: No saved currentUrl to healthcheck on startup.")
        }
    }

    // Reset health state when URL input changes
    LaunchedEffect(urlInput) {
        Log.d(TAG, "SettingsScreen: URL input changed. urlInput='$urlInput', currentUrl='$currentUrl'")
        if (urlInput != currentUrl) {
            Log.d(TAG, "SettingsScreen: urlInput differs from currentUrl. Resetting health and fetched routes.")
            isBackendHealthy = false
            fetchedRoutes.clear()
            errorMessage = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Selection") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text(
                text = "Backend Base URL (VPN IP)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("http://192.168.1.100:8000/") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            Log.i(TAG, "SettingsScreen: 'Connect' clicked. Checking URL input: '$urlInput'")
                            isCheckingHealth = true
                            errorMessage = null
                            isBackendHealthy = false
                            try {
                                val client = BusTrackerClient(urlInput)
                                Log.d(TAG, "SettingsScreen: Requesting health from client.baseUrl='${client.baseUrl}'...")
                                val healthRes = client.service.checkHealth()
                                Log.i(TAG, "SettingsScreen: Connect healthcheck response: $healthRes")
                                if (healthRes["status"] == "ok") {
                                    Log.d(TAG, "SettingsScreen: Health check OK. Loading available routes...")
                                    val routes = client.service.getRoutes()
                                    fetchedRoutes.clear()
                                    fetchedRoutes.addAll(routes)
                                    isBackendHealthy = true
                                    Log.i(TAG, "SettingsScreen: Successfully connected. Loaded ${routes.size} routes.")
                                } else {
                                    errorMessage = "Backend healthcheck failed: status is not ok"
                                    Log.w(TAG, "SettingsScreen: Health check status not 'ok': $healthRes")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "SettingsScreen: Exception checking health for URL input '$urlInput'", e)
                                errorMessage = "Failed to connect: ${e.localizedMessage ?: e.message}"
                            } finally {
                                isCheckingHealth = false
                            }
                        }
                    },
                    enabled = urlInput.isNotEmpty() && !isCheckingHealth
                ) {
                    if (isCheckingHealth) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Connect")
                    }
                }
            }
            
            errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Select Buses to Track (Max 5)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isBackendHealthy) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            if (!isBackendHealthy) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Verify your backend URL to view available routes",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                var searchQuery by remember { mutableStateOf("") }
                val filteredRoutes = remember(searchQuery, fetchedRoutes.toList()) {
                    fetchedRoutes.filter { route ->
                        route.code.contains(searchQuery, ignoreCase = true) ||
                        route.name.contains(searchQuery, ignoreCase = true) ||
                        route.operator.contains(searchQuery, ignoreCase = true)
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    placeholder = { Text("Search bus (e.g. ASG2, T31)") },
                    singleLine = true,
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search"
                                )
                            }
                        }
                    }
                )

                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredRoutes) { route ->
                        val isChecked = chosenBuses.contains(route.slug)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) {
                                        chosenBuses.remove(route.slug)
                                    } else {
                                        if (chosenBuses.size < 5) {
                                            chosenBuses.add(route.slug)
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp)
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        if (chosenBuses.size < 5) {
                                            chosenBuses.add(route.slug)
                                        }
                                    } else {
                                        chosenBuses.remove(route.slug)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "${route.code} — ${route.name}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Operator: ${route.operator} (${route.type})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = { onSave(urlInput, chosenBuses.toList()) },
                    enabled = isBackendHealthy && chosenBuses.isNotEmpty()
                ) {
                    Text("Save Config")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTrackerScreen(
    selectedBuses: List<String>,
    activeTabSlug: String,
    availableRoutes: List<Route>,
    stops: List<Stop>,
    busPositions: List<BusPosition>,
    isLocationPermissionGranted: Boolean,
    onTabSelect: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val activeRoute = remember(activeTabSlug, availableRoutes) {
        availableRoutes.find { it.slug == activeTabSlug }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pik2Bus Tracker", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            // Selected buses tab bar
            if (selectedBuses.isNotEmpty()) {
                TabRow(
                    selectedTabIndex = selectedBuses.indexOf(activeTabSlug).coerceAtLeast(0),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    selectedBuses.forEach { slug ->
                        val routeCode = availableRoutes.find { it.slug == slug }?.code ?: slug
                        Tab(
                            selected = activeTabSlug == slug,
                            onClick = { onTabSelect(slug) },
                            text = { Text(routeCode) }
                        )
                    }
                }
            }

            // Embedded MapScreen
            MapScreen(
                route = activeRoute,
                stops = stops,
                busPositions = busPositions,
                isLocationPermissionGranted = isLocationPermissionGranted,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

package com.lixionary.pik2bus

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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

class MainActivity : ComponentActivity() {
    private lateinit var dataStoreManager: DataStoreManager
    private var trackingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataStoreManager = DataStoreManager(this)

        setContent {
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
                lifecycleScope.launch {
                    dataStoreManager.backendUrlFlow.collectLatest { url ->
                        backendUrl = url
                    }
                }
                lifecycleScope.launch {
                    dataStoreManager.selectedBusesFlow.collectLatest { buses ->
                        selectedBuses = buses
                        if (buses.isEmpty()) {
                            isEditingBuses = true
                        } else {
                            if (activeTabSlug.isEmpty() || !buses.contains(activeTabSlug)) {
                                activeTabSlug = buses.first()
                            }
                        }
                    }
                }
            }

            // Fetch available routes and stops based on selected config
            LaunchedEffect(backendUrl, isEditingBuses) {
                if (backendUrl.isNotEmpty() && !isEditingBuses) {
                    isLoading = true
                    try {
                        val client = BusTrackerClient(backendUrl)
                        availableRoutes = client.service.getRoutes()
                        isLoading = false
                    } catch (e: Exception) {
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
                if (backendUrl.isNotEmpty() && activeTabSlug.isNotEmpty()) {
                    try {
                        val client = BusTrackerClient(backendUrl)
                        activeStops = client.service.getStops(activeTabSlug)
                    } catch (e: Exception) {
                        activeStops = emptyList()
                    }
                }
            }

            // Connect to real-time SSE stream whenever selected list or backend URL changes
            LaunchedEffect(backendUrl, selectedBuses, isEditingBuses) {
                trackingJob?.cancel()
                if (backendUrl.isNotEmpty() && selectedBuses.isNotEmpty() && !isEditingBuses) {
                    trackingJob = lifecycleScope.launch {
                        val client = BusTrackerClient(backendUrl)
                        client.trackBuses(selectedBuses).collectLatest { positions ->
                            busPositions = positions
                        }
                    }
                } else {
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
                                    lifecycleScope.launch {
                                        dataStoreManager.saveBackendUrl(newUrl)
                                        dataStoreManager.saveSelectedBuses(newBuses)
                                        isEditingBuses = false
                                    }
                                },
                                onCancel = {
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
        if (currentUrl.isNotEmpty()) {
            isCheckingHealth = true
            try {
                val client = BusTrackerClient(currentUrl)
                val healthRes = client.service.checkHealth()
                if (healthRes["status"] == "ok") {
                    val routes = client.service.getRoutes()
                    fetchedRoutes.clear()
                    fetchedRoutes.addAll(routes)
                    isBackendHealthy = true
                }
            } catch (e: Exception) {
                errorMessage = "Saved backend unreachable: ${e.localizedMessage ?: e.message}"
            } finally {
                isCheckingHealth = false
            }
        }
    }

    // Reset health state when URL input changes
    LaunchedEffect(urlInput) {
        if (urlInput != currentUrl) {
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
                            isCheckingHealth = true
                            errorMessage = null
                            isBackendHealthy = false
                            try {
                                val client = BusTrackerClient(urlInput)
                                val healthRes = client.service.checkHealth()
                                if (healthRes["status"] == "ok") {
                                    val routes = client.service.getRoutes()
                                    fetchedRoutes.clear()
                                    fetchedRoutes.addAll(routes)
                                    isBackendHealthy = true
                                } else {
                                    errorMessage = "Backend healthcheck failed: status is not ok"
                                }
                            } catch (e: Exception) {
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
                text = "Select Buses to Track (Max 3)",
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
                LazyColumn(
                    modifier = Modifier.weight(1f)
                ) {
                    items(fetchedRoutes) { route ->
                        val isChecked = chosenBuses.contains(route.slug)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) {
                                        chosenBuses.remove(route.slug)
                                    } else {
                                        if (chosenBuses.size < 3) {
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
                                        if (chosenBuses.size < 3) {
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
                modifier = Modifier.weight(1f)
            )
        }
    }
}

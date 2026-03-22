package com.llmhub.llmhub.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llmhub.llmhub.viewmodels.BrowserAgentViewModel
import com.llmhub.llmhub.viewmodels.ChatViewModel
import com.llmhub.llmhub.viewmodels.ChatViewModelFactory
import androidx.compose.material.icons.filled.*
import com.llmhub.llmhub.components.ModelSelectorCard
import com.llmhub.llmhub.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import com.llmhub.llmhub.ui.components.GlassySurface
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import com.llmhub.llmhub.LlmHubApplication
import com.llmhub.llmhub.data.LLMModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserAgentScreen(
    onNavigateBack: () -> Unit,
    onNavigateToModels: () -> Unit,
    viewModelFactory: ChatViewModelFactory = (LocalContext.current as ComponentActivity).let {
        val app = it.application as LlmHubApplication
        ChatViewModelFactory(app, app.chatRepository, it)
    }
) {
    val viewModel: BrowserAgentViewModel = viewModel(factory = viewModelFactory)
    val chatViewModel: ChatViewModel = viewModel(factory = viewModelFactory) // To get available models
    
    val logs by viewModel.logs.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val status by viewModel.currentStatus.collectAsState()
    val availableModels by chatViewModel.availableModels.collectAsState()
    val currentlyLoadedModel by chatViewModel.currentlyLoadedModel.collectAsState()
    val isModelLoaded by chatViewModel.isModelLoaded.collectAsState()
    val isLoadingModel by chatViewModel.isLoadingModel.collectAsState()
    val selectedModel by chatViewModel.selectedModel.collectAsState()
    val selectedBackend by chatViewModel.selectedBackend.collectAsState()
    val selectedNpuDeviceId by chatViewModel.selectedNpuDeviceId.collectAsState()
    
    var goal by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://www.google.com") }
    var showSettings by remember { mutableStateOf(false) }
    var isControlsVisible by remember { mutableStateOf(true) }
    
    val context = LocalContext.current
    
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stop()
        }
    }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            isControlsVisible = false
        }
    }

    LaunchedEffect(Unit) {
        chatViewModel.refreshModels(context)
        chatViewModel.syncCurrentlyLoadedModel()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browser Agent") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { isControlsVisible = !isControlsVisible }) {
                        Icon(
                            if (isControlsVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle Controls"
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Settings")
                    }
                    if (isRunning) {
                        IconButton(onClick = { viewModel.stop() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Model Selection and Goal Input with Visibility Toggle
            if (isControlsVisible) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Model: ${currentlyLoadedModel?.name ?: "None loaded"}", style = MaterialTheme.typography.labelMedium)
                        if (currentlyLoadedModel == null) {
                            if (availableModels.isEmpty()) {
                                Button(onClick = onNavigateToModels, modifier = Modifier.fillMaxWidth()) {
                                    Text("Download Models")
                                }
                            } else {
                                Button(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Select Model")
                                }
                            }
                        } else {
                            LaunchedEffect(currentlyLoadedModel) {
                                viewModel.setModel(currentlyLoadedModel!!)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        
                        OutlinedTextField(
                            value = goal,
                            onValueChange = { goal = it },
                            label = { Text("What should the agent do?") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3
                        )
                        
                        Button(
                            onClick = { viewModel.startGoal(goal) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isRunning && goal.isNotBlank() && currentlyLoadedModel != null
                        ) {
                            Text(if (isRunning) "Running..." else "Start Agent")
                        }
                    }
                }
            } else if (!isRunning) {
                 // Suggest showing controls if not running and hidden
                 TextButton(
                     onClick = { isControlsVisible = true },
                     modifier = Modifier.align(Alignment.CenterHorizontally)
                 ) {
                     Text("Show Controls")
                 }
            }

            // WebView
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            viewModel.attachWebView(this)
                            loadUrl(url)
                        }
                    },
                    update = { view ->
                        // view.loadUrl(url) // Avoid reloading on every recomposition
                    }
                )
            }
            
            // Draggable Resize Handle
            var logHeight by remember { mutableStateOf(200.dp) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: Float ->
                            change.consume()
                            val dragAmountDp = with(density) { dragAmount.toDp() }
                            logHeight = (logHeight - dragAmountDp).coerceIn(100.dp, 600.dp)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), shape = androidx.compose.foundation.shape.CircleShape)
                )
            }
            
            // Logs
            GlassySurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(logHeight),
                cornerRadius = 0.dp
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Status: $status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(logs) { log ->
                            Text(log, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Model Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                ModelSelectorCard(
                    models = availableModels,
                    selectedModel = selectedModel,
                    onModelSelected = { chatViewModel.selectModel(it) },
                    selectedBackend = selectedBackend,
                    selectedNpuDeviceId = selectedNpuDeviceId,
                    onBackendSelected = { backend, deviceId -> chatViewModel.selectBackend(backend, deviceId) },
                    onLoadModel = {
                        chatViewModel.loadModel()
                        showSettings = false
                    },
                    onUnloadModel = { chatViewModel.unloadModel() },
                    isLoading = isLoadingModel,
                    isModelLoaded = isModelLoaded,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

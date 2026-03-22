package com.llmhub.llmhub.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.llmhub.llmhub.LlmHubApplication
import com.llmhub.llmhub.services.GitHubRepository
import com.llmhub.llmhub.viewmodels.ChatViewModelFactory
import com.llmhub.llmhub.viewmodels.GitHubAgentViewModel
import com.llmhub.llmhub.viewmodels.GitHubAuthState
import com.llmhub.llmhub.ui.components.GlassySurface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.llmhub.llmhub.components.ChatSettingsSheet
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.llmhub.llmhub.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubAgentScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAuth: () -> Unit,
    onNavigateToModels: () -> Unit,
    viewModelFactory: ChatViewModelFactory = (LocalContext.current as ComponentActivity).let {
        val app = it.application as LlmHubApplication
        ChatViewModelFactory(app, app.chatRepository, it)
    }
) {
    val viewModel: GitHubAgentViewModel = viewModel(factory = viewModelFactory)
    val authState by viewModel.authState.collectAsState()
    val userProfile by viewModel.userProfile.collectAsState()
    val repositories by viewModel.repositories.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val cloningStatus by viewModel.cloningStatus.collectAsState()
    val currentProject by viewModel.currentProject.collectAsState()
    val clonedRepoNames by viewModel.clonedRepoNames.collectAsState()
    val isChatActive by viewModel.isChatActive.collectAsState()
    val messages by viewModel.messages.collectAsState()

    val availableModels by viewModel.availableModels.collectAsState()
    val currentlyLoadedModel by viewModel.currentlyLoadedModel.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val isLoadingModel by viewModel.isLoadingModel.collectAsState()
    val selectedBackend by viewModel.selectedBackend.collectAsState()
    val selectedNpuDeviceId by viewModel.selectedNpuDeviceId.collectAsState()

    val contextUsageFraction by viewModel.contextUsageFraction.collectAsState()
    val contextUsageLabel by viewModel.contextUsageLabel.collectAsState()

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showContextHint by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        viewModel.checkAuth()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("GitHub Agent", fontWeight = FontWeight.Bold)
                        currentlyLoadedModel?.let { model ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = model.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (viewModel.currentModelSupportsVision()) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.RemoveRedEye, contentDescription = null, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isChatActive) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .size(28.dp)
                                .clickable { showContextHint = true }
                        ) {
                            CircularProgressIndicator(
                                progress = contextUsageFraction,
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = contextUsageLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Icon(Icons.Default.Tune, contentDescription = "Settings")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (authState) {
                GitHubAuthState.Idle -> {
                    CircularProgressIndicator()
                }
                GitHubAuthState.Unauthenticated -> {
                    Icon(
                        imageVector = rememberGithubIcon(),
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "Connect to GitHub",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Login to browse your repositories and start coding with AI.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    Button(
                        onClick = onNavigateToAuth,
                        modifier = Modifier.fillMaxWidth(0.7f),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Icon(rememberGithubIcon(), contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connect with GitHub")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = { viewModel.logout() }) {
                        Text("Clear Session / Reset")
                    }
                }
                GitHubAuthState.Authenticated -> {
                    if (isChatActive) {
                        ChatView(
                            messages = messages,
                            isLoading = isLoading,
                            isLoadingModel = isLoadingModel,
                            currentlyLoadedModel = currentlyLoadedModel,
                            selectedModel = selectedModel,
                            onSendMessage = { viewModel.sendMessage(it) },
                            onBack = { /* TODO: Close chat */ },
                            onNavigateToModels = onNavigateToModels,
                            viewModel = viewModel
                        )
                    } else if (currentProject != null) {
                        ProjectView(
                            repo = currentProject!!,
                            onBack = { viewModel.deselectProject() },
                            onIndex = { viewModel.indexCurrentProject() },
                            onPull = { viewModel.pullRepo(currentProject!!) },
                            onPush = { 
                                // Show push dialog
                                // For now, just a placeholder or mock
                                viewModel.pushRepo(currentProject!!, "Update from AI")
                            },
                            onDelete = { viewModel.deleteRepo(currentProject!!) },
                            onStartChat = { viewModel.startChat() },
                            isLoading = isLoading,
                            status = cloningStatus
                        )
                    } else if (isLoading && repositories.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // User Profile Header
                            item {
                                userProfile?.let { user ->
                                    UserProfileHeader(user = user, onLogout = { viewModel.logout() })
                                }
                            }

                            item {
                                Text(
                                    "Your Repositories",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }

                            items(repositories) { repo ->
                                val isCloned = clonedRepoNames.contains(repo.name)
                                RepositoryItem(
                                    repo = repo, 
                                    isCloned = isCloned,
                                    onClick = { 
                                        if (isCloned) {
                                            viewModel.selectProject(repo)
                                        } else {
                                            viewModel.cloneRepo(repo)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Cloning Progress Dialog
            if (cloningStatus != null && currentProject == null) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text("Repository Action") },
                    text = {
                        Column {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(cloningStatus ?: "")
                        }
                    },
                    confirmButton = { }
                )
            }
        }

        if (showSettingsSheet) {
            ChatSettingsSheet(
                availableModels = availableModels,
                initialSelectedModel = selectedModel,
                initialSelectedBackend = selectedBackend,
                initialSelectedNpuDeviceId = selectedNpuDeviceId,
                currentlyLoadedModel = currentlyLoadedModel,
                isLoadingModel = isLoadingModel,
                onModelSelected = { viewModel.selectModel(it) },
                onBackendSelected = { backend, deviceId -> viewModel.selectBackend(backend, deviceId) },
                onLoadModel = { model, maxTokens, topK, topP, temperature, backend, deviceId, disableVision, disableAudio, nGpuLayers, enableThinking ->
                    showSettingsSheet = false
                    viewModel.setGenerationParameters(maxTokens, topK, topP, temperature, nGpuLayers, enableThinking)
                    if (backend != null) {
                        viewModel.selectBackend(backend, deviceId)
                        viewModel.switchModelWithBackend(model, backend, disableVision, disableAudio)
                    } else {
                        viewModel.switchModel(model)
                    }
                },
                onUnloadModel = { viewModel.unloadModel() },
                onDismiss = { showSettingsSheet = false }
            )
        }

        if (showContextHint) {
            AlertDialog(
                onDismissRequest = { showContextHint = false },
                title = { Text("Context Usage") },
                text = {
                    Text("This percentage shows how much of the AI model's 'memory' (context window) is currently used by your chat history.\n\n" +
                         "When it reaches 100%, the model may start forgetting older messages in this session.")
                },
                confirmButton = {
                    TextButton(onClick = { showContextHint = false }) {
                        Text("Got it")
                    }
                }
            )
        }
    }
}

@Composable
fun ProjectView(
    repo: GitHubRepository,
    onBack: () -> Unit,
    onIndex: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onDelete: () -> Unit,
    onStartChat: () -> Unit,
    isLoading: Boolean,
    status: String?
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = repo.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Text(status ?: "Processing...", style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.height(16.dp))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Project Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = onIndex,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Re-index for AI")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = onStartChat,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    enabled = !isLoading
                ) {
                    Icon(Icons.Default.Chat, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Chat with Agent")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Manage Repository", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onPull,
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Pull", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = onPush,
                modifier = Modifier.weight(1f),
                enabled = !isLoading
            ) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Push", style = MaterialTheme.typography.labelSmall)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            enabled = !isLoading
        ) {
            Icon(Icons.Default.Delete, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Delete from Device")
        }
    }
}

@Composable
fun ChatView(
    messages: List<com.llmhub.llmhub.viewmodels.ChatMessage>,
    isLoading: Boolean,
    isLoadingModel: Boolean,
    currentlyLoadedModel: com.llmhub.llmhub.data.LLMModel?,
    selectedModel: com.llmhub.llmhub.data.LLMModel?,
    onSendMessage: (String) -> Unit,
    onBack: () -> Unit,
    onNavigateToModels: () -> Unit,
    viewModel: GitHubAgentViewModel
) {
    var inputText by remember { mutableStateOf("") }
    
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            if (messages.size <= 1 && !isLoading && !isLoadingModel) {
                item {
                    GitHubWelcomeMessage(
                        currentModel = currentlyLoadedModel?.name,
                        onNavigateToModels = onNavigateToModels,
                        hasDownloadedModels = viewModel.hasDownloadedModels()
                    )
                }
            }

            if (isLoadingModel) {
                item {
                    val name = (selectedModel ?: currentlyLoadedModel)?.name ?: "AI Model"
                    GitHubModelLoadingIndicator(modelName = name)
                }
            }

            items(messages.filter { it.role != "system" }) { msg ->
                ChatMessageItem(msg)
            }
            if (isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.CenterStart) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        }

        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var text by remember { mutableStateOf("") }
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask something about the code...") },
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
                IconButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            onSendMessage(text)
                            text = ""
                        }
                    },
                    enabled = !isLoading
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
fun ChatMessageItem(msg: com.llmhub.llmhub.viewmodels.ChatMessage) {
    val isUser = msg.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            )
        ) {
            Text(
                text = msg.content,
                modifier = Modifier.padding(12.dp),
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun UserProfileHeader(user: com.llmhub.llmhub.services.GitHubUser, onLogout: () -> Unit) {
    GlassySurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = user.avatar_url,
                contentDescription = "Avatar",
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.name ?: user.login,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "@${user.login}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onLogout) {
                Icon(Icons.Default.Logout, contentDescription = "Logout")
            }
        }
    }
}

@Composable
fun RepositoryItem(repo: GitHubRepository, isCloned: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCloned) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = if (repo.private) Icons.Default.Lock else Icons.Default.Public,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = repo.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                
                if (isCloned) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    ) {
                        Text(
                            text = "LOCAL",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (!repo.description.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = repo.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                repo.language?.let { lang ->
                    LanguageBadge(lang)
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Text(
                    text = repo.full_name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun GitHubWelcomeMessage(
    currentModel: String?,
    onNavigateToModels: () -> Unit,
    hasDownloadedModels: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text("GitHub Coding Agent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            if (!hasDownloadedModels) {
                Text("No models downloaded. Please download a model to start chatting.", textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onNavigateToModels) {
                    Text("Download a Model")
                }
            } else if (currentModel == null) {
                Text("Please load an AI model from the settings (Tune icon) to start coding.", textAlign = TextAlign.Center)
            } else {
                Text("Ready to help with your project! Use the model: $currentModel", textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun GitHubModelLoadingIndicator(modelName: String) {
    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Reverse)
    )
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp).graphicsLayer(scaleX = scale, scaleY = scale),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text("Loading $modelName...", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun LanguageBadge(language: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape
    ) {
        Text(
            text = language,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

package com.llmhub.llmhub.screens

import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.llmhub.llmhub.utils.SyntaxHighlighter
import com.llmhub.llmhub.utils.EditorTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val committedUsageFraction by viewModel.committedUsageFraction.collectAsState()
    val contextUsageLabel by viewModel.contextUsageLabel.collectAsState()

    val isProjectStructureVisible by viewModel.isProjectStructureVisible.collectAsState()
    val isCodeViewingVisible by viewModel.isCodeViewingVisible.collectAsState()
    val selectedFilePath by viewModel.selectedFilePath.collectAsState()
    val viewedFileContent by viewModel.viewedFileContent.collectAsState()
    val expandedFolders by viewModel.expandedFolders.collectAsState()
    val currentChatInput by viewModel.currentChatInput.collectAsState()

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
                    if (currentProject != null) {
                        IconButton(onClick = { viewModel.showProjectStructure() }) {
                            Icon(Icons.Default.AccountTree, contentDescription = "Project Structure")
                        }
                    }
                    if (isChatActive) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .size(28.dp)
                                .clickable { showContextHint = true }
                        ) {
                            CircularProgressIndicator(
                                progress = { contextUsageFraction },
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            CircularProgressIndicator(
                                progress = { committedUsageFraction },
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
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues = paddingValues).fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
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
                        contentPadding = PaddingValues(all = 16.dp)
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
                            contentPadding = PaddingValues(all = 16.dp),
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

        // Project Structure Overlay
        if (isProjectStructureVisible) {
            ProjectStructureOverlay(
                viewModel = viewModel,
                expandedFolders = expandedFolders,
                onClose = { viewModel.hideProjectStructure() }
            )
        }

        // Code Viewer/Editor Overlay
        if (isCodeViewingVisible && selectedFilePath != null) {
            val isEditingMode by viewModel.isEditingMode.collectAsState()
            val editorText by viewModel.editorText.collectAsState()
            val editorSettings by viewModel.editorSettings.collectAsState()
            val errorMarkers by viewModel.errorMarkers.collectAsState()
            val isCheckingErrors by viewModel.isCheckingErrors.collectAsState()
            
            CodeEditorOverlay(
                path = selectedFilePath!!,
                content = viewedFileContent ?: "",
                isEditing = isEditingMode,
                editorText = editorText,
                editorSettings = editorSettings,
                errorMarkers = errorMarkers,
                isCheckingErrors = isCheckingErrors,
                onClose = { viewModel.closeFile() },
                onToggleEdit = { viewModel.toggleEditMode() },
                onTextChange = { viewModel.onEditorTextChange(it) },
                onSave = { viewModel.saveEditedFile() },
                onCheckErrors = { viewModel.checkCodeForErrors() },
                onAddContext = { line -> 
                    line?.let { viewModel.addLineToContext(selectedFilePath!!, it) }
                        ?: viewModel.addFileToContext(selectedFilePath!!)
                }
            )
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
        modifier = Modifier.fillMaxSize().padding(all = 16.dp),
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
            Column(modifier = Modifier.padding(all = 16.dp)) {
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
    val currentChatInput by viewModel.currentChatInput.collectAsState()
    
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
                    Box(modifier = Modifier.fillMaxWidth().padding(all = 8.dp), contentAlignment = Alignment.CenterStart) {
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
                modifier = Modifier.padding(all = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = currentChatInput,
                    onValueChange = { viewModel.setChatInput(it) },
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
                        if (currentChatInput.isNotBlank()) {
                            onSendMessage(currentChatInput)
                            viewModel.setChatInput("")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectStructureOverlay(
    viewModel: GitHubAgentViewModel,
    expandedFolders: Set<String>,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Project Structure") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            )
            
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(all = 16.dp)
            ) {
                item {
                    FileTreeItem(
                        path = "",
                        name = "root",
                        isDir = true,
                        level = 0,
                        expandedFolders = expandedFolders,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FileTreeItem(
    path: String,
    name: String,
    isDir: Boolean,
    level: Int,
    expandedFolders: Set<String>,
    viewModel: GitHubAgentViewModel
) {
    val isExpanded = expandedFolders.contains(path)
    var showMenu by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (level * 16).dp)
                .clickable(
                    onClick = {
                        if (isDir) {
                            viewModel.toggleFolder(path)
                        } else {
                            viewModel.openFile(path)
                        }
                    }
                )
                .combinedClickable(
                    onClick = {
                        if (isDir) viewModel.toggleFolder(path)
                        else viewModel.openFile(path)
                    },
                    onLongClick = {
                        if (!isDir) showMenu = true
                    }
                )
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isDir) {
                    if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder
                } else {
                    Icons.Default.Description
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (showMenu) {
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Add to chat for context") },
                        onClick = {
                            viewModel.addFileToContext(path)
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Open for editing") },
                        onClick = {
                            // TODO: Implement simple editing or just open for now
                            viewModel.openFile(path)
                            showMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    )
                }
            }
        }

        if (isDir && isExpanded) {
            val children = viewModel.listFiles(path) ?: emptyList()
            children.forEach { childName ->
                val childIsDir = childName.endsWith("/")
                val cleanChildName = childName.removeSuffix("/")
                val childPath = if (path.isEmpty()) cleanChildName else "$path/$cleanChildName"
                FileTreeItem(
                    path = childPath,
                    name = cleanChildName,
                    isDir = childIsDir,
                    level = level + 1,
                    expandedFolders = expandedFolders,
                    viewModel = viewModel
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorOverlay(
    path: String,
    content: String,
    isEditing: Boolean,
    editorText: String,
    editorSettings: com.llmhub.llmhub.utils.EditorSettings,
    errorMarkers: List<GitHubAgentViewModel.ErrorMarker>,
    isCheckingErrors: Boolean,
    onClose: () -> Unit,
    onToggleEdit: () -> Unit,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onCheckErrors: () -> Unit,
    onAddContext: (Int?) -> Unit
) {
    val theme = EditorTheme.getByName(editorSettings.themeName.name)
    val extension = path.substringAfterLast(".")
    val highlighter = remember(theme, extension) { SyntaxHighlighter(theme) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = theme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            text = path.substringAfterLast("/"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            color = theme.text
                        )
                        if (isEditing) {
                            Text(
                                "Editing Mode", 
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.keyword
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = theme.text)
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = onCheckErrors) {
                            Icon(Icons.Default.BugReport, contentDescription = "Check for Bugs", tint = theme.keyword)
                        }
                        IconButton(onClick = onSave) {
                            Icon(Icons.Default.Save, contentDescription = "Save", tint = theme.string)
                        }
                    }
                    IconButton(onClick = onToggleEdit) {
                        Icon(
                            if (isEditing) Icons.Default.Visibility else Icons.Default.Edit,
                            contentDescription = "Toggle Edit Mode",
                            tint = theme.text
                        )
                    }
                    if (!isEditing) {
                        IconButton(onClick = { onAddContext(null) }) {
                            Icon(Icons.Default.Add, contentDescription = "Add whole file to context", tint = theme.text)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.background.copy(alpha = 0.9f),
                    titleContentColor = theme.text
                )
            )

            if (isCheckingErrors) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = theme.keyword,
                    trackColor = theme.background
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isEditing) {
                    val scrollState = rememberScrollState()
                    val hScrollState = rememberScrollState()
                    
                    // Controlled text field value for cursor placement and navigation
                    var textFieldValue by remember(content) {
                        mutableStateOf(TextFieldValue(content))
                    }
                    
                    // Effect to handle navigation to specific lines (e.g., from error list)
                    var targetLineToFocus by remember { mutableStateOf<Int?>(null) }
                    
                    // Auto-focus first error once analysis completes and window is open
                    LaunchedEffect(errorMarkers, isCheckingErrors) {
                        if (!isCheckingErrors && errorMarkers.isNotEmpty() && targetLineToFocus == null) {
                            targetLineToFocus = errorMarkers.first().lineNumber
                        }
                    }
                    
                    LaunchedEffect(targetLineToFocus) {
                        targetLineToFocus?.let { line ->
                            val lines = textFieldValue.text.lines()
                            if (line >= 1 && line <= lines.size) {
                                // 1. Calculate character offset for cursor
                                var offset = 0
                                for (i in 0 until line - 1) {
                                    offset += lines[i].length + 1 // +1 for \n
                                }
                                textFieldValue = textFieldValue.copy(
                                    selection = androidx.compose.ui.text.TextRange(offset)
                                )
                                
                                // 2. Animated scroll to the line
                                // Estimation: line height (roughly 22dp for 14sp text)
                                // We don't have direct access to line metrics in BasicTextField easily,
                                // but we can approximate it for common mobile displays.
                                val estimatedLineHeight = 19 * 2.5 // Approximate density conversion
                                scrollState.animateScrollTo((line - 1) * estimatedLineHeight.toInt())
                            }
                            targetLineToFocus = null
                        }
                    }
                    
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            // Gutter (Line Numbers)
                            Column(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(44.dp)
                                    .background(theme.background.copy(alpha = 0.5f))
                                    .verticalScroll(scrollState)
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.End
                            ) {
                                val lineCount = textFieldValue.text.lines().size
                                for (i in 1..lineCount) {
                                    val isErrorLine = errorMarkers.any { it.lineNumber == i }
                                    Text(
                                        text = i.toString(),
                                        style = TextStyle(
                                            color = if (isErrorLine) theme.error else theme.comment.copy(alpha = 0.6f),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.End
                                        ),
                                        modifier = Modifier.padding(end = 8.dp, top = 2.dp, bottom = 2.dp)
                                    )
                                }
                            }
                            
                            // Editor Area
                            BasicTextField(
                                value = textFieldValue,
                                onValueChange = { 
                                    textFieldValue = it
                                    // Basic indentation assistance if enabled
                                    if (editorSettings.useTabHelper && it.text.endsWith("\n") && it.text.length > content.length) {
                                        val lines = it.text.split("\n")
                                        if (lines.size > 1) {
                                            val lastLine = lines[lines.size - 2]
                                            val leadingSpaces = lastLine.takeWhile { it.isWhitespace() }
                                            onTextChange(it.text + leadingSpaces)
                                        } else {
                                            onTextChange(it.text)
                                        }
                                    } else {
                                        onTextChange(it.text)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 2.dp, vertical = 4.dp)
                                    .verticalScroll(scrollState)
                                    .horizontalScroll(hScrollState),
                                textStyle = TextStyle(
                                    color = theme.text,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    lineHeight = 19.sp
                                ),
                                cursorBrush = SolidColor(theme.text),
                                visualTransformation = { text ->
                                    TransformedText(
                                        highlighter.highlight(text.text, extension),
                                        OffsetMapping.Identity
                                    )
                                }
                            )
                        }

                        // Interactive Error List at the Bottom
                        if (errorMarkers.isNotEmpty()) {
                            GlassySurface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 160.dp)
                                    .padding(all = 8.dp)
                            ) {
                                Column(modifier = Modifier.padding(all = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.BugReport, contentDescription = null, tint = theme.error, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Issues Found (${errorMarkers.size})", style = MaterialTheme.typography.titleSmall, color = theme.text)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LazyColumn {
                                        items(errorMarkers) { error ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { targetLineToFocus = error.lineNumber }
                                                    .padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(theme.error)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Line ${error.lineNumber}: ",
                                                    style = TextStyle(
                                                        color = theme.keyword,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                )
                                                Text(
                                                    text = error.message,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = theme.text,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val lines = content.lines()
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().background(theme.background)
                    ) {
                        itemsIndexed(lines) { index, line ->
                            var showLineMenu by remember { mutableStateOf(false) }
                            val isError = errorMarkers.any { it.lineNumber == index + 1 }
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { showLineMenu = true }
                                    )
                                    .background(if (isError) theme.error.copy(alpha = 0.15f) else Color.Transparent)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = (index + 1).toString(),
                                    modifier = Modifier.width(32.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isError) theme.error else theme.comment,
                                    textAlign = TextAlign.End
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = highlighter.highlight(line, extension),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = theme.text
                                )
                            }
                            
                            if (showLineMenu) {
                                DropdownMenu(
                                    expanded = showLineMenu,
                                    onDismissRequest = { showLineMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Add to chat for context (Line ${index + 1})") },
                                        onClick = {
                                            onAddContext(index + 1)
                                            showLineMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
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
                modifier = Modifier.padding(all = 12.dp),
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
            modifier = Modifier.padding(all = 16.dp),
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
        Column(modifier = Modifier.padding(all = 16.dp)) {
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
        modifier = Modifier.fillMaxWidth().padding(all = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(all = 24.dp),
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
        modifier = Modifier.fillMaxWidth().padding(all = 16.dp).graphicsLayer(scaleX = scale, scaleY = scale),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(all = 16.dp), verticalAlignment = Alignment.CenterVertically) {
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

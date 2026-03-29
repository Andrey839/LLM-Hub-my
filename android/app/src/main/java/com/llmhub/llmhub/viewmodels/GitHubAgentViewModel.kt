package com.llmhub.llmhub.viewmodels

import java.io.File

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.llmhub.services.GitHubAuthManager
import com.llmhub.llmhub.services.GitHubRepository
import com.llmhub.llmhub.services.GitHubUser
import com.llmhub.llmhub.services.GitHubService
import com.llmhub.llmhub.services.GitHubServiceImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.llmhub.llmhub.embedding.ContextChunk
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.data.ModelAvailabilityProvider
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map as flowMap
import kotlinx.serialization.json.*

// JSON extension helpers
fun JsonElement.asJsonObject() = this as? JsonObject ?: throw Exception("Not an object")
fun JsonElement.asString() = (this as? JsonPrimitive)?.content ?: ""
operator fun JsonObject.get(key: String) = this[key]

sealed class GitHubAuthState {
    object Idle : GitHubAuthState()
    object Authenticated : GitHubAuthState()
    object Unauthenticated : GitHubAuthState()
}

data class ChatMessage(
    val role: String,
    val content: String
)

class GitHubAgentViewModel(
    private val context: Context,
    private val inferenceService: com.llmhub.llmhub.inference.InferenceService
) : ViewModel() {
    private val authManager = GitHubAuthManager(context)
    private val githubService: GitHubService = GitHubServiceImpl(context)
    private val codeProcessor = com.llmhub.llmhub.embedding.CodeProcessor(context)
    private val ragManager = com.llmhub.llmhub.embedding.RagServiceManager.getInstance(context)
    private val prefs = context.getSharedPreferences("github_agent_prefs", Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow<GitHubAuthState>(GitHubAuthState.Idle)
    val authState: StateFlow<GitHubAuthState> = _authState.asStateFlow()

    private val _repositories = MutableStateFlow<List<GitHubRepository>>(emptyList())
    val repositories: StateFlow<List<GitHubRepository>> = _repositories.asStateFlow()

    private val _userProfile = MutableStateFlow<GitHubUser?>(null)
    val userProfile: StateFlow<GitHubUser?> = _userProfile.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _cloningStatus = MutableStateFlow<String?>(null)
    val cloningStatus: StateFlow<String?> = _cloningStatus.asStateFlow()

    private val _currentProject = MutableStateFlow<GitHubRepository?>(null)
    val currentProject: StateFlow<GitHubRepository?> = _currentProject.asStateFlow()

    private val _clonedRepoNames = MutableStateFlow<Set<String>>(emptySet())
    val clonedRepoNames: StateFlow<Set<String>> = _clonedRepoNames.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _currentChatInput = MutableStateFlow("")
    val currentChatInput: StateFlow<String> = _currentChatInput.asStateFlow()

    private val _isChatActive = MutableStateFlow(false)
    val isChatActive: StateFlow<Boolean> = _isChatActive.asStateFlow()

    // Model management states
    private val _availableModels = MutableStateFlow<List<LLMModel>>(emptyList())
    val availableModels: StateFlow<List<LLMModel>> = _availableModels.asStateFlow()

    private val _isLoadingModel = MutableStateFlow(false)
    val isLoadingModel: StateFlow<Boolean> = _isLoadingModel.asStateFlow()

    private val _currentlyLoadedModel = MutableStateFlow<LLMModel?>(null)
    val currentlyLoadedModel: StateFlow<LLMModel?> = _currentlyLoadedModel.asStateFlow()

    private val _selectedModel = MutableStateFlow<LLMModel?>(null)
    val selectedModel: StateFlow<LLMModel?> = _selectedModel.asStateFlow()

    private val _selectedBackend = MutableStateFlow<LlmInference.Backend?>(null)
    val selectedBackend: StateFlow<LlmInference.Backend?> = _selectedBackend.asStateFlow()

    private val _selectedNpuDeviceId = MutableStateFlow<String?>(null)
    val selectedNpuDeviceId: StateFlow<String?> = _selectedNpuDeviceId.asStateFlow()

    private val _pendingContextSize = MutableStateFlow(0)

    private val _contextUsageState = combine(_messages, _currentChatInput, _currentlyLoadedModel, _selectedModel, _pendingContextSize) { msgs, input, curModel, selModel, pending ->
        val model = curModel ?: selModel
        val maxTokens = model?.contextWindowSize?.coerceAtLeast(1) ?: 2048
        
        val historyChars = msgs.sumOf { it.content.length }
        val inputChars = input.length
        val baseSystemOverhead = 2500 
        val tagChars = estimateTagContentSize(listOf(input))
        
        val committedChars = historyChars + inputChars + baseSystemOverhead + tagChars
        val totalChars = committedChars + pending
        
        val committedTokens = (committedChars / 3.0).toInt().coerceAtLeast(0)
        val totalTokens = (totalChars / 3.0).toInt().coerceAtLeast(0)
        
        val committedFraction = (committedTokens.toFloat() / maxTokens.toFloat()).coerceIn(0f, 1f)
        val totalFraction = (totalTokens.toFloat() / maxTokens.toFloat()).coerceIn(0f, 1f)
        
        Triple(committedFraction, totalFraction, "${(totalFraction * 100).toInt()}%")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Triple(0f, 0f, "0%"))

    val contextUsageFraction = _contextUsageState.flowMap { it.second }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)
    val committedUsageFraction = _contextUsageState.flowMap { it.first }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)
    val contextUsageLabel = _contextUsageState.flowMap { it.third }.stateIn(viewModelScope, SharingStarted.Eagerly, "0%")

    private fun estimateTagContentSize(texts: List<String>): Int {
        var estimatedSize = 0
        val fileRegex = Regex("\\[FILE:(.*?)\\]")
        val repo = _currentProject.value ?: return 0
        
        texts.forEach { text ->
            fileRegex.findAll(text).forEach { match ->
                val path = match.groupValues[1]
                val file = File(context.filesDir, "github_projects/${repo.name}/$path")
                if (file.exists()) {
                    // Truncate estimation to our safety limit of 10,000 chars
                    estimatedSize += file.length().toInt().coerceAtMost(10000)
                }
            }
        }
        return estimatedSize
    }

    private val _isProjectStructureVisible = MutableStateFlow(false)
    val isProjectStructureVisible: StateFlow<Boolean> = _isProjectStructureVisible.asStateFlow()

    private val _isCodeViewingVisible = MutableStateFlow(false)
    val isCodeViewingVisible: StateFlow<Boolean> = _isCodeViewingVisible.asStateFlow()

    private val _selectedFilePath = MutableStateFlow<String?>(null)
    val selectedFilePath: StateFlow<String?> = _selectedFilePath.asStateFlow()

    private val _viewedFileContent = MutableStateFlow<String?>(null)
    val viewedFileContent: StateFlow<String?> = _viewedFileContent.asStateFlow()

    private val _expandedFolders = MutableStateFlow<Set<String>>(emptySet())
    val expandedFolders: StateFlow<Set<String>> = _expandedFolders.asStateFlow()

    private val _isEditingMode = MutableStateFlow(false)
    val isEditingMode: StateFlow<Boolean> = _isEditingMode.asStateFlow()

    private val _editorText = MutableStateFlow("")
    val editorText: StateFlow<String> = _editorText.asStateFlow()

    data class ErrorMarker(val lineNumber: Int, val message: String)
    private val _errorMarkers = MutableStateFlow<List<ErrorMarker>>(emptyList())
    val errorMarkers: StateFlow<List<ErrorMarker>> = _errorMarkers.asStateFlow()

    private val _isCheckingErrors = MutableStateFlow(false)
    val isCheckingErrors: StateFlow<Boolean> = _isCheckingErrors.asStateFlow()

    private val _editorSettings = MutableStateFlow(loadEditorSettings())
    val editorSettings: StateFlow<com.llmhub.llmhub.utils.EditorSettings> = _editorSettings.asStateFlow()


    init {
        scanLocalRepos()
        checkAuth()
        restoreLastProject()
        refreshModels()
        syncCurrentlyLoadedModel()
    }

    fun refreshModels() {
        viewModelScope.launch {
            _availableModels.value = ModelAvailabilityProvider.loadAvailableModels(context)
        }
    }

    fun syncCurrentlyLoadedModel() {
        viewModelScope.launch {
            _currentlyLoadedModel.value = inferenceService.getCurrentlyLoadedModel()
        }
    }

    fun selectModel(model: LLMModel) {
        _selectedModel.value = model
        if (_selectedBackend.value == null) {
            _selectedBackend.value = if (model.supportsGpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU
        }
        prefs.edit().putString("selected_model_name", model.name).apply()
    }

    fun selectBackend(backend: LlmInference.Backend, deviceId: String? = null) {
        _selectedBackend.value = backend
        _selectedNpuDeviceId.value = deviceId
        prefs.edit().putString("selected_backend", backend.name).apply()
        prefs.edit().putString("selected_npu_device", deviceId).apply()
    }

    fun setGenerationParameters(maxTokens: Int, topK: Int, topP: Float, temperature: Float, nGpuLayers: Int, enableThinking: Boolean) {
        inferenceService.setGenerationParameters(maxTokens, topK, topP, temperature, nGpuLayers, enableThinking)
    }

    fun loadModel(model: LLMModel, backend: LlmInference.Backend, disableVision: Boolean, disableAudio: Boolean, deviceId: String? = null) {
        _isLoadingModel.value = true
        viewModelScope.launch {
            try {
                val success = inferenceService.loadModel(model, backend, disableVision, disableAudio, deviceId)
                if (success) {
                    _currentlyLoadedModel.value = model
                }
            } finally {
                _isLoadingModel.value = false
            }
        }
    }

    fun switchModel(model: LLMModel) {
        val backend = _selectedBackend.value ?: (if (model.supportsGpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU)
        loadModel(model, backend, false, false, _selectedNpuDeviceId.value)
    }

    fun switchModelWithBackend(model: LLMModel, backend: LlmInference.Backend, disableVision: Boolean, disableAudio: Boolean) {
        loadModel(model, backend, disableVision, disableAudio, _selectedNpuDeviceId.value)
    }

    fun unloadModel() {
        viewModelScope.launch {
            inferenceService.unloadModel()
            _currentlyLoadedModel.value = null
        }
    }

    fun hasDownloadedModels(): Boolean = _availableModels.value.isNotEmpty()

    fun currentModelSupportsVision(): Boolean = _currentlyLoadedModel.value?.supportsVision == true
    fun currentModelSupportsAudio(): Boolean = _currentlyLoadedModel.value?.supportsAudio == true
    fun isAudioCurrentlyDisabled(): Boolean = inferenceService.isAudioCurrentlyDisabled()
    fun isGpuBackendEnabled(): Boolean = inferenceService.isGpuBackendEnabled()

    private fun scanLocalRepos() {
        val projectsDir = java.io.File(context.filesDir, "github_projects")
        if (projectsDir.exists()) {
            val names = projectsDir.listFiles { file -> file.isDirectory }?.map { it.name }?.toSet() ?: emptySet()
            _clonedRepoNames.value = names
        }
    }

    private fun restoreLastProject() {
        val lastName = prefs.getString("last_project_name", null)
        if (lastName != null) {
            // If we have repositories loaded, find it
            _repositories.value.find { it.name == lastName }?.let {
                _currentProject.value = it
            }
        }
    }

    fun checkAuth() {
        val token = authManager.getAccessToken()
        android.util.Log.e("GitHubAgentViewModel", "checkAuth: token is ${if (token == null) "NULL" else "present, length ${token.length}"}")
        if (token != null) {
            _authState.value = GitHubAuthState.Authenticated
            fetchData(token)
        } else {
            _authState.value = GitHubAuthState.Unauthenticated
        }
    }

    private fun fetchData(token: String) {
        viewModelScope.launch {
            android.util.Log.e("GitHubAgentViewModel", "Starting fetchData")
            _isLoading.value = true
            val user = githubService.getUserProfile(token)
            _userProfile.value = user
            android.util.Log.e("GitHubAgentViewModel", "User profile fetched: ${user?.login}")
            
            val repos = githubService.getRepositories(token)
            _repositories.value = repos
            android.util.Log.e("GitHubAgentViewModel", "Fetched ${repos.size} repositories")
            
            // Auto-restore last project if it exists in fetched list
            val lastName = prefs.getString("last_project_name", null)
            if (_currentProject.value == null && lastName != null) {
                repos.find { it.name == lastName }?.let { 
                    _currentProject.value = it 
                }
            }
            
            _isLoading.value = false
            scanLocalRepos()
        }
    }

    fun cloneRepo(repo: GitHubRepository) {
        val token = authManager.getAccessToken() ?: return
        val projectsDir = java.io.File(context.filesDir, "github_projects")
        if (!projectsDir.exists()) projectsDir.mkdirs()
        
        val targetDir = java.io.File(projectsDir, repo.name)
        
        viewModelScope.launch {
            _isLoading.value = true
            val success = githubService.cloneRepository(token, repo, targetDir) { status ->
                _cloningStatus.value = status
            }
            if (success) {
                _currentProject.value = repo
                prefs.edit().putString("last_project_name", repo.name).apply()
                scanLocalRepos()
            }
            _isLoading.value = false
        }
    }

    fun deleteRepo(repo: GitHubRepository) {
        viewModelScope.launch {
            _isLoading.value = true
            _cloningStatus.value = "Deleting ${repo.name} and RAG index..."
            
            // 1. Remove from RAG
            codeProcessor.removeProject(repo.name)
            
            // 2. Delete folder
            val projectsDir = java.io.File(context.filesDir, "github_projects")
            val targetDir = java.io.File(projectsDir, repo.name)
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            
            // 3. Update state
            scanLocalRepos()
            if (_currentProject.value?.name == repo.name) {
                _currentProject.value = null
                prefs.edit().remove("last_project_name").apply()
            }
            
            _cloningStatus.value = "Repository ${repo.name} deleted."
            _isLoading.value = false
            kotlinx.coroutines.delay(2000)
            _cloningStatus.value = null
        }
    }

    fun pullRepo(repo: GitHubRepository) {
        val token = authManager.getAccessToken() ?: return
        val targetDir = java.io.File(context.filesDir, "github_projects/${repo.name}")
        
        viewModelScope.launch {
            _isLoading.value = true
            githubService.pullRepository(token, repo, targetDir) { status ->
                _cloningStatus.value = status
            }
            _isLoading.value = false
            kotlinx.coroutines.delay(2000)
            _cloningStatus.value = null
        }
    }

    fun pushRepo(repo: GitHubRepository, commitMessage: String) {
        val token = authManager.getAccessToken() ?: return
        val targetDir = java.io.File(context.filesDir, "github_projects/${repo.name}")
        
        viewModelScope.launch {
            _isLoading.value = true
            githubService.pushChanges(token, repo, targetDir, commitMessage) { status ->
                _cloningStatus.value = status
            }
            _isLoading.value = false
            kotlinx.coroutines.delay(2000)
            _cloningStatus.value = null
        }
    }

    fun selectProject(repo: GitHubRepository) {
        _currentProject.value = repo
        prefs.edit().putString("last_project_name", repo.name).apply()
    }

    fun deselectProject() {
        _currentProject.value = null
        prefs.edit().remove("last_project_name").apply()
    }

    fun indexCurrentProject() {
        val repo = _currentProject.value ?: return
        val projectDir = java.io.File(context.filesDir, "github_projects/${repo.name}")
        
        viewModelScope.launch {
            _isLoading.value = true
            codeProcessor.indexProject(repo.name, projectDir) { status ->
                _cloningStatus.value = status
            }
            _isLoading.value = false
        }
    }

    fun readFile(path: String): String? {
        val repo = _currentProject.value ?: return null
        val normalizedPath = path.removePrefix("/")
        val file = java.io.File(context.filesDir, "github_projects/${repo.name}/$normalizedPath")
        return if (file.exists() && file.isFile) file.readText() else null
    }

    fun listFiles(path: String): List<String>? {
        val repo = _currentProject.value ?: return null
        val normalizedPath = path.removePrefix("/")
        val dir = java.io.File(context.filesDir, "github_projects/${repo.name}/$normalizedPath")
        return if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.map { if (it.isDirectory) "${it.name}/" else it.name }
        } else null
    }

    fun writeFile(path: String, content: String): Boolean {
        val repo = _currentProject.value ?: return false
        val normalizedPath = path.removePrefix("/")
        val file = java.io.File(context.filesDir, "github_projects/${repo.name}/$normalizedPath")
        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun startChat() {
        _isChatActive.value = true
        if (_messages.value.isEmpty()) {
            _messages.value = listOf(
                ChatMessage(
                    role = "assistant",
                    content = "Hello! I'm your GitHub Coding Agent. I've indexed the project and I'm ready to help you with code analysis, feature implementation, or bug fixing. What would you like to do?"
                )
            )
        }
    }

    fun setChatInput(text: String) {
        _currentChatInput.value = text
    }

    fun showProjectStructure() {
        _isProjectStructureVisible.value = true
    }

    fun hideProjectStructure() {
        _isProjectStructureVisible.value = false
        _isCodeViewingVisible.value = false
    }

    fun toggleFolder(path: String) {
        val current = _expandedFolders.value
        if (current.contains(path)) {
            _expandedFolders.value = current - path
        } else {
            _expandedFolders.value = current + path
        }
    }

    fun openFile(path: String) {
        _selectedFilePath.value = path
        _viewedFileContent.value = readFile(path)
        _isCodeViewingVisible.value = true
    }

    fun closeFile() {
        _isCodeViewingVisible.value = false
        _isEditingMode.value = false
        _selectedFilePath.value = null
        _viewedFileContent.value = null
        _editorText.value = ""
        _errorMarkers.value = emptyList()
    }

    fun toggleEditMode() {
        if (!_isEditingMode.value) {
            _editorText.value = _viewedFileContent.value ?: ""
            _errorMarkers.value = emptyList()
        }
        _isEditingMode.value = !_isEditingMode.value
    }

    private var autoAnalysisJob: kotlinx.coroutines.Job? = null

    fun onEditorTextChange(newText: String) {
        _editorText.value = newText
        
        // Auto-analysis debounce
        if (_editorSettings.value.isAutoAnalysisEnabled) {
            autoAnalysisJob?.cancel()
            autoAnalysisJob = viewModelScope.launch {
                kotlinx.coroutines.delay(2500) // 2.5 second debounce
                checkCodeForErrors(isManual = false)
            }
        }
    }

    fun saveEditedFile() {
        val path = _selectedFilePath.value ?: return
        val content = _editorText.value
        if (writeFile(path, content)) {
            _viewedFileContent.value = content
            _isEditingMode.value = false
            // Also notify RAG if needed, but for now we just persist
        }
    }

    fun checkCodeForErrors(isManual: Boolean = true) {
        val path = _selectedFilePath.value ?: return
        val content = _editorText.value
        val model = _currentlyLoadedModel.value
        val extension = path.substringAfterLast(".", "")

        // 1. Local Syntax Check (Fast)
        val checker = com.llmhub.llmhub.utils.LocalSyntaxChecker()
        val localErrors = checker.check(content, extension)
        
        if (isManual) {
            // Manual check always clears and shows progress
            _isCheckingErrors.value = true
            _errorMarkers.value = localErrors 
        } else if (localErrors.isNotEmpty()) {
            // Background check: only update if local errors found
            _errorMarkers.value = localErrors
        }

        if (model == null) {
            if (isManual) {
                _errorMarkers.value = localErrors + ErrorMarker(1, "Please load an AI model to check for deeper errors.")
                _isCheckingErrors.value = false
            }
            return
        }

        viewModelScope.launch {
            if (isManual) _isCheckingErrors.value = true
            _errorMarkers.value = emptyList() // Clear old errors
            val lines = content.lines()
            val chunkedContent = if (lines.size > 200) {
                // Windowing: first 200 lines for now (simplified chunking)
                lines.take(200).joinToString("\n") + "\n... (remaining ${lines.size - 200} lines skipped for speed)"
            } else {
                content
            }

            val prompt = """
                EXPERT CODE ANALYZER (L: $extension)
                Find bugs/syntax errors.
                Respond ONLY JSON [ { "line": INT, "message": "STR" } ].
                No explanation. [] if clean.
                
                CODE:
                $chunkedContent
            """.trimIndent()
            
            val response = try {
                inferenceService.generateResponse(prompt, model)
            } catch (e: Exception) {
                null
            }

            try {
                val jsonArray = Json.parseToJsonElement(response ?: "[]").jsonArray
                val aiMarkers = jsonArray.map { element ->
                    val obj = element.jsonObject
                    ErrorMarker(
                        obj["line"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                        obj["message"]?.jsonPrimitive?.content ?: "Error"
                    )
                }
                // Merge local and AI errors, avoiding duplicates
                val combined = (localErrors + aiMarkers).distinctBy { "${it.lineNumber}:${it.message}" }
                _errorMarkers.value = combined
            } catch (e: Exception) {
                android.util.Log.e("GitHubAgentViewModel", "Failed to parse error markers: $response", e)
                if (isManual) _errorMarkers.value = localErrors
            }
            _isCheckingErrors.value = false
        }
    }

    private fun loadEditorSettings(): com.llmhub.llmhub.utils.EditorSettings {
        val useTab = prefs.getBoolean("editor_use_tab_helper", true)
        val themeName = prefs.getString("editor_theme_name", com.llmhub.llmhub.utils.EditorThemeName.MONOKAI.name) ?: com.llmhub.llmhub.utils.EditorThemeName.MONOKAI.name
        val isAutoAnalysis = prefs.getBoolean("editor_auto_analysis", false)
        return com.llmhub.llmhub.utils.EditorSettings(
            useTabHelper = useTab,
            themeName = com.llmhub.llmhub.utils.EditorThemeName.valueOf(themeName),
            isAutoAnalysisEnabled = isAutoAnalysis
        )
    }

    fun updateEditorSettings(settings: com.llmhub.llmhub.utils.EditorSettings) {
        _editorSettings.value = settings
        prefs.edit()
            .putBoolean("editor_use_tab_helper", settings.useTabHelper)
            .putString("editor_theme_name", settings.themeName.name)
            .putBoolean("editor_auto_analysis", settings.isAutoAnalysisEnabled)
            .apply()
    }

    fun addFileToContext(path: String) {
        val tag = "[FILE:$path]"
        appendToChatInput(tag)
    }

    fun addLineToContext(path: String, lineNumber: Int) {
        val tag = "[LINE:$path:$lineNumber]"
        appendToChatInput(tag)
    }

    private fun appendToChatInput(text: String) {
        val currentText = _currentChatInput.value
        val separator = if (currentText.isEmpty() || currentText.endsWith(" ")) "" else " "
        _currentChatInput.value = currentText + separator + text + " "
    }

    private fun formatFileShorthand(path: String): String {
        val fileName = path.substringAfterLast("/")
        val dotIndex = fileName.lastIndexOf(".")
        if (dotIndex <= 0) return fileName // No extension

        val name = fileName.substring(0, dotIndex)
        val ext = fileName.substring(dotIndex)

        return if (name.length > 4) {
            "${name.take(3)}...${name.last()}$ext"
        } else {
            fileName
        }
    }

    private fun formatLineShorthand(path: String, lineNumber: Int): String {
        return "${formatFileShorthand(path)}:${lineNumber}"
    }

    fun sendMessage(text: String) {
        val userMsg = ChatMessage(role = "user", content = text)
        _messages.value = _messages.value + userMsg

        viewModelScope.launch {
            _isLoading.value = true
            processAgentTurn()
            _isLoading.value = false
        }
    }

    private suspend fun processAgentTurn() {
        val repo = _currentProject.value ?: return
        val rootFiles = listFiles("")?.joinToString(", ") ?: "unknown"
        val systemPrompt = """
            You are a GitHub Agent, an expert software engineer operating on a mobile device.
            You have access to a local clone of a GitHub repository.
            Current project: ${repo.name}
            Project root contents: $rootFiles

            You can use the following tools:
            - SEARCH: Query the RAG index to find relevant code snippets. 
              Format: {"action": "search", "query": "search term"}
            - LIST_FILES: List files in a directory. 
              Format: {"action": "list_files", "path": "relative/path"}
            - READ_FILE: Read the content of a file. 
              Format: {"action": "read_file", "path": "path/to/file"}
            - WRITE_FILE: Overwrite or create a file. 
              Format: {"action": "write_file", "path": "path", "content": "code..."}
            - FINISH: Respond to the user. 
              Format: {"action": "finish", "message": "your response"}

            Rules:
            1. ALWAYS respond with a SINGLE valid JSON object.
            2. MANDATORY: Your FIRST action for any new project MUST be listing the root directory: {"action": "list_files", "path": ""}
            3. Do NOT guess file paths. If you are unsure of a path, use LIST_FILES to explore.
            4. Use SEARCH to find code based on keywords, then use LIST_FILES and READ_FILE to navigate.
            
            NOTE: If the user provides a tag like [FILE:path] or [LINE:path:num], the system will automatically provide the content of that file or line in the subsequent system messages.
        """.trimIndent()

        var loop = true
        var iteration = 0
        
        // Pre-process user message for file/line tags and inject context
        val lastUserMsg = _messages.value.lastOrNull { it.role == "user" }
        if (lastUserMsg != null) {
            val fileRegex = Regex("\\[FILE:(.*?)\\]")
            val lineRegex = Regex("\\[LINE:(.*?):(\\d+)\\]")
            
            fileRegex.findAll(lastUserMsg.content).forEach { match ->
                val path = match.groupValues[1]
                val file = File(context.filesDir, "github_projects/${_currentProject.value?.name}/$path")
                if (file.exists()) {
                    _pendingContextSize.value += file.length().toInt().coerceAtMost(10000)
                }
                
                var content = readFile(path)
                if (content != null) {
                    if (content.length > 10000) {
                        content = content.take(10000) + "\n\n[TRUNCATED: File exceeds 10,000 chars and was partially omitted for stability]"
                    }
                    addSystemContext("Content of referenced file '$path':\n$content")
                    _pendingContextSize.value = (_pendingContextSize.value - 10000).coerceAtLeast(0) // Rough reset, but we'll do a full reset later
                    
                    // Add dependency signatures context
                    try {
                        val repo = _currentProject.value
                        if (repo != null) {
                            val projectRoot = File(context.filesDir, "github_projects/${repo.name}")
                            val analyzer = com.llmhub.llmhub.utils.DependencyAnalyzer(projectRoot)
                            val extension = path.substringAfterLast(".", "")
                            
                            // Estimate dependency context size (usually around 1-5k chars)
                            _pendingContextSize.value += 2000 
                            
                            var depContext = analyzer.getDependencyContext(content, extension)
                            if (depContext.isNotBlank()) {
                                if (depContext.length > 5000) {
                                    depContext = depContext.take(5000) + "\n\n[TRUNCATED: Dependency context too large]"
                                }
                                addSystemContext("Summarized signatures of Level-1 dependencies for '$path':\n$depContext")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("GitHubAgentViewModel", "Failed to analyze dependencies: ${e.message}")
                    } finally {
                        _pendingContextSize.value = 0 // Full reset for safety
                    }
                }
            }
            
            lineRegex.findAll(lastUserMsg.content).forEach { match ->
                val path = match.groupValues[1]
                val lineNum = match.groupValues[2].toIntOrNull() ?: 1
                
                _pendingContextSize.value += 200 // Estimate for a single line
                val fileContent = readFile(path)
                if (fileContent != null) {
                    val lines = fileContent.lines()
                    val lineText = lines.getOrNull(lineNum - 1) ?: "Line not found"
                    addSystemContext("Content of referenced line $lineNum in '$path':\n$lineText")
                }
                _pendingContextSize.value = 0
            }
        }

        while (loop && iteration < 5) {
            iteration++
            val prompt = buildPrompt(systemPrompt)
            val model = inferenceService.getCurrentlyLoadedModel()
            if (model == null) {
                addAgentMessage("Please load an AI model from the settings menu to continue.")
                loop = false
                break
            }
            
            // Context Window Safety Guard
            val estimatedTokens = (prompt.length / 3.0).toInt()
            if (estimatedTokens > (model.contextWindowSize - 100)) {
                addSystemContext("⚠️ CONTEXT OVERFLOW WARNING: The current prompt ($estimatedTokens tokens) approaches or exceeds the model limit (${model.contextWindowSize}). Some history was omitted.")
                // Potentially truncate history further if buildPrompt doesn't handle it
            }

            val response = inferenceService.generateResponse(prompt, model) ?: "{\"action\": \"finish\", \"message\": \"I encountered an error.\"}"
            
            try {
                val cleanResponse = extractFirstJsonObject(response)
                val json = kotlinx.serialization.json.Json.parseToJsonElement(cleanResponse).asJsonObject()
                val action = json["action"]?.asString()
                
                when (action) {
                    "search" -> {
                        val query = json["query"]?.asString() ?: ""
                        _pendingContextSize.value += 5000 // Average search result estimate
                        val results = ragManager.searchGlobalContext(query, maxResults = 5, metadataFilter = "github_project:${repo.name}")
                        val context = results.joinToString("\n\n") { "File: ${it.fileName}\n${it.content}" }
                        _pendingContextSize.value = 0
                        addAgentMessage("Searching for '$query'...\nFound relevant snippets.")
                        addSystemContext("Search results for '$query':\n$context")
                    }
                    "list_files" -> {
                        val path = json["path"]?.asString() ?: ""
                        _pendingContextSize.value += 500
                        val files = listFiles(path)
                        _pendingContextSize.value = 0
                        if (files != null) {
                            addAgentMessage("Listing files in: ${if (path.isEmpty()) "root" else path}")
                            addSystemContext("Files in $path:\n${files.joinToString("\n")}")
                        } else {
                            addSystemContext("Error: Directory $path not found.")
                        }
                    }
                    "read_file" -> {
                        val path = json["path"]?.asString() ?: ""
                        val file = File(context.filesDir, "github_projects/${repo.name}/$path")
                        if (file.exists()) {
                            _pendingContextSize.value += file.length().toInt().coerceAtMost(10000)
                        }
                        val content = readFile(path)
                        _pendingContextSize.value = 0
                        if (content != null) {
                            addAgentMessage("Reading file: $path")
                            addSystemContext("Content of $path:\n$content")
                        } else {
                            addSystemContext("Error: File $path not found. Use LIST_FILES to find the correct path.")
                        }
                    }
                    "write_file" -> {
                        val path = json["path"]?.asString() ?: ""
                        val content = json["content"]?.asString() ?: ""
                        if (writeFile(path, content)) {
                            addAgentMessage("Successfully updated file: $path")
                            addSystemContext("File $path has been updated.")
                        } else {
                            addSystemContext("Error: Could not write to $path.")
                        }
                    }
                    "finish" -> {
                        val message = json["message"]?.asString() ?: "Done."
                        _messages.value = _messages.value + ChatMessage(role = "assistant", content = message)
                        loop = false
                    }
                    else -> loop = false
                }
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(role = "assistant", content = response)
                loop = false
            }
        }
    }

    private fun buildPrompt(system: String): String {
        val model = _currentlyLoadedModel.value ?: _selectedModel.value ?: return system
        val maxTokens = model.contextWindowSize
        val safetyBuffer = 500
        val targetTokens = maxTokens - safetyBuffer
        
        val reversedMsgs = _messages.value.reversed()
        val includedMsgs = mutableListOf<ChatMessage>()
        var currentTokenEst = (system.length / 3.0).toInt()
        
        for (msg in reversedMsgs) {
            val msgTokens = (msg.content.length / 3.0).toInt() + 10 // overhead per msg
            if (currentTokenEst + msgTokens <= targetTokens) {
                includedMsgs.add(0, msg)
                currentTokenEst += msgTokens
            } else {
                break 
            }
        }
        
        val history = includedMsgs.joinToString("\n") { (role, content) -> "$role: $content" }
        return "$system\n\n$history\nassistant:"
    }

    private fun addAgentMessage(text: String) {
        _messages.value = _messages.value + ChatMessage(role = "assistant", content = text)
    }

    private fun addSystemContext(text: String) {
        _messages.value = _messages.value + ChatMessage(role = "system", content = text)
    }


    private fun extractFirstJsonObject(text: String): String {
        var depth = 0
        var start = -1
        for (i in text.indices) {
            if (text[i] == '{') {
                if (depth == 0) start = i
                depth++
            } else if (text[i] == '}') {
                depth--
                if (depth == 0 && start != -1) {
                    return text.substring(start, i + 1)
                }
            }
        }
        return text
    }

    fun logout() {
        authManager.clearToken()
        _authState.value = GitHubAuthState.Unauthenticated
        _userProfile.value = null
        _repositories.value = emptyList()
        _isLoading.value = false
        _cloningStatus.value = null
        _currentProject.value = null
        _messages.value = emptyList()
        _isChatActive.value = false
    }

}

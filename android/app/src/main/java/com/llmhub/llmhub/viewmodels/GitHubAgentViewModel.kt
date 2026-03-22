package com.llmhub.llmhub.viewmodels

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
import kotlinx.coroutines.flow.map

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

    private val _contextUsageFraction = MutableStateFlow(0f)
    val contextUsageFraction: StateFlow<Float> = _contextUsageFraction.asStateFlow()
    private val _contextUsageLabel = MutableStateFlow("0%")
    val contextUsageLabel: StateFlow<String> = _contextUsageLabel.asStateFlow()

    private val prefs = context.getSharedPreferences("github_agent_prefs", Context.MODE_PRIVATE)

    init {
        scanLocalRepos()
        checkAuth()
        restoreLastProject()
        refreshModels()
        syncCurrentlyLoadedModel()
        recalculateContextUsage()
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
                    recalculateContextUsage()
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
            recalculateContextUsage()
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
        val file = java.io.File(context.filesDir, "github_projects/${repo.name}/$path")
        return if (file.exists()) file.readText() else null
    }

    fun writeFile(path: String, content: String): Boolean {
        val repo = _currentProject.value ?: return false
        val file = java.io.File(context.filesDir, "github_projects/${repo.name}/$path")
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

    fun sendMessage(text: String) {
        val userMsg = ChatMessage(role = "user", content = text)
        _messages.value = _messages.value + userMsg

        viewModelScope.launch {
            _isLoading.value = true
            recalculateContextUsage()
            processAgentTurn()
            _isLoading.value = false
            recalculateContextUsage()
        }
    }

    private suspend fun processAgentTurn() {
        val repo = _currentProject.value ?: return
        val systemPrompt = """
            You are a GitHub Agent, an expert software engineer operating on a mobile device.
            You have access to a local clone of a GitHub repository.
            Current project: ${repo.name}

            You can use the following tools to fulfill the user's request:
            - SEARCH: Query the RAG index to find relevant code snippets. 
              Format: {"action": "search", "query": "search term"}
            - READ_FILE: Read the content of a file. 
              Format: {"action": "read_file", "path": "relative/path/to/file"}
            - WRITE_FILE: Overwrite or create a file with new content. 
              Format: {"action": "write_file", "path": "path", "content": "code..."}
            - FINISH: Signal that you have completed the task or need user input. 
              Format: {"action": "finish", "message": "your response to user"}

            Rules:
            1. ALWAYS respond with a SINGLE valid JSON object.
            2. For complex tasks, start by SEARCHing for relevant files.
            3. Use READ_FILE to understand the code before making changes.
            4. Keep responses concise and focused on the task.
        """.trimIndent()

        var loop = true
        var iteration = 0
        while (loop && iteration < 5) {
            iteration++
            val prompt = buildPrompt(systemPrompt)
            val model = inferenceService.getCurrentlyLoadedModel()
            if (model == null) {
                addAgentMessage("Please load an AI model from the settings menu to continue.")
                loop = false
                break
            }
            val response = inferenceService.generateResponse(prompt, model) ?: "{\"action\": \"finish\", \"message\": \"I encountered an error.\"}"
            
            try {
                val json = kotlinx.serialization.json.Json.parseToJsonElement(response).asJsonObject()
                val action = json["action"]?.asString()
                
                when (action) {
                    "search" -> {
                        val query = json["query"]?.asString() ?: ""
                        val results = ragManager.searchGlobalContext(query, maxResults = 5, metadataFilter = "github_project:${repo.name}")
                        val context = results.joinToString("\n\n") { "File: ${it.fileName}\n${it.content}" }
                        addAgentMessage("Searching for '$query'...\nFound relevant snippets.")
                        addSystemContext("Search results for '$query':\n$context")
                    }
                    "read_file" -> {
                        val path = json["path"]?.asString() ?: ""
                        val content = readFile(path)
                        if (content != null) {
                            addAgentMessage("Reading file: $path")
                            addSystemContext("Content of $path:\n$content")
                        } else {
                            addSystemContext("Error: File $path not found.")
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
        val lastMsgs = _messages.value.takeLast(10)
        val history = lastMsgs.joinToString("\n") { (role, content) -> "$role: $content" }
        return "$system\n\n$history\nassistant:"
    }

    private fun addAgentMessage(text: String) {
        _messages.value = _messages.value + ChatMessage(role = "assistant", content = text)
    }

    private fun addSystemContext(text: String) {
        _messages.value = _messages.value + ChatMessage(role = "system", content = text)
    }

    private fun kotlinx.serialization.json.JsonElement.asJsonObject() = this as? kotlinx.serialization.json.JsonObject ?: throw Exception("Not an object")
    private fun kotlinx.serialization.json.JsonElement.asString() = (this as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
    private fun kotlinx.serialization.json.JsonObject.get(key: String) = this[key]

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
        recalculateContextUsage()
    }

    private fun recalculateContextUsage() {
        val model = _currentlyLoadedModel.value ?: _selectedModel.value
        val maxTokens = model?.contextWindowSize?.coerceAtLeast(1) ?: 4096
        
        val usedChars = _messages.value.sumOf { it.content.length }
        val usedTokens = (usedChars / 4).coerceAtLeast(0)
        
        val fraction = (usedTokens.toFloat() / maxTokens.toFloat()).coerceIn(0f, 1f)
        _contextUsageFraction.value = fraction
        _contextUsageLabel.value = "${(fraction * 100).toInt()}%"
    }
}

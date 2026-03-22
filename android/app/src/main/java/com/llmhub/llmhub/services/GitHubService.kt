package com.llmhub.llmhub.services

import android.content.Context
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class GitHubRepository(
    val id: Long,
    val name: String,
    val full_name: String,
    val description: String? = null,
    val html_url: String,
    val clone_url: String,
    val language: String? = null,
    val private: Boolean
)

@Serializable
data class GitHubUser(
    val login: String,
    val avatar_url: String,
    val name: String? = null
)

interface GitHubService {
    suspend fun getUserProfile(token: String): GitHubUser?
    suspend fun getRepositories(token: String): List<GitHubRepository>
    suspend fun cloneRepository(
        token: String,
        repo: GitHubRepository,
        targetDir: java.io.File,
        onProgress: (String) -> Unit
    ): Boolean
    suspend fun pullRepository(
        token: String,
        repo: GitHubRepository,
        targetDir: java.io.File,
        onProgress: (String) -> Unit
    ): Boolean
    suspend fun pushChanges(
        token: String,
        repo: GitHubRepository,
        targetDir: java.io.File,
        commitMessage: String,
        onProgress: (String) -> Unit
    ): Boolean
}

class GitHubServiceImpl(
    private val context: Context,
    private val httpClient: HttpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { 
                ignoreUnknownKeys = true 
                coerceInputValues = true
            })
        }
    }
) : GitHubService {
    private val json = Json { 
        ignoreUnknownKeys = true 
        coerceInputValues = true
    }

    override suspend fun getUserProfile(token: String): GitHubUser? {
        android.util.Log.e("GitHubService", "getUserProfile called, token length: ${token.length}")
        return try {
            val response = httpClient.get("https://api.github.com/user") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github.v3+json")
                header("User-Agent", "LLM-Hub-Android-App")
            }
            if (response.status.value in 200..299) {
                val responseText = response.bodyAsText()
                val user = json.decodeFromString(GitHubUser.serializer(), responseText)
                android.util.Log.e("GitHubService", "Successfully fetched user: ${user.login}")
                user
            } else {
                android.util.Log.e("GitHubService", "Failed to fetch user: ${response.status} ${response.bodyAsText()}")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("GitHubService", "Error fetching user profile", e)
            null
        }
    }

    override suspend fun getRepositories(token: String): List<GitHubRepository> {
        android.util.Log.e("GitHubService", "getRepositories called, token length: ${token.length}")
        return try {
            val response = httpClient.get("https://api.github.com/user/repos") {
                header("Authorization", "Bearer $token")
                header("Accept", "application/vnd.github.v3+json")
                header("User-Agent", "LLM-Hub-Android-App")
                parameter("sort", "updated")
                parameter("per_page", 100)
            }
            if (response.status.value in 200..299) {
                val responseText = response.bodyAsText()
                val repos = json.decodeFromString(ListSerializer(GitHubRepository.serializer()), responseText)
                android.util.Log.e("GitHubService", "Successfully fetched ${repos.size} repositories")
                repos
            }
 else {
                android.util.Log.e("GitHubService", "Failed to fetch repos: ${response.status} ${response.bodyAsText()}")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("GitHubService", "Error fetching repositories", e)
            emptyList()
        }
    }

    override suspend fun cloneRepository(
        token: String,
        repo: GitHubRepository,
        targetDir: java.io.File,
        onProgress: (String) -> Unit
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            onProgress("Cloning ${repo.name}...")
            
            val cloneCommand = org.eclipse.jgit.api.Git.cloneRepository()
                .setURI(repo.clone_url)
                .setDirectory(targetDir)
                .setCredentialsProvider(
                    org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider("token", token)
                )
                .setTimeout(300)
                .setProgressMonitor(object : org.eclipse.jgit.lib.ProgressMonitor {
                    private var currentTask: String = ""
                    private var totalWork: Int = 0
                    private var completedWork: Int = 0

                    override fun start(totalTasks: Int) {
                        android.util.Log.e("GitHubService", "Clone starting: $totalTasks tasks")
                    }
                    override fun beginTask(title: String, totalWork: Int) {
                        this.currentTask = title
                        this.totalWork = if (totalWork <= 0) 0 else totalWork
                        this.completedWork = 0
                        android.util.Log.e("GitHubService", "JGit Task: $title, Work: $totalWork")
                        onProgress("$title...")
                    }
                    override fun update(completed: Int) {
                        completedWork += completed
                        if (totalWork > 0) {
                            val percent = (completedWork * 100) / totalWork
                            onProgress("$currentTask: $percent%")
                        } else {
                            onProgress("$currentTask: $completedWork")
                        }
                    }
                    override fun endTask() {}
                    override fun isCancelled(): Boolean = false
                    override fun showDuration(enabled: Boolean) {}
                })
                .setCloneAllBranches(false)
                .setNoCheckout(false)
            
            android.util.Log.e("GitHubService", "Calling JGit clone for ${repo.clone_url}")
            cloneCommand.call().use { git ->
                android.util.Log.e("GitHubService", "Clone successful for ${repo.name}")
                onProgress("Successfully cloned ${repo.name}")
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("GitHubService", "Error cloning repository", e)
            onProgress("Error: ${e.message}")
            false
        }
    }

    override suspend fun pullRepository(
        token: String,
        repo: GitHubRepository,
        targetDir: java.io.File,
        onProgress: (String) -> Unit
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            onProgress("Pulling latest changes for ${repo.name}...")
            org.eclipse.jgit.api.Git.open(targetDir).use { git ->
                git.pull()
                    .setCredentialsProvider(
                        org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider("token", token)
                    )
                    .call()
                onProgress("Successfully pulled ${repo.name}")
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("GitHubService", "Error pulling repository", e)
            onProgress("Error: ${e.message}")
            false
        }
    }

    override suspend fun pushChanges(
        token: String,
        repo: GitHubRepository,
        targetDir: java.io.File,
        commitMessage: String,
        onProgress: (String) -> Unit
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            onProgress("Committing and pushing changes...")
            org.eclipse.jgit.api.Git.open(targetDir).use { git ->
                git.add().addFilepattern(".").call()
                git.commit().setMessage(commitMessage).call()
                git.push()
                    .setCredentialsProvider(
                        org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider("token", token)
                    )
                    .call()
                onProgress("Successfully pushed changes to ${repo.name}")
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("GitHubService", "Error pushing changes", e)
            onProgress("Error: ${e.message}")
            false
        }
    }
}

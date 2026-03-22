package com.llmhub.llmhub.services

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Log
import com.llmhub.llmhub.BuildConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Serializable
data class GitHubTokenResponse(
    val access_token: String,
    val token_type: String,
    val scope: String
)

class GitHubAuthManager(private val context: Context) {
    private val TAG = "GitHubAuthManager"
    
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "github_auth_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { 
                ignoreUnknownKeys = true 
            })
        }
    }

    fun saveAccessToken(token: String) {
        sharedPreferences.edit().putString("access_token", token).apply()
    }

    fun getAccessToken(): String? {
        return sharedPreferences.getString("access_token", null)
    }

    fun clearToken() {
        sharedPreferences.edit().remove("access_token").apply()
    }

    suspend fun exchangeCodeForToken(code: String): String? = withContext(Dispatchers.IO) {
        try {
            val response: HttpResponse = httpClient.post("https://github.com/login/oauth/access_token") {
                header("Accept", "application/json")
                header("User-Agent", "LlmHub-Android")
                parameter("client_id", BuildConfig.GITHUB_CLIENT_ID)
                parameter("client_secret", BuildConfig.GITHUB_CLIENT_SECRET)
                parameter("code", code)
            }
            
            if (response.status.value in 200..299) {
                val tokenResponse: GitHubTokenResponse = response.body()
                saveAccessToken(tokenResponse.access_token)
                Log.d(TAG, "Successfully exchanged code for token")
                tokenResponse.access_token
            } else {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "Failed to exchange code: ${response.status}")
                Log.e(TAG, "Error body: $errorBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exchanging code for token", e)
            null
        }
    }

    companion object {
        const val AUTH_URL = "https://github.com/login/oauth/authorize"
        const val REDIRECT_URI = "https://llmhub.com/github-auth"
        const val SCOPE = "repo,user"

        fun getFullAuthUrl(): String {
            val encodedClientId = java.net.URLEncoder.encode(BuildConfig.GITHUB_CLIENT_ID, "UTF-8")
            val encodedRedirectUri = java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8")
            val encodedScope = java.net.URLEncoder.encode(SCOPE, "UTF-8")
            return "$AUTH_URL?client_id=$encodedClientId&redirect_uri=$encodedRedirectUri&scope=$encodedScope"
        }
    }
}

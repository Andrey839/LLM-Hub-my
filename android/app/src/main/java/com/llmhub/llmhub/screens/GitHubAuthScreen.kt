package com.llmhub.llmhub.screens

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import com.llmhub.llmhub.services.GitHubAuthManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubAuthScreen(
    onNavigateBack: () -> Unit,
    onAuthComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authManager = remember { GitHubAuthManager(context) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(0) } // 0: OAuth, 1: Token
    var tokenInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub Login", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("OAuth (Recommended)") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Token (PAT)") }
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (error != null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Error: $error", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { error = null }) {
                            Text("Retry")
                        }
                    }
                } else if (selectedTab == 0) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        android.util.Log.d("GitHubAuth", "Page started: $url")
                                        super.onPageStarted(view, url, favicon)
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val url = request?.url?.toString() ?: return false
                                        android.util.Log.d("GitHubAuth", "Should override: $url")
                                        if (url.startsWith(GitHubAuthManager.REDIRECT_URI)) {
                                            val code = request.url.getQueryParameter("code")
                                            if (code != null) {
                                                isLoading = true
                                                scope.launch {
                                                    val token = authManager.exchangeCodeForToken(code)
                                                    isLoading = false
                                                    if (token != null) {
                                                        onAuthComplete()
                                                    } else {
                                                        error = "Failed to exchange code for token. Check if GITHUB_CLIENT_SECRET is correctly set in local.properties."
                                                    }
                                                }
                                            } else {
                                                val errorParam = request.url.getQueryParameter("error")
                                                error = errorParam ?: "Authorization denied or failed"
                                            }
                                            return true
                                        }
                                        return false
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: android.webkit.WebResourceError?
                                    ) {
                                        android.util.Log.e("GitHubAuth", "Error: ${error?.description} at ${request?.url}")
                                        super.onReceivedError(view, request, error)
                                    }
                                }
                                loadUrl(GitHubAuthManager.getFullAuthUrl())
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Login with Personal Access Token",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "You can generate a PAT in GitHub Settings > Developer settings > Personal access tokens.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            label = { Text("Paste PAT here") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                if (tokenInput.isNotBlank()) {
                                    isLoading = true
                                    authManager.saveAccessToken(tokenInput.trim())
                                    onAuthComplete()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = tokenInput.isNotBlank() && !isLoading
                        ) {
                            Text("Connect")
                        }
                    }
                }
                
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

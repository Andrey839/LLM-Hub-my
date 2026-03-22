package com.llmhub.llmhub.browser

import android.webkit.WebView
import android.webkit.ValueCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject

class BrowserAgentService {
    private var webView: WebView? = null

    fun attachWebView(webView: WebView) {
        this.webView = webView
    }

    fun detachWebView() {
        this.webView = null
    }

    suspend fun navigate(url: String) = withContext(Dispatchers.Main) {
        val finalUrl = if (url.startsWith("http")) url else "https://$url"
        webView?.loadUrl(finalUrl)
    }

    suspend fun getPageState(): String = withContext(Dispatchers.Main) {
        val currentWebView = webView ?: return@withContext "TITLE: No WebView attached"
        val js = """
            (function() {
                const elements = [];
                const interactive = document.querySelectorAll('button, a, input, [onclick], [role="button"], [role="link"], select, textarea');
                
                let count = 0;
                let md = [];
                md.push("URL: " + window.location.href.substring(0, 100));
                md.push("TITLE: " + document.title.substring(0, 50));
                md.push("---");
                
                for (let i = 0; i < interactive.length && count < 50; i++) {
                    const el = interactive[i];
                    const rect = el.getBoundingClientRect();
                    const style = window.getComputedStyle(el);
                    
                    if (rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden') {
                        const agentId = "el_" + i;
                        el.setAttribute('data-agent-id', agentId);
                        
                        const text = (el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || "").trim().substring(0, 40).replace(/\n/g, " ");
                        if (!text && el.tagName !== 'INPUT' && el.tagName !== 'SELECT') continue;

                        const tag = el.tagName.toLowerCase();
                        md.push("[" + agentId + "] <" + tag + "> " + (text || "(empty)"));
                        count++;
                    }
                }
                
                // Add a simple hash of IDs to detect changes later
                const stateHash = md.join('|').split('').reduce((a, b) => { a = ((a << 5) - a) + b.charCodeAt(0); return a & a; }, 0);
                md.push("---");
                md.push("HASH: " + stateHash);
                
                return md.join('\n');
            })()
        """.trimIndent()

        suspendCancellableCoroutine { continuation ->
            currentWebView.evaluateJavascript(js) { result ->
                // Remove surrounding quotes from JS string result
                val cleaned = if (result != null && result.startsWith("\"") && result.endsWith("\"")) {
                    result.substring(1, result.length - 1)
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                } else result ?: ""
                continuation.resume(cleaned)
            }
        }
    }

    suspend fun executeAction(action: String, agentId: String, text: String? = null): String = withContext(Dispatchers.Main) {
        val currentWebView = webView ?: return@withContext "No WebView attached"
        val js = when (action.uppercase()) {
            "CLICK" -> "const el = document.querySelector('[data-agent-id=\"$agentId\"]'); if(el) { el.click(); 'Clicked $agentId'; } else { 'Element $agentId not found'; }"
            "TYPE" -> "const el = document.querySelector('[data-agent-id=\"$agentId\"]'); if(el) { el.value = '${text ?: ""}'; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); 'Typed in $agentId'; } else { 'Element $agentId not found'; }"
            "SCROLL_DOWN" -> "window.scrollBy({top: 500, behavior: 'smooth'}); 'Scrolled down';"
            "SCROLL_UP" -> "window.scrollBy({top: -500, behavior: 'smooth'}); 'Scrolled up';"
            "NAVIGATE" -> {
                val url = if (text?.startsWith("http") == true) text else "https://${text ?: ""}"
                currentWebView.loadUrl(url)
                "'Navigated to $url'"
            }
            else -> "'Unknown action: $action'"
        }

        suspendCancellableCoroutine { continuation ->
            currentWebView.evaluateJavascript(js) { result ->
                continuation.resume(result ?: "Success")
            }
        }
    }

    suspend fun captureScreenshot(): android.graphics.Bitmap? = withContext(Dispatchers.Main) {
        val currentWebView = webView ?: return@withContext null
        if (currentWebView.width <= 0 || currentWebView.height <= 0) return@withContext null
        
        try {
            val width = currentWebView.width
            val height = currentWebView.height
            // Standardize on 448 for MediaPipe Vision models to avoid resizing overhead on model side
            val targetDim = 448
            val scale = targetDim.toFloat() / maxOf(width, height)
            
            val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
            val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
            
            val bitmap = android.graphics.Bitmap.createBitmap(scaledWidth, scaledHeight, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.scale(scale, scale)
            currentWebView.draw(canvas)
            bitmap
        } catch (e: Exception) {
            android.util.Log.e("BrowserAgent", "Screenshot failed", e)
            null
        }
    }
}

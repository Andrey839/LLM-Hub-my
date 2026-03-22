package com.llmhub.llmhub.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.llmhub.browser.BrowserAgentService
import com.llmhub.llmhub.inference.InferenceService
import com.llmhub.llmhub.data.LLMModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.util.Log

class BrowserAgentViewModel(
    private val inferenceService: InferenceService,
    private val browserService: BrowserAgentService
) : ViewModel() {

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentStatus = MutableStateFlow("Ready")
    val currentStatus: StateFlow<String> = _currentStatus.asStateFlow()

    private var selectedModel: LLMModel? = null

    fun setModel(model: LLMModel) {
        selectedModel = model
    }

    fun attachWebView(webView: android.webkit.WebView) {
        browserService.attachWebView(webView)
    }

    fun startGoal(goal: String) {
        val model = selectedModel ?: return
        if (_isRunning.value) return

        viewModelScope.launch {
            _isRunning.value = true
            _logs.value = listOf("Goal: $goal")
            val chatId = "browser_${java.util.UUID.randomUUID().toString().take(8)}"
            var step = 0
            val maxSteps = 10
            var lastPageHash = ""
            var lastAction = ""
            
            while (step < maxSteps && _isRunning.value) {
                _currentStatus.value = "Analyzing page (Step ${step + 1})..."
                val pageState = browserService.getPageState()
                val hasVision = model.supportsVision
                
                // Extract current hash from pageState (format is "... HASH: 12345")
                val currentHash = pageState.substringAfterLast("HASH: ", "")
                val url = pageState.substringAfter("URL: ", "").substringBefore("\n")
                
                // VISION ON DEMAND: Only capture screenshot if:
                // 1. It's the first step
                // 2. The page content (elements/text) changed (different hash)
                // 3. The last action was NAVIGATE
                val shouldCaptureVision = hasVision && (step == 0 || currentHash != lastPageHash || lastAction == "NAVIGATE")
                
                val images = if (shouldCaptureVision) {
                    _currentStatus.value = "Capturing page screenshot..."
                    listOfNotNull(browserService.captureScreenshot())
                } else emptyList()

                val jsonFormatInstruction = """
                    Reply ONLY with a JSON object.
                    CRITICAL: Do not use your internal search tools (no 🔍). Do not use conversational filler.
                    
                    EXAMPLE JSON RESPONSE:
                    {
                      "thought": "I need to search for the weather. I will type it into the search box.",
                      "actions": [
                        {"action": "TYPE", "agentId": "el_1", "text": "weather in London"},
                        {"action": "CLICK", "agentId": "el_2"}
                      ]
                    }
                """.trimIndent()

                val prompt = if (step == 0) {
                    """
                        system: You are a specialized Browser Agent. You MUST NOT use your default assistant tools, search engines, or emojis. 
                        Interact ONLY by providing JSON actions for the provided page state.
                        
                        Goal: $goal
                        
                        Available Actions:
                        - NAVIGATE: url
                        - CLICK: agentId
                        - TYPE: agentId, text
                        - SCROLL_DOWN
                        - SCROLL_UP
                        - ANSWER: text (If goal is met)
                        
                        $jsonFormatInstruction
                        
                        Current Page State:
                        $pageState
                        ${if (shouldCaptureVision && images.isNotEmpty()) "IMAGE: [Attached screenshot]" else ""}
                    """.trimIndent()
                } else {
                    """
                        Last Action Result: $lastAction was executed.
                        $jsonFormatInstruction
                        
                        Current Page State:
                        $pageState
                        ${if (shouldCaptureVision && images.isNotEmpty()) "IMAGE: [Attached screenshot]" else "IMAGE: [Not provided - use previous context]"}
                        Next action(s) for goal "$goal":
                    """.trimIndent()
                }

                addLog("Step ${step + 1}: Vision=${if (shouldCaptureVision) "ON" else "OFF"}. Thinking...")
                _currentStatus.value = "Model is thinking..."
                val response = try {
                    // Use generateResponseWithSession to leverage KV cache
                    val res = inferenceService.generateResponseWithSession(prompt, model, chatId, images)
                    if (res.isBlank()) {
                        addLog("Error: Model returned empty response")
                        break
                    }
                    res
                } catch (e: Exception) {
                    addLog("Inference Error: ${e.message}")
                    break
                }

                Log.d("BrowserAgent", "Step $step Response: $response")
                
                try {
                    // Extract JSON if it's wrapped in markdown
                    val jsonStr = if (response.contains("{")) {
                        response.substring(response.indexOf("{"), response.lastIndexOf("}") + 1)
                    } else {
                        addLog("Error: No JSON found. Raw: ${response.take(100)}")
                        throw Exception("No JSON found")
                    }

                    val json = JSONObject(jsonStr)
                    val thought = json.optString("thought")
                    addLog("Thought: $thought")

                    // Handle Actions Array (Chaining) or Single Action
                    val actionsArray = json.optJSONArray("actions")
                    val actionsToExecute = mutableListOf<JSONObject>()
                    
                    if (actionsArray != null) {
                        for (i in 0 until actionsArray.length()) {
                            actionsToExecute.add(actionsArray.getJSONObject(i))
                        }
                    } else if (json.has("action")) {
                        actionsToExecute.add(json)
                    }

                    if (actionsToExecute.isEmpty()) {
                        addLog("No actions provided by agent")
                    }

                    for (actionObj in actionsToExecute) {
                        val action = actionObj.getString("action")
                        val agentId = actionObj.optString("agentId")
                        val text = actionObj.optString("text")
                        
                        if (action == "ANSWER") {
                            addLog("Goal achieved: $text")
                            _currentStatus.value = "Goal Accomplished"
                            step = maxSteps // End loop
                            break 
                        }

                        _currentStatus.value = "Executing $action $agentId..."
                        val result = browserService.executeAction(action, agentId, text)
                        addLog("Action: $action -> $result")
                        
                        lastAction = action
                        lastPageHash = currentHash
                        
                        // If action was NAVIGATE, stop chaining as the DOM has changed
                        if (action == "NAVIGATE") {
                            addLog("Navigation detected, stopping remaining actions in chain")
                            break
                        }
                        
                        // Small settle delay between chained actions
                        kotlinx.coroutines.delay(100)
                    }
                    
                } catch (e: Exception) {
                    addLog("Action Error: ${e.message}")
                    Log.e("BrowserAgent", "Error processing response", e)
                }

                step++
                kotlinx.coroutines.delay(500) // Reduced from 2000ms for faster execution
            }
            
            _isRunning.value = false
            _currentStatus.value = "Finished"
        }
    }

    private fun addLog(message: String) {
        _logs.value = _logs.value + message
    }

    fun stop() {
        _isRunning.value = false
        addLog("Agent stopped by user.")
    }
}

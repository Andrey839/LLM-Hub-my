package com.llmhub.llmhub.viewmodels

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.data.ModelData
import com.llmhub.llmhub.inference.InferenceService
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

data class BenchmarkResult(
    val modelName: String,
    val backend: String,
    val loadTimeMs: Long,
    val ttftMs: Long,
    val tokensPerSecond: Float,
    val totalTokens: Int,
    val status: String = "Success"
)

class BenchmarkViewModel(
    private val inferenceService: InferenceService,
    private val context: Context
) : ViewModel() {

    private val TAG = "BenchmarkViewModel"
    private val BENCHMARK_PROMPT = "Explain the importance of NPU in modern mobile devices in 100 words."

    private val _availableModels = MutableStateFlow<List<LLMModel>>(emptyList())
    val availableModels: StateFlow<List<LLMModel>> = _availableModels.asStateFlow()

    private val _results = MutableStateFlow<List<BenchmarkResult>>(emptyList())
    val results: StateFlow<List<BenchmarkResult>> = _results.asStateFlow()

    private val _isBenchmarking = MutableStateFlow(false)
    val isBenchmarking: StateFlow<Boolean> = _isBenchmarking.asStateFlow()

    private val _currentProgress = MutableStateFlow("")
    val currentProgress: StateFlow<String> = _currentProgress.asStateFlow()

    init {
        refreshModels()
    }

    fun refreshModels() {
        viewModelScope.launch {
            val downloaded = com.llmhub.llmhub.data.ModelAvailabilityProvider.loadAvailableModels(context)
            _availableModels.value = downloaded
        }
    }

    fun runFullBenchmark(selectedModels: List<LLMModel>) {
        viewModelScope.launch {
            _isBenchmarking.value = true
            _results.value = emptyList()
            
            for (model in selectedModels) {
                // Determine possible backends
                val backends = mutableListOf(LlmInference.Backend.CPU)
                if (model.supportsGpu) {
                    backends.add(LlmInference.Backend.GPU)
                }
                
                // If Qualcomm NPU is supported, ORT or MediaPipe will use it 
                // We've widened support to 8 Gen 2/3.
                // For benchmarking purposes, "GPU" backend in our code now 
                // maps to NPU on Snapdragon 8 Gen 2+ via QNN/HTP tweaks we made.
                
                for (backend in backends) {
                    val backendName = if (backend == LlmInference.Backend.GPU) "NPU/GPU" else "CPU"
                    _currentProgress.value = context.getString(com.llmhub.llmhub.R.string.testing_model_on_backend, model.name, backendName)
                    
                    try {
                        val result = performBenchmark(model, backend)
                        _results.value = _results.value + result
                    } catch (e: Exception) {
                        Log.e(TAG, "Benchmark failed for ${model.name} on $backendName", e)
                        _results.value = _results.value + BenchmarkResult(
                            model.name, backendName, 0, 0, 0f, 0, "Error: ${e.message}"
                        )
                    }
                }
            }
            
            _currentProgress.value = context.getString(com.llmhub.llmhub.R.string.benchmark_complete)
            _isBenchmarking.value = false
        }
    }

    private suspend fun performBenchmark(model: LLMModel, backend: LlmInference.Backend): BenchmarkResult {
        // 1. Measure Load Time
        val loadStart = System.currentTimeMillis()
        val loaded = inferenceService.loadModel(model, backend, null)
        if (!loaded) throw Exception("Failed to load model")
        val loadTimeMs = System.currentTimeMillis() - loadStart
        
        // 2. Run Inference & Measure TPS/TTFT
        var firstTokenTime = 0L
        var tokenCount = 0
        val startTime = System.currentTimeMillis()
        
        try {
            inferenceService.generateResponseStream(BENCHMARK_PROMPT, model).collect { chunk ->
                if (tokenCount == 0 && chunk.isNotEmpty()) {
                    firstTokenTime = System.currentTimeMillis()
                }
                // Crude token estimation: spaces + some overhead
                tokenCount += chunk.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            }
        } finally {
            // Unload to free memory for next test
            inferenceService.unloadModel()
        }
        
        val totalTimeMs = System.currentTimeMillis() - startTime
        val ttftMs = if (firstTokenTime > 0) firstTokenTime - startTime else totalTimeMs
        
        // Decoding time excludes TTFT for "tokens per second"
        val decodingTimeMs = (totalTimeMs - ttftMs).coerceAtLeast(1)
        val tps = (tokenCount.toFloat() / (decodingTimeMs.toFloat() / 1000f))
        
        return BenchmarkResult(
            modelName = model.name,
            backend = if (backend == LlmInference.Backend.GPU) "NPU/GPU" else "CPU",
            loadTimeMs = loadTimeMs,
            ttftMs = ttftMs,
            tokensPerSecond = tps,
            totalTokens = tokenCount
        )
    }

    private fun LLMModel.localFileName(): String {
        return url.substringAfterLast("/")
    }
}

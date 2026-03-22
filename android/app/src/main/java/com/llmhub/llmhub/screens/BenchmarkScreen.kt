package com.llmhub.llmhub.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.llmhub.llmhub.R
import com.llmhub.llmhub.data.LLMModel
import com.llmhub.llmhub.viewmodels.BenchmarkResult
import com.llmhub.llmhub.viewmodels.BenchmarkViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    viewModel: BenchmarkViewModel,
    onNavigateBack: () -> Unit
) {
    val availableModels by viewModel.availableModels.collectAsState()
    val results by viewModel.results.collectAsState()
    val isBenchmarking by viewModel.isBenchmarking.collectAsState()
    val progress by viewModel.currentProgress.collectAsState()
    
    val selectedModels = remember { mutableStateListOf<LLMModel>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.auto_benchmark)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !isBenchmarking) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (availableModels.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_models_found_benchmark), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                Text(
                    stringResource(R.string.select_models_to_test),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    items(availableModels) { model ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = selectedModels.contains(model),
                                onCheckedChange = { checked ->
                                    if (checked) selectedModels.add(model) else selectedModels.remove(model)
                                },
                                enabled = !isBenchmarking
                            )
                            Text(model.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { viewModel.runFullBenchmark(selectedModels.toList()) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBenchmarking && selectedModels.isNotEmpty()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.start_benchmark))
                }

                if (isBenchmarking) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = progress,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    stringResource(R.string.results_label),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                ResultsTable(results)
            }
        }
    }
}

@Composable
fun ResultsTable(results: List<BenchmarkResult>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(8.dp)
            ) {
                Text("Model", Modifier.weight(1.5f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Backend", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("TPS", Modifier.weight(0.7f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("TTFT", Modifier.weight(0.7f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            HorizontalDivider()
        }
        
        items(results) { result ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Text(result.modelName, Modifier.weight(1.5f), fontSize = 11.sp)
                Text(result.backend, Modifier.weight(1f), fontSize = 11.sp)
                Text(
                    text = String.format("%.1f", result.tokensPerSecond),
                    modifier = Modifier.weight(0.7f),
                    color = if (result.tokensPerSecond > 10) Color(0xFF00C853) else MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp
                )
                Text("${result.ttftMs}ms", Modifier.weight(0.7f), fontSize = 11.sp)
            }
            HorizontalDivider()
        }
    }
}

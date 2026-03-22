package com.llmhub.llmhub.embedding

import android.content.Context
import android.util.Log
import com.llmhub.llmhub.data.LlmHubDatabase
import com.llmhub.llmhub.data.MemoryDocument
import com.llmhub.llmhub.data.MemoryChunkEmbedding
import com.llmhub.llmhub.data.floatArrayToByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CodeProcessor(private val context: Context) {
    private val TAG = "CodeProcessor"
    private val ragManager = RagServiceManager.getInstance(context)
    private val db = LlmHubDatabase.getDatabase(context)

    suspend fun indexProject(
        projectName: String,
        projectDir: File,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val codeFiles = projectDir.walkTopDown()
                .filter { it.isFile && isCodeFile(it) && !it.absolutePath.contains(".git") }
                .toList()

            onProgress("Found ${codeFiles.size} code files. Starting indexing...")

            for ((fileIndex, file) in codeFiles.withIndex()) {
                val relativePath = file.absolutePath.removePrefix(projectDir.parentFile?.absolutePath ?: "")
                onProgress("Indexing [$fileIndex/${codeFiles.size}]: $relativePath")
                
                val content = try { file.readText() } catch (e: Exception) { "" }
                if (content.isBlank()) continue

                val chunks = createCodeChunks(content, relativePath)
                
                // For each file, we create a virtual "MemoryDocument" so it shows up in RAG
                val docId = "github_${projectName}_${relativePath.hashCode()}"
                val doc = MemoryDocument(
                    id = docId,
                    fileName = relativePath,
                    content = content,
                    metadata = "github_project:$projectName;path:$relativePath",
                    createdAt = System.currentTimeMillis(),
                    status = "EMBEDDING_IN_PROGRESS"
                )
                db.memoryDao().insert(doc)

                for ((chunkIndex, chunkText) in chunks.withIndex()) {
                    val emb = ragManager.generateEmbedding(chunkText)
                    if (emb != null) {
                        val chunkId = "${docId}_$chunkIndex"
                        val modelName = ragManager.getCurrentEmbeddingModelName()
                        val chunkEntity = MemoryChunkEmbedding(
                            id = chunkId,
                            docId = docId,
                            fileName = relativePath,
                            chunkIndex = chunkIndex,
                            content = chunkText,
                            embedding = floatArrayToByteArray(emb),
                            embeddingModel = modelName,
                            createdAt = System.currentTimeMillis()
                        )
                        db.memoryDao().insertChunk(chunkEntity)
                        ragManager.addGlobalDocumentChunk(docId, chunkText, relativePath, chunkIndex, emb, modelName, doc.metadata)
                    }
                }
                db.memoryDao().update(doc.copy(status = "EMBEDDED", chunkCount = chunks.size))
            }
            onProgress("Project $projectName indexed successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error indexing project", e)
            onProgress("Error during indexing: ${e.message}")
            false
        }
    }

    suspend fun removeProject(projectName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Find all documents with this project name in metadata
            val docIds = db.memoryDao().getAll().filter { 
                it.metadata.contains("github_project:$projectName") 
            }.map { it.id }
            
            for (id in docIds) {
                db.memoryDao().deleteById(id)
                db.memoryDao().deleteChunksByDocId(id)
                ragManager.removeDocumentFromGlobalContext(id)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error removing project $projectName", e)
            false
        }
    }

    private fun isCodeFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return setOf("kt", "java", "py", "js", "ts", "cpp", "h", "swift", "html", "css", "gradle", "xml", "md").contains(ext)
    }

    private fun createCodeChunks(content: String, filePath: String): List<String> {
        val maxChunkSize = 1000
        val overlap = 200
        
        // Prefix each chunk with file path to maintain context
        val prefix = "File: $filePath\n\n"
        val effectiveMax = maxChunkSize - prefix.length

        if (content.length <= effectiveMax) {
            return listOf(prefix + content)
        }

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < content.length) {
            var end = (start + effectiveMax).coerceAtMost(content.length)
            
            // Try to find a better break point (newline)
            if (end < content.length) {
                val lastNewline = content.substring(start, end).lastIndexOf('\n')
                if (lastNewline > effectiveMax / 2) {
                    end = start + lastNewline
                }
            }
            
            chunks.add(prefix + content.substring(start, end).trim())
            start = end - overlap
            if (start < 0) start = 0
            if (end == content.length) break
        }
        return chunks
    }
}

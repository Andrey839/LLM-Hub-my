package com.llmhub.llmhub.utils

import java.io.File
import java.util.regex.Pattern

/**
 * Utility to analyze Level-1 dependencies (direct imports) of a file
 * and extract their signatures to save memory and context tokens.
 */
class DependencyAnalyzer(private val projectRoot: File) {

    /**
     * Finds local project dependencies for the given file content and extension,
     * returns a string containing the signatures of the imported classes.
     */
    fun getDependencyContext(content: String, extension: String): String {
        val localImports = findLocalImports(content, extension)
        val sb = StringBuilder()
        
        localImports.take(10).forEach { importPath -> // Limit to 10 dependencies for RAM/CPU safety
            val file = findFileForImport(importPath, extension)
            if (file != null && file.exists()) {
                val signatures = extractSignatures(file, extension)
                if (signatures.isNotBlank()) {
                    sb.append("--- Dependency: ${file.name} ---\n")
                    sb.append(signatures)
                    sb.append("\n\n")
                }
            }
        }
        
        return sb.toString()
    }

    private fun findLocalImports(content: String, extension: String): List<String> {
        val imports = mutableListOf<String>()
        val lines = content.lines()
        
        when (extension.lowercase()) {
            "kt", "java" -> {
                val pattern = Pattern.compile("import\\s+([\\w\\.]+)")
                lines.forEach { line ->
                    val matcher = pattern.matcher(line)
                    if (matcher.find()) {
                        val imp = matcher.group(1)
                        if (imp != null && !isStandardLibrary(imp, extension)) {
                            imports.add(imp)
                        }
                    }
                }
            }
            "py" -> {
                val pattern1 = Pattern.compile("^import\\s+([\\w\\.]+)")
                val pattern2 = Pattern.compile("^from\\s+([\\w\\.]+)\\s+import")
                lines.forEach { line ->
                    val m1 = pattern1.matcher(line)
                    if (m1.find()) {
                        imports.add(m1.group(1) ?: "")
                    } else {
                        val m2 = pattern2.matcher(line)
                        if (m2.find()) {
                            imports.add(m2.group(1) ?: "")
                        }
                    }
                }
            }
        }
        return imports.distinct()
    }

    private fun isStandardLibrary(importPath: String, extension: String): Boolean {
        return when (extension.lowercase()) {
            "kt" -> importPath.startsWith("kotlin.") || importPath.startsWith("kotlinx.") || importPath.startsWith("java.") || importPath.startsWith("javax.") || importPath.startsWith("android.") || importPath.startsWith("androidx.")
            "java" -> importPath.startsWith("java.") || importPath.startsWith("javax.") || importPath.startsWith("android.") || importPath.startsWith("androidx.")
            else -> false
        }
    }

    private fun findFileForImport(importPath: String, extension: String): File? {
        val parts = importPath.split(".")
        
        // Strategy 1: Direct path from root
        var current = projectRoot
        parts.forEach { part ->
            val next = File(current, part)
            if (next.exists()) {
                current = next
            }
        }
        
        if (current.isFile && current.extension == extension) return current
        
        // Strategy 2: Search for filename in project tree (heuristics for mobile)
        val fileName = parts.last() + "." + extension
        return projectRoot.walkTopDown()
            .maxDepth(10) // Limit depth for performance
            .filter { it.name == fileName }
            .firstOrNull()
    }

    private fun extractSignatures(file: File, extension: String): String {
        val sb = StringBuilder()
        try {
            file.bufferedReader().use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()
                    if (shouldKeepLine(trimmed, extension)) {
                        sb.append(line).append("\n")
                    }
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            // Log error or skip
        }
        return sb.toString()
    }

    private fun shouldKeepLine(line: String, extension: String): Boolean {
        if (line.isEmpty() || line.startsWith("//") || line.startsWith("#") || line.startsWith("/*") || line.startsWith("*")) return false
        
        return when (extension.lowercase()) {
            "kt", "java" -> {
                line.startsWith("package") || 
                line.contains("class ") || 
                line.contains("interface ") || 
                line.contains("object ") || 
                line.contains("enum ") ||
                line.contains("fun ") || 
                line.contains("public ") || 
                line.contains("protected ") ||
                line.contains("val ") || 
                line.contains("var ")
            }
            "py" -> {
                line.startsWith("class ") || line.startsWith("def ")
            }
            else -> false
        }
    }
}

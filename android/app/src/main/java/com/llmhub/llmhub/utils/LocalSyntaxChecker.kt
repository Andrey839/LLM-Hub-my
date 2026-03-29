package com.llmhub.llmhub.utils

import com.llmhub.llmhub.viewmodels.GitHubAgentViewModel

/**
 * High-speed local syntax checker to find obvious errors without AI.
 */
class LocalSyntaxChecker {

    fun check(content: String, extension: String): List<GitHubAgentViewModel.ErrorMarker> {
        val errors = mutableListOf<GitHubAgentViewModel.ErrorMarker>()
        
        // 1. Bracket Matching (General for all languages)
        errors.addAll(checkBrackets(content))
        
        // 2. Language-specific checks
        if (extension.lowercase() == "py") {
            errors.addAll(checkPythonIndentation(content))
        }
        
        return errors.distinctBy { it.lineNumber.toString() + it.message }
    }

    private fun checkBrackets(content: String): List<GitHubAgentViewModel.ErrorMarker> {
        val errors = mutableListOf<GitHubAgentViewModel.ErrorMarker>()
        val stack = mutableListOf<Pair<Char, Int>>()
        val lines = content.lines()
        
        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            line.forEach { char ->
                when (char) {
                    '{', '(', '[' -> stack.add(char to lineNum)
                    '}', ')', ']' -> {
                        if (stack.isEmpty()) {
                            errors.add(GitHubAgentViewModel.ErrorMarker(lineNum, "Unexpected '$char'"))
                        } else {
                            val (open, openLine) = stack.removeAt(stack.size - 1)
                            if (!isMatching(open, char)) {
                                errors.add(GitHubAgentViewModel.ErrorMarker(lineNum, "Mismatched '$char' (partner for '$open' from line $openLine missing)"))
                            }
                        }
                    }
                }
            }
        }
        
        stack.forEach { (open, openLine) ->
            errors.add(GitHubAgentViewModel.ErrorMarker(openLine, "Unclosed '$open'"))
        }
        return errors
    }

    private fun isMatching(open: Char, close: Char): Boolean {
        return (open == '{' && close == '}') || (open == '(' && close == ')') || (open == '[' && close == ']')
    }

    private fun checkPythonIndentation(content: String): List<GitHubAgentViewModel.ErrorMarker> {
        val errors = mutableListOf<GitHubAgentViewModel.ErrorMarker>()
        val lines = content.lines()
        
        lines.forEachIndexed { index, line ->
            val lineNum = index + 1
            val trimmed = line.trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
            
            val currentIndent = line.length - trimmed.length
            
            // Check colon follow-up
            if (index > 0) {
                val prevLine = lines[index - 1].trimEnd()
                if (prevLine.endsWith(":") && !prevLine.trimStart().startsWith("#")) {
                    val prevIndent = lines[index - 1].length - lines[index-1].trimStart().length
                    if (currentIndent <= prevIndent) {
                        errors.add(GitHubAgentViewModel.ErrorMarker(lineNum, "Expected indented block after ':'"))
                    }
                }
            }
        }
        return errors
    }
}

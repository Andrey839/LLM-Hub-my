package com.llmhub.llmhub.utils

data class EditorSettings(
    val useTabHelper: Boolean = true,
    val themeName: EditorThemeName = EditorThemeName.MONOKAI,
    val isAutoAnalysisEnabled: Boolean = false
)

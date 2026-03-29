package com.llmhub.llmhub.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import java.util.regex.Pattern

enum class EditorThemeName {
    MONOKAI, DRACULA, SOLARIZED_LIGHT
}

data class EditorTheme(
    val background: Color,
    val text: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val type: Color,
    val function: Color,
    val number: Color,
    val error: Color = Color.Red
) {
    companion object {
        val Monokai = EditorTheme(
            background = Color(0xFF272822),
            text = Color(0xFFF8F8F2),
            keyword = Color(0xFFF92672),
            string = Color(0xFFA6E22E),
            comment = Color(0xFF75715E),
            type = Color(0xFF66D9EF),
            function = Color(0xFFA6E22E),
            number = Color(0xFFAE81FF)
        )

        val Dracula = EditorTheme(
            background = Color(0xFF282A36),
            text = Color(0xFFF8F8F2),
            keyword = Color(0xFFFF79C6),
            string = Color(0xFFF1FA8C),
            comment = Color(0xFF6272A4),
            type = Color(0xFF8BE9FD),
            function = Color(0xFF50FA7B),
            number = Color(0xFFBD93F9)
        )

        val SolarizedLight = EditorTheme(
            background = Color(0xFFFDF6E3),
            text = Color(0xFF657B83),
            keyword = Color(0xFF859900),
            string = Color(0xFF2AA198),
            comment = Color(0xFF93A1A1),
            type = Color(0xFFB58900),
            function = Color(0xFF268BD2),
            number = Color(0xFFD33682)
        )

        fun getByName(name: String): EditorTheme {
            return when (name.uppercase()) {
                "MONOKAI" -> Monokai
                "DRACULA" -> Dracula
                "SOLARIZED_LIGHT" -> SolarizedLight
                else -> Monokai
            }
        }
    }
}

class SyntaxHighlighter(private val theme: EditorTheme) {

    fun highlight(text: String, extension: String): AnnotatedString {
        return buildAnnotatedString {
            append(text)
            
            val langPatterns = getPatternsForExtension(extension)
            
            // 1. Comments (highest priority)
            applyPattern(text, langPatterns.comment, theme.comment)
            
            // 2. Strings
            applyPattern(text, langPatterns.string, theme.string)
            
            // 3. Keywords
            applyPattern(text, langPatterns.keyword, theme.keyword, FontWeight.Bold)
            
            // 4. Types
            applyPattern(text, langPatterns.type, theme.type)
            
            // 5. Numbers
            applyPattern(text, langPatterns.number, theme.number)
        }
    }

    private fun AnnotatedString.Builder.applyPattern(
        text: String, 
        regex: String, 
        color: Color, 
        fontWeight: FontWeight = FontWeight.Normal
    ) {
        if (regex.isEmpty()) return
        val matcher = Pattern.compile(regex).matcher(text)
        while (matcher.find()) {
            addStyle(SpanStyle(color = color, fontWeight = fontWeight), matcher.start(), matcher.end())
        }
    }

    private data class LangPatterns(
        val keyword: String,
        val type: String,
        val string: String,
        val comment: String,
        val number: String = "\\b\\d+(\\.\\d+)?\\b"
    )

    private fun getPatternsForExtension(ext: String): LangPatterns {
        return when (ext.lowercase().removePrefix(".")) {
            "kt", "kts" -> LangPatterns(
                keyword = "\\b(package|import|class|interface|object|fun|val|var|if|else|when|for|while|do|try|catch|finally|throw|return|is|as|in|this|super|typealias|constructor|init|companion|field|property|receiver|param|set|get|external|abstract|final|open|override|private|public|protected|internal|enum|annotation|sealed|data|inline|noinline|crossinline|tailrec|operator|infix|suspend|const|lateinit|vararg|external|reified|expect|actual)\\b",
                type = "\\b(Any|Unit|Nothing|String|Int|Long|Short|Byte|Double|Float|Boolean|Char|Array|List|Set|Map|MutableList|MutableSet|MutableMap)\\b",
                string = "\"[^\"]*\"|\'[^\']*\'",
                comment = "//.*|/\\*[\\s\\S]*?\\*/"
            )
            "java" -> LangPatterns(
                keyword = "\\b(package|import|class|interface|enum|extends|implements|public|private|protected|static|final|abstract|native|synchronized|transient|volatile|strictfp|void|boolean|char|byte|short|int|long|float|double|if|else|switch|case|default|while|do|for|break|continue|return|try|catch|finally|throw|throws|new|instanceof|this|super|assert|true|false|null)\\b",
                type = "\\b(String|Integer|Long|Short|Byte|Double|Float|Boolean|Character|Object|List|Set|Map|ArrayList|HashMap)\\b",
                string = "\"[^\"]*\"|\'[^\']*\'",
                comment = "//.*|/\\*[\\s\\S]*?\\*/"
            )
            "py" -> LangPatterns(
                keyword = "\\b(def|class|if|elif|else|while|for|in|try|except|finally|with|as|import|from|return|yield|pass|break|continue|lambda|None|True|False|and|or|not|is|del|global|nonlocal|assert|async|await)\\b",
                type = "\\b(int|float|complex|list|tuple|range|str|bytes|bytearray|memoryview|set|frozenset|dict|bool)\\b",
                string = "(\"\"\"[\\s\\S]*?\"\"\"|\'\'\'[\\s\\S]*?\'\'\'|\"[^\"]*\"|\'[^\']*\')",
                comment = "#.*"
            )
            "c", "h" -> LangPatterns(
                keyword = "\\b(if|else|for|while|do|switch|case|default|break|continue|return|goto|sizeof|typeof|struct|union|enum|typedef|static|extern|auto|register|const|volatile|inline|restrict|_Bool|_Complex|_Generic|void|char|short|int|long|float|double|signed|unsigned|#include|#define|#undef|#if|#ifdef|#ifndef|#else|#elif|#endif|#error|#pragma|#line)\\b",
                type = "\\b(size_t|ssize_t|int8_t|int16_t|int32_t|int64_t|uint8_t|uint16_t|uint32_t|uint64_t|bool)\\b",
                string = "\"[^\"]*\"|\'[^\']*\'",
                comment = "//.*|/\\*[\\s\\S]*?\\*/"
            )
            "cpp", "hpp", "cc", "h++" -> LangPatterns(
                keyword = "\\b(class|typename|template|namespace|using|public|private|protected|virtual|override|final|friend|inline|explicit|mutable|noexcept|static_cast|dynamic_cast|reinterpret_cast|const_cast|typeid|operator|new|delete|try|catch|throw|this|true|false|nullptr|if|else|for|while|do|switch|case|default|break|continue|return|goto|sizeof|struct|union|enum|typedef|static|extern|auto|register|const|volatile|void|char|short|int|long|float|double|signed|unsigned|#include|#define|#undef|#if|#ifdef|#ifndef|#else|#elif|#endif|#error|#pragma|#line)\\b",
                type = "\\b(string|vector|list|deque|set|map|multiset|multimap|unordered_set|unordered_map|stack|queue|priority_queue|pair|tuple|unique_ptr|shared_ptr|weak_ptr|int8_t|int16_t|int32_t|int64_t|uint8_t|uint16_t|uint32_t|uint64_t|bool)\\b",
                string = "\"[^\"]*\"|\'[^\']*\'",
                comment = "//.*|/\\*[\\s\\S]*?\\*/"
            )
            else -> LangPatterns("", "", "", "") // Unknown extension
        }
    }
}

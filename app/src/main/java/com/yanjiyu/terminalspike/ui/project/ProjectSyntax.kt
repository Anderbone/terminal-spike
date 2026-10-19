package com.yanjiyu.terminalspike.ui.project

internal enum class CodeTokenKind { KEYWORD, STRING, COMMENT, NUMBER }
internal data class CodeToken(val start: Int, val end: Int, val kind: CodeTokenKind)

/** Lightweight lexical coloring, not a language server. Plain text keeps its original bytes. */
internal fun projectSyntax(text: String, path: String): List<CodeToken> {
    val extension = path.substringAfterLast('.', "").lowercase()
    if (extension !in codeExtensions) return emptyList()
    val hashComments = extension in setOf("py", "sh", "bash", "yaml", "yml", "toml", "rb", "conf")
    val tokens = mutableListOf<CodeToken>()
    var i = 0
    while (i < text.length) {
        val start = i
        val char = text[i]
        when {
            (hashComments && char == '#') || (!hashComments && text.startsWith("//", i)) -> {
                tokens += CodeToken(i, text.length, CodeTokenKind.COMMENT)
                break
            }
            text.startsWith("/*", i) -> {
                i = text.indexOf("*/", i + 2).takeIf { it >= 0 }?.plus(2) ?: text.length
                tokens += CodeToken(start, i, CodeTokenKind.COMMENT)
            }
            char == '"' || char == '\'' || char == '`' -> {
                i++
                while (i < text.length) {
                    if (text[i] == '\\') i = minOf(text.length, i + 2)
                    else if (text[i++] == char) break
                }
                tokens += CodeToken(start, i, CodeTokenKind.STRING)
            }
            char.isDigit() && (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                i++
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] in "._")) i++
                tokens += CodeToken(start, i, CodeTokenKind.NUMBER)
            }
            char.isLetter() || char == '_' -> {
                i++
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                if (text.substring(start, i) in codeKeywords) tokens += CodeToken(start, i, CodeTokenKind.KEYWORD)
            }
            else -> i++
        }
    }
    return tokens
}

private val codeExtensions = setOf("kt", "kts", "java", "js", "jsx", "ts", "tsx", "json", "py", "sh", "bash",
    "c", "h", "cpp", "hpp", "rs", "go", "swift", "cs", "rb", "yaml", "yml", "toml", "conf", "sql", "css", "scss")
private val codeKeywords = setOf("fun", "val", "var", "class", "interface", "object", "data", "sealed", "internal", "private",
    "public", "protected", "override", "open", "final", "abstract", "suspend", "inline", "return", "if", "else", "when",
    "for", "while", "do", "try", "catch", "finally", "throw", "throws", "import", "package", "in", "is", "as", "null",
    "true", "false", "this", "super", "new", "void", "static", "const", "let", "function", "async", "await", "export",
    "default", "switch", "case", "break", "continue", "def", "from", "with", "lambda", "None", "True", "False", "and",
    "or", "not", "pass", "yield", "impl", "pub", "use", "mod", "fn", "struct", "enum", "match", "mut", "trait",
    "type", "SELECT", "FROM", "WHERE", "JOIN", "INSERT", "UPDATE", "DELETE", "CREATE", "TABLE")

package com.wirelesspet.pet

/** Validation shared by archive extraction and model resource lookup. */
object ModelImportPolicy {
    const val MAX_BYTES = 128L * 1024 * 1024
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_FILES = 2048

    /** Bound recursion before untrusted JSON reaches parsers and validation. */
    fun checkJsonDepth(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 48) { "模型 JSON 嵌套过深" } }
                '}', ']' -> depth--
            }
        }
    }

    fun entryPath(raw: String): String {
        require(raw.isNotBlank() && raw.length <= 512) { "素材路径无效" }
        require(!raw.startsWith('/') && '\\' !in raw && ':' !in raw && '\u0000' !in raw) { "不支持绝对路径或网络资源" }
        require(raw.split('/').none { it == ".." || it == "." }) { "素材路径不能越出角色目录" }
        return raw
    }

    fun resourcePath(manifestPath: String, reference: String): String {
        require(reference.isNotBlank() && !reference.startsWith('/') && '\\' !in reference && ':' !in reference && '?' !in reference && '#' !in reference) { "模型引用了不支持的资源路径" }
        val parts = manifestPath.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        reference.split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> { require(parts.isNotEmpty()) { "模型引用越出角色目录" }; parts.removeAt(parts.lastIndex) }
                else -> parts.add(it)
            }
        }
        return entryPath(parts.joinToString("/"))
    }
}

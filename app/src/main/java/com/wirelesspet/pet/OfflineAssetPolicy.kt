package com.wirelesspet.pet

/** 只接收应用自有 HTTPS 来源和 Live2D 资源目录，拒绝路径穿越。 */
object OfflineAssetPolicy {
    fun path(scheme: String?, host: String?, decodedPath: String?): String? {
        if (scheme != "https" || host != "local.wirelesspet") return null
        val path = decodedPath?.removePrefix("/") ?: return null
        if (!path.startsWith("live2d/") || path.split('/').any { it == ".." || it == "." } || '\\' in path) return null
        return path
    }
}

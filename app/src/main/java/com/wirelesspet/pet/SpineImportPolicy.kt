package com.wirelesspet.pet

/** Atlas page names occur at the start of each blank-line-separated page. */
object SpineImportPolicy {
    fun checkVersion(version: String) {
        require(Regex("^(3\\.[78]|4\\.[01])\\.\\d+.*$").matches(version)) {
            "暂不支持 Spine $version；请选择 3.7、3.8、4.0 或 4.1 的素材"
        }
    }
    fun pages(atlas: String): List<String> {
        val pages = atlas.removePrefix("\uFEFF").trim().split(Regex("\\r?\\n\\s*\\r?\\n"))
            .map { it.lineSequence().first().trim() }
        require(pages.isNotEmpty() && pages.size <= 16 && pages.none { it.isBlank() }) { "Spine 图集无效或超过16张贴图" }
        pages.forEach { ModelImportPolicy.entryPath(it) }
        return pages
    }
}

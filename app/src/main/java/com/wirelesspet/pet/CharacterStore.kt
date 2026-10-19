package com.wirelesspet.pet

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/** Imported copies are private to the app; no persisted grants or broad storage permissions. */
class CharacterStore(private val context: Context) {
    private val root = File(context.filesDir, "characters").apply { mkdirs() }

    init { refresh() }

    fun refresh() {
        CharacterCatalog.imported.value = root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.matches(Regex("user_[a-f0-9]{32}")) }
            .mapNotNull { folder -> runCatching {
                val data = JSONObject(File(folder, "character-meta.json").readText())
                require(data.getString("id") == folder.name)
                val kind = data.getString("kind")
                require(CharacterCatalog.supports(kind))
                val file = ModelImportPolicy.entryPath(data.getString("file"))
                require(File(folder, file).isFile)
                CompanionCharacter(folder.name, data.getString("name").take(48), "我的角色 · 本地导入", kind, "user/${folder.name}/$file")
            }.getOrNull() }.sortedBy { it.name }
    }

    fun import(uri: Uri, image: Boolean): CompanionCharacter {
        require(uri.scheme == "content") { "请通过系统文件选择器导入" }
        require(image || CharacterCatalog.modelsEnabled) { "当前构建仅支持 PNG；动态模型需启用相应运行库" }
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: if (image) "我的图片.png" else "我的角色.zip"
        val id = "user_" + UUID.randomUUID().toString().replace("-", "")
        val staging = File(root, ".$id").apply { check(mkdir()) }
        val finished = File(root, id)
        try {
            var kind = "png"
            var modelFile = "image.png"
            context.contentResolver.openInputStream(uri)?.use { input ->
                if (image) {
                    modelFile = "image.png"
                    copyLimited(input, File(staging, modelFile), 32L * 1024 * 1024)
                    validateImage(File(staging, modelFile), pngOnly = true)
                    kind = "png"
                } else {
                    extract(input, staging)
                    val files = staging.walkTopDown().filter { it.isFile }.toList()
                    val candidates = files.filter {
                        it.name == "model.json" || it.name.endsWith(".model3.json") || it.name.endsWith(".model.json")
                    }
                    val atlases = files.filter { it.extension == "atlas" }
                    if (candidates.isEmpty() && atlases.size == 1) {
                        require(CharacterCatalog.supports("spine")) { "当前构建未启用 Spine" }
                        val atlas = atlases.single()
                        val skeletons = files.filter { it.parentFile == atlas.parentFile &&
                            (it.extension == "skel" || (it.extension == "json" && runCatching {
                                JSONObject(it.readText()).has("skeleton")
                            }.getOrDefault(false))) }
                        require(skeletons.size == 1) { "Spine ZIP 需要一个骨骼文件（.skel 或 .json）及一个 .atlas" }
                        val skeleton = skeletons.single()
                        val version = if (skeleton.extension == "json") {
                            JSONObject(skeleton.readText()).getJSONObject("skeleton").getString("spine")
                        } else {
                            val header = skeleton.inputStream().use { input -> ByteArray(64).also { input.read(it) } }.toString(Charsets.ISO_8859_1)
                            Regex("[34]\\.[0-9]+\\.[0-9]+").find(header)?.value ?: error("无法识别 Spine 骨骼版本")
                        }
                        SpineImportPolicy.checkVersion(version)
                        val atlasPath = atlas.relativeTo(staging).invariantSeparatorsPath
                        SpineImportPolicy.pages(atlas.readText()).forEach { ref ->
                            val texture = File(staging, ModelImportPolicy.resourcePath(atlasPath, ref))
                            require(texture.isFile) { "Spine 缺少贴图：$ref" }
                            validateImage(texture)
                        }
                        modelFile = "spine-model.json"
                        require(!File(staging, modelFile).exists()) { "ZIP 包含保留文件名 spine-model.json" }
                        File(staging, modelFile).writeText(JSONObject()
                            .put("skeleton", skeleton.relativeTo(staging).invariantSeparatorsPath)
                            .put("atlas", atlasPath).toString())
                        kind = "spine"
                    } else {
                        require(candidates.size == 1 && atlases.isEmpty()) { "ZIP 请只包含一个 Live2D 或 Spine 模型；不同姿势请分别导入" }
                        val manifest = candidates.single()
                        require(manifest.length() <= 2 * 1024 * 1024) { "模型配置文件过大" }
                        modelFile = manifest.relativeTo(staging).invariantSeparatorsPath
                        val definition = JSONObject(manifest.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
                        kind = if (definition.has("FileReferences")) "cubism4" else "cubism2"
                        require(CharacterCatalog.supports(kind)) { "当前构建未启用 Live2D" }
                        validateReferences(staging, modelFile, definition, kind)
                    }
                }
            } ?: error("无法读取所选文件")
            val file = modelFile
            val type = kind
            val name = displayName.substringBeforeLast('.').take(48).ifBlank { "我的角色" }
            File(staging, "character-meta.json").writeText(JSONObject().put("id", id).put("name", name).put("kind", type).put("file", file).toString())
            check(staging.renameTo(finished)) { "无法保存角色" }
            refresh()
            return CharacterCatalog.find(id)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun open(path: String): InputStream? = runCatching {
        val relative = ModelImportPolicy.entryPath(path)
        val id = relative.substringBefore('/')
        require(id.matches(Regex("user_[a-f0-9]{32}")))
        val directory = File(root, id).canonicalFile
        val file = File(root, relative).canonicalFile
        require(file.path.startsWith(directory.path + File.separator) && file.isFile)
        file.inputStream()
    }.getOrNull()

    private fun extract(input: InputStream, directory: File) {
        var count = 0
        var total = 0L
        var imagePixels = 0L
        val used = mutableSetOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++count <= ModelImportPolicy.MAX_FILES) { "模型文件数量过多" }
                val path = ModelImportPolicy.entryPath(entry.name)
                if (entry.isDirectory || path.startsWith("__MACOSX/") || path.substringAfterLast('/').startsWith('.')) continue
                require(used.add(path.lowercase())) { "ZIP 中存在重复路径" }
                val extension = path.substringAfterLast('.', "").lowercase()
                require(extension in setOf("atlas", "skel", "json", "moc", "moc3", "mtn", "png", "jpg", "jpeg", "wav", "mp3", "txt", "md", "license")) { "模型包含不支持的文件类型：$extension" }
                val target = File(directory, path)
                target.parentFile?.mkdirs()
                val fileLimit = if (extension in setOf("json", "atlas")) 2L * 1024 * 1024 else ModelImportPolicy.MAX_FILE_BYTES
                total += copyLimited(zip, target, minOf(fileLimit, ModelImportPolicy.MAX_BYTES - total))
                if (extension == "json") ModelImportPolicy.checkJsonDepth(target.readText(Charsets.UTF_8))
                if (extension in setOf("png", "jpg", "jpeg")) {
                    imagePixels += validateImage(target)
                    require(imagePixels <= 33_554_432L) { "贴图总像素过多，请缩小贴图后重试" }
                }
            }
        }
        require(count > 0) { "ZIP 中没有模型文件" }
    }

    private fun copyLimited(input: InputStream, target: File, limit: Long): Long {
        var size = 0L
        target.outputStream().use { out ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                size += read
                require(size <= limit) { "素材过大：单文件最多64MB，角色总计最多128MB，PNG最多32MB" }
                out.write(buffer, 0, read)
            }
        }
        return size
    }

    private fun validateImage(file: File, pngOnly: Boolean = false): Long {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(!pngOnly || options.outMimeType == "image/png") { "请选择有效的 PNG 图片" }
        require(options.outWidth in 1..8192 && options.outHeight in 1..8192) { "图片无效或超过8192像素" }
        val pixels = options.outWidth.toLong() * options.outHeight
        require(pixels <= 16_777_216L) { "单张图片最多1677万像素" }
        return pixels
    }

    private fun validateReferences(directory: File, manifest: String, data: JSONObject, kind: String) {
        fun checkFile(ref: String) {
            require(File(directory, ModelImportPolicy.resourcePath(manifest, ref)).isFile) { "模型缺少资源：$ref" }
        }
        val references = if (kind == "cubism4") data.getJSONObject("FileReferences") else data
        checkFile(references.getString(if (kind == "cubism4") "Moc" else "model"))
        val textures = references.getJSONArray(if (kind == "cubism4") "Textures" else "textures")
        require(textures.length() in 1..16) { "模型需要1至16张贴图" }
        for (i in 0 until textures.length()) checkFile(textures.getString(i))
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> node.keys().forEach { key ->
                    val value = node.get(key)
                    if ((key.equals("file", true) || key in setOf("Physics", "Pose", "DisplayInfo", "UserData", "physics", "pose", "Sound", "sound")) && value is String) checkFile(value)
                    else walk(value)
                }
                is JSONArray -> for (i in 0 until node.length()) walk(node.get(i))
            }
        }
        walk(references)
    }
}

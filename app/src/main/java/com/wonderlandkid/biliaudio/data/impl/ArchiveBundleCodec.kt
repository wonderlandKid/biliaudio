package com.wonderlandkid.biliaudio.data.impl

import com.wonderlandkid.biliaudio.data.model.ARCHIVE_MANIFEST_FORMAT
import com.wonderlandkid.biliaudio.data.model.ARCHIVE_MANIFEST_VERSION
import com.wonderlandkid.biliaudio.data.model.ArchiveFormatException
import com.wonderlandkid.biliaudio.data.model.ArchiveManifest
import com.wonderlandkid.biliaudio.data.model.ArchiveManifestFile
import com.wonderlandkid.biliaudio.data.model.ArchiveSettingsDocument
import com.wonderlandkid.biliaudio.data.model.ArchiveTreeEntry
import com.wonderlandkid.biliaudio.data.model.LIBRARY_ARCHIVE_VERSION
import com.wonderlandkid.biliaudio.data.model.LibraryArchive
import com.wonderlandkid.biliaudio.data.model.PLAYLISTS_ARCHIVE_VERSION
import com.wonderlandkid.biliaudio.data.model.PlaylistsArchive
import com.wonderlandkid.biliaudio.data.model.SETTINGS_ARCHIVE_VERSION
import kotlinx.serialization.json.Json

/** v3 bundle 解析结果：各域独立文件。 */
data class BundleContents(
    val library: LibraryArchive,
    val settings: ArchiveSettingsDocument?,
    val playlists: PlaylistsArchive?,
    val covers: Map<String, ByteArray>,
)

/**
 * v3 bundle 编解码：manifest + library + settings + playlists(可选) + covers/。
 *
 * 目录/zip 共用同一内部结构；当前实现目录形态（zip 作为 P2 传输层，不改内部结构）。
 */
class ArchiveBundleCodec(
    private val libraryCodec: LibraryArchiveCodec = LibraryArchiveCodec(),
    private val json: Json = prettyJson,
) {

    /** 生成完整 bundle 条目（manifest 在首位，便于人看；导入不依赖顺序）。 */
    fun buildEntries(
        library: LibraryArchive,
        settings: ArchiveSettingsDocument?,
        playlists: PlaylistsArchive?,
        coverBytes: Map<String, ByteArray>,
        appVersion: String? = null,
    ): List<ArchiveTreeEntry> {
        val normalizedLibrary = libraryCodec.validateAndNormalize(library)
        val files = ArrayList<ArchiveManifestFile>()
        val entries = ArrayList<ArchiveTreeEntry>()

        val libraryBytes = libraryCodec.encode(normalizedLibrary).toByteArray(Charsets.UTF_8)
        files += manifestFile(LIBRARY_FILE, TYPE_LIBRARY, LIBRARY_ARCHIVE_VERSION, libraryBytes, optional = false)
        entries += ArchiveTreeEntry(LIBRARY_FILE, libraryBytes, JSON_MIME)

        val usedCovers = LinkedHashMap<String, Pair<ByteArray, String>>()
        for (album in normalizedLibrary.albums) {
            val cover = album.cover ?: continue
            if (cover.dataBase64 != null) {
                throw ArchiveFormatException("bundle 中的 cover 不能内嵌 dataBase64（应使用 covers/ 文件）")
            }
            val bytes = coverBytes[cover.hash]
                ?: throw ArchiveFormatException("缺少封面文件：covers/${cover.hash}")
            if (bytes.size != cover.byteLength) {
                throw ArchiveFormatException("封面字节数不一致：covers/${cover.hash}")
            }
            if (!LibraryArchiveCodec.sha256Hex(bytes).equals(cover.hash, ignoreCase = true)) {
                throw ArchiveFormatException("封面 hash 校验失败：covers/${cover.hash}")
            }
            usedCovers[cover.hash] = bytes to extensionForMime(cover.mime)
        }
        for ((hash, pair) in usedCovers) {
            val normalizedHash = hash.lowercase()
            val (bytes, ext) = pair
            val path = "$COVERS_DIR$normalizedHash.$ext"
            files += manifestFile(path, TYPE_COVER, null, bytes, optional = false)
            entries += ArchiveTreeEntry(path, bytes, mimeForExtension(ext))
        }

        settings?.let { doc ->
            val bytes = json.encodeToString(ArchiveSettingsDocument.serializer(), doc).toByteArray(Charsets.UTF_8)
            files += manifestFile(SETTINGS_FILE, TYPE_SETTINGS, SETTINGS_ARCHIVE_VERSION, bytes, optional = true)
            entries += ArchiveTreeEntry(SETTINGS_FILE, bytes, JSON_MIME)
        }
        playlists?.takeIf { it.playlists.isNotEmpty() }?.let { doc ->
            val bytes = json.encodeToString(PlaylistsArchive.serializer(), doc).toByteArray(Charsets.UTF_8)
            files += manifestFile(PLAYLISTS_FILE, TYPE_PLAYLISTS, PLAYLISTS_ARCHIVE_VERSION, bytes, optional = true)
            entries += ArchiveTreeEntry(PLAYLISTS_FILE, bytes, JSON_MIME)
        }

        val manifest = ArchiveManifest(
            format = ARCHIVE_MANIFEST_FORMAT,
            version = ARCHIVE_MANIFEST_VERSION,
            kind = "library",
            createdAt = normalizedLibrary.exportedAt,
            app = normalizedLibrary.app,
            files = files,
        )
        val manifestBytes = json.encodeToString(ArchiveManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
        return listOf(ArchiveTreeEntry(MANIFEST_FILE, manifestBytes, JSON_MIME)) + entries
    }

    /** 解析目录/zip 解出的文件集合；严格校验 manifest 与文件哈希。 */
    fun parseEntries(entries: List<ArchiveTreeEntry>): BundleContents {
        val files = LinkedHashMap<String, ArchiveTreeEntry>()
        for (entry in entries) {
            val path = normalizePath(entry.relativePath)
                ?: throw ArchiveFormatException("非法归档路径：${entry.relativePath}")
            if (files.put(path, entry) != null) {
                throw ArchiveFormatException("归档中存在重复文件：$path")
            }
        }
        val manifestEntry = files[MANIFEST_FILE]
            ?: throw ArchiveFormatException("缺少 manifest.json，不是 v3 bundle")
        val manifest = runCatching {
            json.decodeFromString(ArchiveManifest.serializer(), manifestEntry.bytes.toString(Charsets.UTF_8))
        }.getOrElse { e ->
            throw ArchiveFormatException("manifest.json 解析失败：${e.message ?: "格式不正确"}")
        }
        if (manifest.format != ARCHIVE_MANIFEST_FORMAT) {
            throw ArchiveFormatException("不是 $ARCHIVE_MANIFEST_FORMAT 容器")
        }
        if (manifest.version > ARCHIVE_MANIFEST_VERSION) {
            throw ArchiveFormatException("容器版本 v${manifest.version} 高于当前支持的 v$ARCHIVE_MANIFEST_VERSION，请升级 App")
        }
        if (manifest.kind != "library") {
            throw ArchiveFormatException("当前只支持整库 bundle（kind=library），实际 ${manifest.kind}")
        }

        // 先校验 manifest 列出的每个文件的 hash/大小
        for (file in manifest.files) {
            val entry = files[file.path]
            if (entry == null) {
                if (file.optional) continue
                throw ArchiveFormatException("归档缺少必需文件：${file.path}")
            }
            file.bytes?.let { expected ->
                if (entry.bytes.size.toLong() != expected) {
                    throw ArchiveFormatException("文件大小不一致：${file.path}")
                }
            }
            file.sha256?.let { expected ->
                val actual = LibraryArchiveCodec.sha256Hex(entry.bytes)
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw ArchiveFormatException("文件校验失败：${file.path}")
                }
            }
        }

        val libraryPath = manifest.files.firstOrNull { it.type == TYPE_LIBRARY }?.path
            ?: throw ArchiveFormatException("manifest 中没有 library 文件")
        val libraryEntry = files[libraryPath]
            ?: throw ArchiveFormatException("归档缺少 library.json")
        val library = libraryCodec.parse(libraryEntry.bytes.toString(Charsets.UTF_8)).archive
        if (library.kind != com.wonderlandkid.biliaudio.data.model.ArchiveKind.LIBRARY) {
            throw ArchiveFormatException("bundle 中的 library 文件 kind 必须为 library")
        }

        val coverDirFiles = files.filterKeys { it.startsWith(COVERS_DIR) }
        val covers = LinkedHashMap<String, ByteArray>()
        for ((path, entry) in coverDirFiles) {
            val fileName = path.removePrefix(COVERS_DIR)
            if (fileName.isBlank() || fileName.contains('/')) {
                throw ArchiveFormatException("非法封面路径：$path")
            }
            val hash = fileName.substringBeforeLast('.', fileName)
            if (!LibraryArchiveCodec.sha256Hex(entry.bytes).equals(hash, ignoreCase = true)) {
                throw ArchiveFormatException("封面文件 hash 不匹配：$path")
            }
            covers[hash] = entry.bytes
        }
        for (album in library.albums) {
            val cover = album.cover ?: continue
            if (cover.dataBase64 != null) {
                throw ArchiveFormatException("bundle 中的 cover 不能内嵌 dataBase64（应使用 covers/ 文件）")
            }
            val bytes = covers[cover.hash]
                ?: throw ArchiveFormatException("缺少封面文件：covers/${cover.hash}")
            if (bytes.size != cover.byteLength) {
                throw ArchiveFormatException("封面 byteLength 不一致：covers/${cover.hash}")
            }
        }

        val settings = manifest.files.firstOrNull { it.type == TYPE_SETTINGS }?.let { file ->
            val entry = files[file.path] ?: return@let null
            runCatching {
                json.decodeFromString(ArchiveSettingsDocument.serializer(), entry.bytes.toString(Charsets.UTF_8))
            }.getOrElse { e ->
                throw ArchiveFormatException("settings.json 解析失败：${e.message ?: "格式不正确"}")
            }
        }
        settings?.let {
            if (it.format != com.wonderlandkid.biliaudio.data.model.SETTINGS_ARCHIVE_FORMAT) {
                throw ArchiveFormatException("settings.json format 不正确")
            }
            if (it.version > SETTINGS_ARCHIVE_VERSION) {
                throw ArchiveFormatException("settings 版本过高，请升级 App")
            }
        }

        val playlists = manifest.files.firstOrNull { it.type == TYPE_PLAYLISTS }?.let { file ->
            val entry = files[file.path] ?: return@let null
            runCatching {
                json.decodeFromString(PlaylistsArchive.serializer(), entry.bytes.toString(Charsets.UTF_8))
            }.getOrElse { e ->
                throw ArchiveFormatException("playlists.json 解析失败：${e.message ?: "格式不正确"}")
            }
        }
        playlists?.let {
            if (it.format != com.wonderlandkid.biliaudio.data.model.PLAYLISTS_ARCHIVE_FORMAT) {
                throw ArchiveFormatException("playlists.json format 不正确")
            }
            if (it.version > PLAYLISTS_ARCHIVE_VERSION) {
                throw ArchiveFormatException("playlists 版本过高，请升级 App")
            }
        }

        return BundleContents(
            library = library,
            settings = settings,
            playlists = playlists,
            covers = covers,
        )
    }

    private fun extensionForMime(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private fun mimeForExtension(ext: String): String = when (ext) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    private fun manifestFile(
        path: String,
        type: String,
        version: Int?,
        bytes: ByteArray,
        optional: Boolean,
    ): ArchiveManifestFile = ArchiveManifestFile(
        path = path,
        type = type,
        version = version,
        optional = optional,
        sha256 = LibraryArchiveCodec.sha256Hex(bytes),
        bytes = bytes.size.toLong(),
    )

    private fun normalizePath(raw: String): String? {
        val parts = raw.replace('\\', '/').split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val LIBRARY_FILE = "library.json"
        const val SETTINGS_FILE = "settings.json"
        const val PLAYLISTS_FILE = "playlists.json"
        const val COVERS_DIR = "covers/"
        const val TYPE_LIBRARY = "library"
        const val TYPE_SETTINGS = "settings"
        const val TYPE_PLAYLISTS = "playlists"
        const val TYPE_COVER = "cover"
        private const val JSON_MIME = "application/json"

        val prettyJson: Json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}

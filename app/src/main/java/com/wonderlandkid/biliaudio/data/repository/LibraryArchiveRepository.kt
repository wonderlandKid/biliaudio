package com.wonderlandkid.biliaudio.data.repository

import com.wonderlandkid.biliaudio.data.model.ArchiveImportMode
import com.wonderlandkid.biliaudio.data.model.ArchiveImportReport
import com.wonderlandkid.biliaudio.data.model.ArchivePreview
import com.wonderlandkid.biliaudio.data.model.ArchiveTreeEntry

/**
 * 音乐库归档：导出/导入（.biliaudio.json）。
 *
 * 统一承载「整库备份 / 单专辑分享 / 仅分割点」三种用途，格式见 docs/LIBRARY_ARCHIVE.md。
 */
interface LibraryArchiveRepository {
    /** 导出整个音乐库（含设置与用户数据）为 JSON 文本。 */
    suspend fun exportAllJson(): String

    /** 导出单张专辑为 JSON 文本；专辑不存在返回 null。 */
    suspend fun exportAlbumJson(albumId: Long): String?

    /** 解析 + 预演，不写库；用于导入前预览。 */
    suspend fun preview(text: String, mode: ArchiveImportMode): ArchivePreview

    /** 执行导入。 */
    suspend fun importArchive(text: String, mode: ArchiveImportMode): ArchiveImportReport

    /**
     * 导出为「目录形态」归档（v0.6.0）：
     * ```
     * biliaudio-library.json        // 根索引：格式/版本/导出时间/设置
     * <艺术家>/<专辑>/album.json    // 专辑元信息（含内嵌封面 coverBase64）
     * <艺术家>/<专辑>/segments.json // 专辑分割点
     * ```
     * 返回按层级排好序的文件条目，UI 用 SAF 目录写入。
     */
    suspend fun exportTreeEntries(): List<ArchiveTreeEntry>

    /**
     * 从「目录形态」归档导入：读取 entries 中的 album.json / segments.json，
     * 与对应专辑合并；导入语义与 [importArchive] 的 [mode] 一致。
     */
    suspend fun importTreeEntries(entries: List<ArchiveTreeEntry>, mode: ArchiveImportMode): ArchiveImportReport

    /** 最近一次「覆盖全库」自动备份的时间（毫秒），没有返回 null。 */
    fun lastAutoBackupAt(): Long?
}

package com.wonderlandkid.biliaudio.data.repository

import com.wonderlandkid.biliaudio.data.model.ArchiveImportMode
import com.wonderlandkid.biliaudio.data.model.ArchiveImportReport
import com.wonderlandkid.biliaudio.data.model.ArchivePreview
import com.wonderlandkid.biliaudio.data.model.ArchiveTreeEntry

/**
 * 音乐库归档（v3 分域 bundle + 单分享文件），格式见 docs/ARCHIVE_LAYOUT_V3.md。
 *
 * - 整库备份 = 目录 bundle：manifest.json + library.json + settings.json(可选) + covers/；
 * - 单专辑 / 分割点仍支持单 JSON 分享。
 */
interface LibraryArchiveRepository {
    /** 导出单张专辑为 JSON 文本；专辑不存在返回 null。 */
    suspend fun exportAlbumJson(albumId: Long): String?

    /** 解析 + 预演，不写库；用于导入前预览。 */
    suspend fun preview(text: String, mode: ArchiveImportMode): ArchivePreview

    /** 执行导入。 */
    suspend fun importArchive(text: String, mode: ArchiveImportMode): ArchiveImportReport

    /**
     * 导出整库 bundle 的全部文件：
     * ```
     * manifest.json    // 文件清单 + sha256
     * library.json     // 专辑 + 分曲（封面只引用 hash）
     * settings.json    // 可移植设置白名单
     * covers/<sha256>  // 内容寻址封面
     * ```
     * UI 用 SAF 目录写入；zip 只是传输层，格式不变。
     */
    suspend fun exportTreeEntries(): List<ArchiveTreeEntry>

    /**
     * 从整库 bundle 目录导入：校验 manifest + 各域文件，再按 [mode] 导入 library 域；
     * settings 默认只在「覆盖全库」时恢复。
     */
    suspend fun importTreeEntries(entries: List<ArchiveTreeEntry>, mode: ArchiveImportMode): ArchiveImportReport

    /** 最近一次「覆盖全库」自动备份的时间（毫秒），没有返回 null。 */
    fun lastAutoBackupAt(): Long?
}

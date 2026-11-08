package com.wonderlandkid.biliaudio.data.repository

import com.wonderlandkid.biliaudio.data.model.UserDataBackup
import com.wonderlandkid.biliaudio.data.model.UserDataImportReport

/**
 * 歌单 + 听歌统计的独立备份（JSON），不混入音乐库归档。
 *
 * 导出/导入使用专辑稳定键 + 分曲 startMs 定位，不依赖本机 trackId/trackUid。
 */
interface UserDataBackupRepository {
    suspend fun exportBackup(): UserDataBackup
    suspend fun exportJson(): String
    suspend fun importJson(json: String): UserDataImportReport
}

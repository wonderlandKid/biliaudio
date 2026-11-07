package com.wonderlandkid.biliaudio.ui.components

import com.wonderlandkid.biliaudio.data.model.Album
import com.wonderlandkid.biliaudio.data.model.TrackWithAlbum
import com.wonderlandkid.biliaudio.player.QueueEntry
import java.io.File

/**
 * 封面展示模型：
 * - 有用户自定义封面（本机文件存在）→ 用本地 [File]；
 * - 否则回退 B 站远程封面 URL；
 * - 都没有 → null（Coil 显示占位色块）。
 *
 * 供 `CoverImage(url = ...)` 使用（Coil 支持 File / URL / Uri）。
 */
fun Album.coverModel(): Any? = localCoverFile(coverLocalPath) ?: coverUrl.takeIf { it.isNotBlank() }

fun TrackWithAlbum.coverModel(): Any? = album.coverModel()

fun QueueEntry.coverModel(): Any? = localCoverFile(coverLocalPath) ?: coverUrl.takeIf { it.isNotBlank() }

private fun localCoverFile(path: String?): File? =
    path?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

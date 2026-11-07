package com.wonderlandkid.biliaudio.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.BuildConfig
import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import com.wonderlandkid.biliaudio.data.repository.AuthState
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.theme.Dimens
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.util.Diag
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页（底部导航 Tab）：
 * - 账号卡片：未登录 → 去登录；已登录 → 头像 / 昵称 / 退出登录（二次确认）；
 * - 音质选择（写入 [SettingsRepository.setAudioQuality]）；
 * - 深色模式（跟随系统 / 浅色 / 深色，写入 [SettingsRepository.setDarkTheme]）；
 * - 关于：版本号（BuildConfig.VERSION_NAME）、应用说明、B 站内容版权说明。
 *
 * @param onOpenLogin 未登录时点击「去登录」的回调（由导航层打开扫码登录页）
 * @param onOpenBackupImport 点击「导入 / 恢复」的回调（由导航层打开导入页的「归档导入」Tab）
 * @param onOpenDiagnostics 点击「诊断日志」的回调（由导航层打开诊断日志页）
 */
@Composable
fun SettingsScreen(
    onOpenLogin: () -> Unit,
    onOpenBackupImport: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = appViewModel { container ->
        SettingsViewModel(
            settingsRepository = container.settingsRepository,
            authRepository = container.authRepository,
            libraryArchiveRepository = container.libraryArchiveRepository,
            appContext = container.appContext,
        )
    }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val lastAutoBackupAt by viewModel.lastAutoBackupAt.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    // 导出音乐库：SAF 选择保存位置（默认文件名带时间戳）
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.exportLibrary(uri)
    }
    var showLogoutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }
    // 从导入页返回时可能刚产生自动备份，重新读取一次
    LaunchedEffect(Unit) {
        viewModel.refreshLastAutoBackup()
    }
    // 诊断日志行数（设置页入口展示用，定期刷新）
    var diagLineCount by remember { mutableStateOf(Diag.lineCount()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            diagLineCount = Diag.lineCount()
            delay(2000)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.lg,
                // 外层 Scaffold 已让出底部导航/迷你播放条高度，这里只留呼吸空间
                bottom = Spacing.xxxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            item {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.padding(horizontal = Spacing.xs),
                )
            }
            item {
                AccountCard(
                    authState = authState,
                    onOpenLogin = onOpenLogin,
                    onLogoutClick = { showLogoutDialog = true },
                )
            }
            item { SectionTitle("播放") }
            item {
                AudioQualityCard(
                    selected = settings.audioQuality,
                    onSelect = viewModel::setAudioQuality,
                )
            }
            item { SectionTitle("外观") }
            item {
                DarkThemeCard(
                    darkTheme = settings.darkTheme,
                    onSelect = viewModel::setDarkTheme,
                )
            }
            item { SectionTitle("备份与恢复") }
            item {
                BackupRestoreCard(
                    isExporting = isExporting,
                    lastAutoBackupAt = lastAutoBackupAt,
                    onExportClick = { exportLauncher.launch(defaultBackupFileName()) },
                    onImportClick = onOpenBackupImport,
                )
            }
            item { SectionTitle("诊断与维护") }
            item {
                DiagnosticsEntryCard(
                    lineCount = diagLineCount,
                    onClick = onOpenDiagnostics,
                )
            }
            item { SectionTitle("关于") }
            item { AboutCard() }
            item {
                Text(
                    text = "哔哩专辑 · 仅供个人学习与本地播放使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.md),
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(Spacing.lg),
        )
    }

    // 退出登录二次确认
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("退出后部分高音质音频可能无法获取，确定要退出当前 B 站账号吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                    },
                ) {
                    Text("退出登录", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 组件
// ---------------------------------------------------------------------------

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.xs, top = Spacing.xs),
    )
}

/** 统一的大圆角设置卡片。 */
@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.cardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            content = content,
        )
    }
}

/** 账号卡片：未登录 → 去登录；已登录 → 头像/昵称/退出。 */
@Composable
private fun AccountCard(
    authState: AuthState,
    onOpenLogin: () -> Unit,
    onLogoutClick: () -> Unit,
) {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (authState) {
                is AuthState.LoggedIn -> {
                    CoverImage(
                        url = authState.profile.avatarUrl,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape),
                        cornerRadius = 28.dp,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = authState.profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "UID ${authState.profile.mid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = onLogoutClick,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
                        ),
                        contentPadding = PaddingValues(horizontal = Spacing.md),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text("退出登录")
                    }
                }

                AuthState.LoggedOut, AuthState.Unknown -> {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (authState is AuthState.Unknown) "正在检查登录状态…" else "未登录",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "登录后可展示头像昵称与可用音质",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (authState is AuthState.Unknown) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                        )
                    } else {
                        Button(
                            onClick = onOpenLogin,
                            shape = RoundedCornerShape(50),
                        ) {
                            Text("去登录")
                        }
                    }
                }
            }
        }
    }
}

/** 音质单选。 */
@Composable
private fun AudioQualityCard(
    selected: AudioQualityPreference,
    onSelect: (AudioQualityPreference) -> Unit,
) {
    SettingsCard {
        AudioQualityPreference.entries.forEachIndexed { index, preference ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelect(preference) }
                    .padding(horizontal = Spacing.xs, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preference.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (preference == selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        text = qualityDescription(preference),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RadioButton(
                    selected = preference == selected,
                    onClick = { onSelect(preference) },
                )
            }
            if (index != AudioQualityPreference.entries.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

private fun qualityDescription(preference: AudioQualityPreference): String = when (preference) {
    AudioQualityPreference.AUTO -> "按账号权限选择最高可用音质"
    AudioQualityPreference.HIGH -> "192K，音质最好，流量较大"
    AudioQualityPreference.MEDIUM -> "132K，音质与流量平衡"
    AudioQualityPreference.LOW -> "64K，流量最小，适合移动网络"
}

/** 深色模式单选：跟随系统 / 浅色 / 深色。 */
@Composable
private fun DarkThemeCard(
    darkTheme: Boolean?,
    onSelect: (Boolean?) -> Unit,
) {
    val options = listOf(
        Triple("跟随系统", null, "与手机系统的深色模式保持一致"),
        Triple("浅色", false, "始终使用浅色主题"),
        Triple("深色", true, "始终使用深色主题"),
    )
    SettingsCard {
        options.forEachIndexed { index, option ->
            val (label, value, description) = option
            val selected = darkTheme == value
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelect(value) }
                    .padding(horizontal = Spacing.xs, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RadioButton(
                    selected = selected,
                    onClick = { onSelect(value) },
                )
            }
            if (index != options.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

/** 关于：版本号、应用说明、版权与技术栈。 */
@Composable
private fun AboutCard() {
    SettingsCard {
        // 版本
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "版本",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        // 应用说明
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(text = "关于哔哩专辑", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "哔哩专辑（BiliAudio）把 B 站上「一整张专辑装在一个视频里」的内容，" +
                    "转换成可按歌曲搜索、可按分曲播放的音乐库：支持 BV 号 / 批量 / 搜索导入、" +
                    "自定义分曲时间点、后台播放与歌词页看原视频。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        // 版权说明
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(text = "内容版权说明", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "所有音频、视频与图片内容版权归哔哩哔哩（bilibili）及原 UP 主所有。" +
                    "本应用仅用于个人学习与本地播放，不提供内容下载、缓存或分发，" +
                    "与哔哩哔哩官方无关。请支持正版并尊重 UP 主权益。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        // 技术栈
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xs, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(text = "技术栈", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Kotlin · Jetpack Compose · Material 3 · Media3 · Room · Coil · zxing",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 备份与恢复
// ---------------------------------------------------------------------------

/** 备份与恢复卡片：导出音乐库 / 导入恢复 / 上次自动备份时间。 */
@Composable
private fun BackupRestoreCard(
    isExporting: Boolean,
    lastAutoBackupAt: Long?,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
) {
    SettingsCard {
        // ---- 导出音乐库 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                imageVector = Icons.Rounded.FileDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "导出音乐库", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "导出全部专辑与分曲时间点为 JSON 备份文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Button(
                onClick = onExportClick,
                enabled = !isExporting,
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = Spacing.md),
            ) {
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("导出中")
                } else {
                    Text("导出")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        // ---- 导入 / 恢复 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                imageVector = Icons.Rounded.FileUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "导入 / 恢复", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "从备份文件恢复，或导入分享的专辑 / 分割点文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            OutlinedButton(
                onClick = onImportClick,
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = Spacing.md),
            ) {
                Text("导入")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        // ---- 上次自动备份 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "上次自动备份",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = lastAutoBackupAt?.let { formatBackupTime(it) } ?: "暂无自动备份",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            text = "「覆盖全库」导入前会自动备份当前音乐库，自动备份保留最近 10 份。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )
    }
}

/** 默认导出文件名：biliaudio-library-yyyyMMdd-HHmm.json */
private fun defaultBackupFileName(): String =
    "biliaudio-library-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.json"

/** 自动备份时间展示：yyyy-MM-dd HH:mm */
private fun formatBackupTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

// ---------------------------------------------------------------------------
// 诊断与维护
// ---------------------------------------------------------------------------

/** 「诊断日志」入口卡片：显示最近日志行数，点击进入诊断日志页。 */
@Composable
private fun DiagnosticsEntryCard(
    lineCount: Int,
    onClick: () -> Unit,
) {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                imageVector = Icons.Rounded.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "诊断日志", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "查看 / 复制 / 导出运行日志，帮助定位播放、缓冲、导入问题",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "$lineCount 行",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

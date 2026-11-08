package com.wonderlandkid.biliaudio.ui.settings

import android.net.Uri
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
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Save
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.BuildConfig
import com.wonderlandkid.biliaudio.data.model.ArchiveImportMode
import com.wonderlandkid.biliaudio.data.model.AudioQualityPreference
import com.wonderlandkid.biliaudio.data.repository.AuthState
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.BiliGroup
import com.wonderlandkid.biliaudio.ui.components.BiliGroupItem
import com.wonderlandkid.biliaudio.ui.components.BiliGroupSectionTitle
import com.wonderlandkid.biliaudio.ui.components.BiliPageHeader
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.theme.Dimens
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import com.wonderlandkid.biliaudio.ui.theme.entrance
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
 * @param onOpenCacheManagement 点击「缓存管理」的回调（由导航层打开缓存管理页）
 */
@Composable
fun SettingsScreen(
    onOpenLogin: () -> Unit,
    onOpenBackupImport: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenCacheManagement: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = appViewModel { container ->
        SettingsViewModel(
            settingsRepository = container.settingsRepository,
            authRepository = container.authRepository,
            libraryArchiveRepository = container.libraryArchiveRepository,
            userDataBackupRepository = container.userDataBackupRepository,
            appContext = container.appContext,
        )
    }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val lastAutoBackupAt by viewModel.lastAutoBackupAt.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val isExportingTree by viewModel.isExportingTree.collectAsStateWithLifecycle()
    val isImportingTree by viewModel.isImportingTree.collectAsStateWithLifecycle()
    val treeImportReport by viewModel.treeImportResult.collectAsStateWithLifecycle()

    // 用户数据（歌单/统计）独立 JSON 备份：与音乐库归档完全分开
    val userDataExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportUserData(uri) }
    val userDataImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.importUserData(uri) }

    // 目录归档：导出到文件夹 / 从文件夹导入（SAF 选择目录）
    val treeExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.exportTreeToFolder(uri)
    }
    var pendingTreeImportUri by remember { mutableStateOf<Uri?>(null) }
    var treeImportMode by remember { mutableStateOf(ArchiveImportMode.MERGE) }
    val treeImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            treeImportMode = ArchiveImportMode.MERGE
            pendingTreeImportUri = uri
        }
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
                // 与发现/音乐库一致：页头由 BiliPageHeader 自带水平 Spacing.lg
                top = Spacing.md,
                bottom = Spacing.xxxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            item(key = "header") {
                BiliPageHeader(
                    title = "设置",
                    subtitle = "哔哩专辑 v${BuildConfig.VERSION_NAME} · 备份、外观与账号",
                )
            }
            item {
                SettingsPadded {
                    AccountCard(
                        authState = authState,
                        onOpenLogin = onOpenLogin,
                        onLogoutClick = { showLogoutDialog = true },
                    )
                }
            }
            item { SettingsPadded { SectionTitle("播放") } }
            item {
                SettingsPadded {
                    AudioQualityCard(
                        selected = settings.audioQuality,
                        onSelect = viewModel::setAudioQuality,
                    )
                }
            }
            item { SettingsPadded { SectionTitle("外观") } }
            item {
                SettingsPadded {
                    DarkThemeCard(
                        darkTheme = settings.darkTheme,
                        onSelect = viewModel::setDarkTheme,
                    )
                }
            }
            item { SettingsPadded { SectionTitle("备份与恢复") } }
            item {
                SettingsPadded {
                    BackupRestoreCard(
                        isExportingTree = isExportingTree,
                        isImportingTree = isImportingTree,
                        lastAutoBackupAt = lastAutoBackupAt,
                        onImportShareClick = onOpenBackupImport,
                        onExportTreeClick = { treeExportLauncher.launch(null) },
                        onImportTreeClick = { treeImportLauncher.launch(null) },
                    )
                }
            }
            item { SettingsPadded { SectionTitle("用户数据") } }
            item {
                SettingsPadded {
                    UserDataBackupCard(
                        onExport = {
                            userDataExportLauncher.launch(buildUserDataFileName())
                        },
                        onImport = {
                            userDataImportLauncher.launch(
                                arrayOf("application/json", "text/plain", "application/octet-stream"),
                            )
                        },
                    )
                }
            }
            item { SettingsPadded { SectionTitle("存储") } }
            item {
                SettingsPadded {
                    CacheManagementEntryCard(onClick = onOpenCacheManagement)
                }
            }
            item { SettingsPadded { SectionTitle("诊断与维护") } }
            item {
                SettingsPadded {
                    DiagnosticsToggleCard(
                        enabled = settings.diagnosticLogsEnabled,
                        onCheckedChange = viewModel::setDiagnosticLogsEnabled,
                    )
                }
            }
            item {
                SettingsPadded {
                    DiagnosticsEntryCard(
                        lineCount = diagLineCount,
                        onClick = onOpenDiagnostics,
                    )
                }
            }
            item { SettingsPadded { SectionTitle("关于") } }
            item { SettingsPadded { AboutCard() } }
            item {
                Text(
                    text = "哔哩专辑 · 仅供个人学习与本地播放使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
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

    // 从文件夹导入：先选导入模式（合并 / 仅更新分割点 / 覆盖全库）
    val treeImportUri = pendingTreeImportUri
    if (treeImportUri != null) {
        AlertDialog(
            onDismissRequest = { pendingTreeImportUri = null },
            title = { Text("从文件夹导入") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        text = "按「顶层 → 艺术家 → 专辑 → 分割」目录结构读取 *.json 归档，请选择导入模式：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TreeImportModeOption(
                        label = "合并导入",
                        description = "按 BV + 分 P 匹配：不存在新建，已存在更新元信息与分曲",
                        selected = treeImportMode == ArchiveImportMode.MERGE,
                        onSelect = { treeImportMode = ArchiveImportMode.MERGE },
                    )
                    TreeImportModeOption(
                        label = "仅更新分割点",
                        description = "只更新已存在专辑的分曲，不新建专辑、不改元信息",
                        selected = treeImportMode == ArchiveImportMode.SEGMENTS_ONLY,
                        onSelect = { treeImportMode = ArchiveImportMode.SEGMENTS_ONLY },
                    )
                    TreeImportModeOption(
                        label = "覆盖全库",
                        description = "先自动备份当前音乐库，再清空并导入（危险）",
                        selected = treeImportMode == ArchiveImportMode.REPLACE,
                        onSelect = { treeImportMode = ArchiveImportMode.REPLACE },
                        isDanger = true,
                    )
                    if (treeImportMode == ArchiveImportMode.REPLACE) {
                        Text(
                            text = "覆盖前会自动备份到应用私有目录；如需长期保留，请先在「单文件备份」里导出。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingTreeImportUri = null
                        viewModel.importTreeFromFolder(treeImportUri, treeImportMode)
                    },
                ) { Text("开始导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTreeImportUri = null }) { Text("取消") }
            },
        )
    }

    // 目录导入结果：统计 + 覆盖全库时的自动备份路径
    val treeReport = treeImportReport
    if (treeReport != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissTreeImportResult,
            title = { Text("目录导入完成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        text = "新建 ${treeReport.createdCount} · 更新 ${treeReport.updatedCount} · " +
                            "分曲更新 ${treeReport.segmentsUpdatedCount} · 跳过 ${treeReport.skippedCount} · " +
                            "失败 ${treeReport.failedCount}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (treeReport.mode == ArchiveImportMode.REPLACE) {
                        val backupPath = treeReport.backupFilePath
                        if (backupPath != null) {
                            Text(
                                text = "覆盖前已自动备份：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = backupPath,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text(
                                text = "自动备份失败，请确认已手动导出备份。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (treeReport.parseErrors.isNotEmpty()) {
                        Text(
                            text = "有 ${treeReport.parseErrors.size} 条条目解析失败，详见诊断日志。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissTreeImportResult) { Text("确定") }
            },
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
    BiliGroupSectionTitle(title = title)
}

/** 设置卡片统一走通用分组模板。 */
@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BiliGroup(modifier = modifier, content = content)
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

/** 备份与恢复卡片：整库 bundle（manifest + library + settings + covers）+ 单分享文件导入。 */
@Composable
private fun BackupRestoreCard(
    isExportingTree: Boolean,
    isImportingTree: Boolean,
    lastAutoBackupAt: Long?,
    onImportShareClick: () -> Unit,
    onExportTreeClick: () -> Unit,
    onImportTreeClick: () -> Unit,
) {
    SettingsCard {
        Text(
            text = "整库备份（bundle）",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        Text(
            text = "按「manifest.json + library.json + settings.json + covers/」分域目录备份，" +
                "可人工浏览、跨设备恢复；zip 只是传输层。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xs),
        )
        SettingsActionRow(
            icon = Icons.Rounded.Save,
            title = "导出整库到文件夹",
            description = "生成 manifest/library/settings/covers（同名文件覆盖）",
            action = {
                OutlinedButton(
                    onClick = onExportTreeClick,
                    enabled = !isExportingTree && !isImportingTree,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = Spacing.sm),
                ) {
                    if (isExportingTree) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text("导出中")
                    } else {
                        Text("选择文件夹")
                    }
                }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        SettingsActionRow(
            icon = Icons.Rounded.FolderOpen,
            title = "从文件夹恢复",
            description = "选择 bundle 目录，校验 manifest/hash 后按所选模式导入",
            action = {
                OutlinedButton(
                    onClick = onImportTreeClick,
                    enabled = !isExportingTree && !isImportingTree,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = Spacing.sm),
                ) {
                    if (isImportingTree) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text("导入中")
                    } else {
                        Text("选择文件夹")
                    }
                }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        SettingsActionRow(
            icon = Icons.Rounded.FileUpload,
            title = "导入分享文件",
            description = "导入单专辑 / 分割点 JSON（整库恢复请用上面的 bundle 目录）",
            action = {
                OutlinedButton(
                    onClick = onImportShareClick,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = Spacing.md),
                ) {
                    Text("导入")
                }
            },
        )
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
            text = "「覆盖全库」前会自动备份为 bundle 目录，保留最近 10 份；设置默认不覆盖本地。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )
    }
}

/** 设置页条目统一水平内边距（页头 [BiliPageHeader] 自带 Spacing.lg）。 */
@Composable
private fun SettingsPadded(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            // 页头 BiliPageHeader 不经过这里，保持静态；只有列表/卡片内容入场
            .entrance(order = 0),
    ) {
        content()
    }
}

/** 统一的「图标 + 标题 + 说明 + 操作按钮」设置行。 */
@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    description: String,
    action: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        action()
    }
}

/** 目录导入模式单选项（AlertDialog 内）。 */
@Composable
private fun TreeImportModeOption(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
    isDanger: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDanger) {
                    MaterialTheme.colorScheme.error
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
    }
}

/** 自动备份时间展示：yyyy-MM-dd HH:mm */
private fun formatBackupTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

// ---------------------------------------------------------------------------
// 诊断与维护
// ---------------------------------------------------------------------------

/** 「维测日志」开关：关闭后 Diag 不再记录/打印（立即生效并持久化）。 */
@Composable
private fun DiagnosticsToggleCard(
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                imageVector = Icons.Rounded.BugReport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "维测日志",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (enabled) {
                        "默认关闭；开启时记录播放/网络/导入等诊断日志（Logcat tag BiliDiag）"
                    } else {
                        "已关闭：不再记录/打印诊断日志（历史日志仍可查看/导出）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

/** 「诊断日志」入口卡片：显示最近日志行数，点击进入诊断日志页。 */
@Composable
private fun UserDataBackupCard(
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    SettingsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Backup,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "歌单与听歌统计备份", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "独立 JSON，不含音乐库专辑/分曲/封面/缓存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                ) { Text("导出") }
                androidx.compose.material3.Button(
                    onClick = onImport,
                    modifier = Modifier.weight(1f),
                ) { Text("导入") }
            }
        }
    }
}

private fun buildUserDataFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "biliaudio-user-data-$stamp.json"
}

@Composable
private fun CacheManagementEntryCard(onClick: () -> Unit) {
    BiliGroup(contentPadding = PaddingValues(0.dp)) {
        BiliGroupItem(
            title = "缓存管理",
            subtitle = "查看已缓存的专辑音频/歌词视频，删除或清空全部",
            onClick = onClick,
            leading = {
                Icon(
                    imageVector = Icons.Rounded.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            },
            trailing = {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }
}

@Composable
private fun DiagnosticsEntryCard(
    lineCount: Int,
    onClick: () -> Unit,
) {
    BiliGroup(contentPadding = PaddingValues(0.dp)) {
        BiliGroupItem(
            title = "诊断日志",
            subtitle = "查看 / 复制 / 导出运行日志，帮助定位播放、缓冲、导入问题",
            onClick = onClick,
            leading = {
                Icon(
                    imageVector = Icons.Rounded.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$lineCount 行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )
    }
}

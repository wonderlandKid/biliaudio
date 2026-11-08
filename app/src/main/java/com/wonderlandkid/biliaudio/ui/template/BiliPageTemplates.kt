package com.wonderlandkid.biliaudio.ui.template

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.wonderlandkid.biliaudio.ui.LocalMiniPlayerSnackbarInset
import com.wonderlandkid.biliaudio.ui.theme.Spacing

/**
 * 页面模板（docs/UI_ARCHITECTURE.md）：
 *
 * - `BiliDetailScaffold`：层级页面（有返回、无底部导航）的统一容器；
 * - `BiliTopBar`：层级页面统一顶栏（返回 + 标题 + actions）；
 * - T1 Tab 页仍由 AppNavHost 的 Scaffold + [com.wonderlandkid.biliaudio.ui.components.BiliPageHeader] 负责；
 * - 沉浸页（播放/歌词）不使用这些模板，自己管理 insets。
 *
 * 所有模板统一约定：
 * - 系统栏 inset 由 AppNavHost 外层 Scaffold 处理 → 内部 `contentWindowInsets = WindowInsets(0)`；
 * - 顶栏返回键统一在左侧、标题统一 titleLarge；不使用页面自己的 Row 手写顶栏；
 * - 页面内容自己接收 [PaddingValues]，不要再叠加系统栏 padding。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiliTopBar(
    onBack: () -> Unit,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        // 系统栏 inset 由外层 AppNavHost 的 Scaffold 统一处理
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

/**
 * 层级页面统一 Scaffold：返回顶栏 + Snackbar + 内容。
 *
 * 使用方式：
 * ```
 * BiliDetailScaffold(title = "编辑分割点", onBack = onBack, snackbarHostState = snackbar) { inner ->
 *     Column(Modifier.padding(inner)) { ... }
 * }
 * ```
 */
@Composable
fun BiliDetailScaffold(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            snackbarHostState?.let { state ->
                // 迷你播放条悬浮在页面底部之上；Snackbar 需要抬到它上方，
                // 否则会被毛玻璃遮住并在 Haze 采样里变成一块黑色。
                SnackbarHost(
                    hostState = state,
                    modifier = Modifier.padding(bottom = LocalMiniPlayerSnackbarInset.current),
                )
            }
        },
        topBar = {
            BiliTopBar(
                onBack = onBack,
                title = title,
                actions = actions,
            )
        },
    ) { innerPadding ->
        content(innerPadding)
    }
}

/** 层级页面顶栏内容区统一顶部间距（需要展示页内大标题时使用）。 */
val DetailContentTopPadding = Spacing.sm

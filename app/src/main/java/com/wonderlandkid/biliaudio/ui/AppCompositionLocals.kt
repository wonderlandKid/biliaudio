package com.wonderlandkid.biliaudio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wonderlandkid.biliaudio.BiliAudioApp
import com.wonderlandkid.biliaudio.di.AppContainer

/** 从任意 Composable 获取应用级依赖容器。 */
@Composable
fun rememberAppContainer(): AppContainer {
    val context = LocalContext.current
    return (context.applicationContext as BiliAudioApp).container
}

/**
 * 统一的 ViewModel 创建方式：直接注入 [AppContainer]。
 *
 * 用法：
 * ```
 * val vm: HomeViewModel = appViewModel { c -> HomeViewModel(c.albumRepository) }
 * ```
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    crossinline create: (AppContainer) -> VM,
): VM {
    val context = LocalContext.current
    return viewModel(
        factory = viewModelFactory {
            initializer { create((context.applicationContext as BiliAudioApp).container) }
        },
    )
}

/** 跟随设置的应用主题壳。 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val container = rememberAppContainer()
    val settings by container.settingsRepository.settings.collectAsState()
    val dark = settings.darkTheme ?: isSystemInDarkTheme()
    com.wonderlandkid.biliaudio.ui.theme.BiliAudioTheme(darkTheme = dark, content = content)
}

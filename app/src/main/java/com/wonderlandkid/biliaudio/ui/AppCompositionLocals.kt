package com.wonderlandkid.biliaudio.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wonderlandkid.biliaudio.BiliAudioApp
import com.wonderlandkid.biliaudio.di.AppContainer
import com.wonderlandkid.biliaudio.ui.theme.rememberBiliPressIndication

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

/** 全局按压反馈：所有可点击组件统一水波纹 + 按压底色；禁用组件不触发。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProvidePressIndication(content: @Composable () -> Unit) {
    val indication = rememberBiliPressIndication()
    CompositionLocalProvider(
        LocalIndication provides indication,
        LocalRippleConfiguration provides null,
        content = content,
    )
}

/** 跟随设置的应用主题壳。 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val container = rememberAppContainer()
    val settings by container.settingsRepository.settings.collectAsState()
    val dark = settings.darkTheme ?: isSystemInDarkTheme()
    val view = LocalView.current
    // 系统栏图标明暗必须跟随 App 自己的深色设置，而不是只跟随系统深色模式：
    // 深色主题下状态栏时钟/图标用浅色，浅色主题下用深色。
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    com.wonderlandkid.biliaudio.ui.theme.BiliAudioTheme(darkTheme = dark) {
        ProvidePressIndication(content = content)
    }
}

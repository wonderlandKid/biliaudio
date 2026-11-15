package com.wonderlandkid.biliaudio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.wonderlandkid.biliaudio.ui.AppTheme
import com.wonderlandkid.biliaudio.ui.navigation.AppNavHost
import com.wonderlandkid.biliaudio.util.Diag

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                NotificationPermissionRequest()
                AppNavHost()
            }
        }
    }

    // 维测：App 前后台切换（排查“回前台后状态不对/转场异常/音频焦点问题”）。
    override fun onStart() {
        super.onStart()
        Diag.log("App", "UI foreground")
    }

    override fun onStop() {
        Diag.log("App", "UI background")
        super.onStop()
    }
}

/** Android 13+ 通知栏播放控制需要 POST_NOTIFICATIONS 权限。 */
@androidx.compose.runtime.Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // 维测：拒绝后通知栏/锁屏不会有播放控制（排查“通知栏没有控制”时先看这条）。
        Diag.log("App", "notification permission granted=$granted")
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

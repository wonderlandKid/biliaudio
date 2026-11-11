package com.wonderlandkid.biliaudio.ui.login

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wonderlandkid.biliaudio.data.model.UserProfile
import com.wonderlandkid.biliaudio.data.repository.AuthState
import com.wonderlandkid.biliaudio.data.repository.QrLoginState
import com.wonderlandkid.biliaudio.data.repository.QrPhase
import com.wonderlandkid.biliaudio.ui.appViewModel
import com.wonderlandkid.biliaudio.ui.components.CoverImage
import com.wonderlandkid.biliaudio.ui.template.BiliDetailScaffold
import com.wonderlandkid.biliaudio.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * B 站扫码登录页。
 *
 * 流程：进入即调用 [AuthRepository.startQrLogin] → 用 zxing 把 qrState.qrUrl 画成二维码 →
 * 按 [QrPhase] 展示等待扫码 / 已扫码待确认 / 已过期（可刷新）/ 错误；
 * 登录成功后展示账号信息并回调 [onLoggedIn]。
 *
 * 注意：本机模拟器没有摄像头，页面只保证二维码渲染与状态 UI 正确；真实扫码由数据层轮询完成。
 * 返回时取消轮询（点返回立即取消，ViewModel 销毁时兜底再取消一次）。
 *
 * @param onBack 返回上一页
 * @param onLoggedIn 登录成功且用户点击「进入应用」后回调
 */
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LoginViewModel = appViewModel { container ->
        LoginViewModel(
            authRepository = container.authRepository,
            appScope = container.appScope,
        )
    }
    val qrState by viewModel.qrState.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val qrError by viewModel.qrError.collectAsStateWithLifecycle()

    val profile = (authState as? AuthState.LoggedIn)?.profile
    val isLoggedIn = profile != null || qrState.phase == QrPhase.SUCCESS

    // 系统返回：先取消轮询再交给导航层
    BackHandler {
        viewModel.cancelLogin()
        onBack()
    }

    BiliDetailScaffold(
        onBack = {
            viewModel.cancelLogin()
            onBack()
        },
        title = "扫码登录",
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            if (isLoggedIn) {
                LoginSuccessContent(
                    profile = profile,
                    onLoggedIn = onLoggedIn,
                )
            } else {
                LoginQrContent(
                    qrState = qrState,
                    fallbackError = qrError,
                    onRefresh = viewModel::refreshQrCode,
                )
            }
        }
    }
}


// ---------------------------------------------------------------------------
// 扫码内容
// ---------------------------------------------------------------------------

@Composable
private fun LoginQrContent(
    qrState: QrLoginState,
    fallbackError: String?,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = "使用 B 站 App 扫码登录",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "登录后可展示头像昵称，并使用账号可用音质播放",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xl))

        // 数据层抛异常时用本地错误兜底；否则按 qrState.phase 展示
        val errorMessage = fallbackError
            ?: if (qrState.phase == QrPhase.ERROR) {
                qrState.message ?: "登录二维码获取失败，请检查网络后重试"
            } else {
                null
            }
        QrCard(
            qrState = qrState,
            errorMessage = errorMessage,
            onRefresh = onRefresh,
        )

        Spacer(Modifier.height(Spacing.xl))
        LoginTipsCard()
        Spacer(Modifier.height(Spacing.xxl))
    }
}

/** 二维码卡片：白色底 + 二维码 + 状态行 / 过期遮罩 / 错误重试。 */
@Composable
private fun QrCard(
    qrState: QrLoginState,
    errorMessage: String?,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (errorMessage != null) {
                QrErrorContent(message = errorMessage, onRetry = onRefresh)
            } else {
                QrImageBox(
                    qrUrl = qrState.qrUrl,
                    expired = qrState.phase == QrPhase.EXPIRED,
                    onRefresh = onRefresh,
                )
                Spacer(Modifier.height(Spacing.lg))
                QrStatusRow(qrState)
                if (qrState.phase == QrPhase.EXPIRED) {
                    Spacer(Modifier.height(Spacing.md))
                    Button(
                        onClick = onRefresh,
                        shape = RoundedCornerShape(50),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text("刷新二维码")
                    }
                }
            }
        }
    }
}

/** 二维码位图区域（produceState 在后台线程用 zxing 生成，避免阻塞 UI）。 */
@Composable
private fun QrImageBox(
    qrUrl: String?,
    expired: Boolean,
    onRefresh: () -> Unit,
) {
    val bitmap: Bitmap? by produceState<Bitmap?>(initialValue = null, qrUrl) {
        value = if (qrUrl.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) { QrCodeGenerator.generate(qrUrl, sizePx = 720) }
        }
    }
    Surface(
        modifier = Modifier.size(252.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "B 站登录二维码",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // 二维码失效遮罩（点击遮罩也可刷新）
            if (expired) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = "二维码已失效",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                        )
                        TextButton(onClick = onRefresh) {
                            Text("点击刷新", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** 二维码下方状态行：等待扫码 / 已扫码待确认 / 加载中 / 已过期。 */
@Composable
private fun QrStatusRow(qrState: QrLoginState) {
    val (icon, text, color) = when (qrState.phase) {
        QrPhase.SCANNED -> Triple(
            Icons.Rounded.CheckCircle,
            "已扫码，请在手机上确认登录",
            MaterialTheme.colorScheme.primary,
        )

        QrPhase.EXPIRED -> Triple(
            Icons.Rounded.Refresh,
            "二维码已失效，请刷新后重新扫码",
            MaterialTheme.colorScheme.error,
        )

        QrPhase.WAITING -> Triple(
            Icons.Rounded.QrCodeScanner,
            "等待扫码",
            MaterialTheme.colorScheme.onSurface,
        )

        QrPhase.SUCCESS -> Triple(
            Icons.Rounded.CheckCircle,
            "登录成功",
            MaterialTheme.colorScheme.primary,
        )

        QrPhase.LOADING, QrPhase.IDLE -> Triple(
            null,
            "正在获取登录二维码…",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        QrPhase.ERROR -> Triple(
            Icons.Rounded.ErrorOutline,
            qrState.message ?: "登录二维码获取失败",
            MaterialTheme.colorScheme.error,
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
    // 扫码操作提示
    val hint = when (qrState.phase) {
        QrPhase.WAITING -> "打开 B 站手机客户端 → 我的 → 扫一扫"
        QrPhase.SCANNED -> "在手机上点击「确认登录」即可完成"
        QrPhase.EXPIRED -> "二维码有效期约 3 分钟"
        else -> null
    }
    if (hint != null) {
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 二维码获取失败。 */
@Composable
private fun QrErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "登录二维码获取失败",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.lg))
        Button(onClick = onRetry, shape = RoundedCornerShape(50)) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text("重试")
        }
    }
}

/** 扫码步骤与隐私说明。 */
@Composable
private fun LoginTipsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = "扫码步骤",
                style = MaterialTheme.typography.titleMedium,
            )
            TipLine(index = "1", text = "打开 B 站手机客户端，进入「我的」")
            TipLine(index = "2", text = "点击右上角「扫一扫」，扫描上方二维码")
            TipLine(index = "3", text = "在手机上点击「确认登录」即可完成")
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "登录 Cookie 仅保存在本机，用于访问 B 站接口；本应用不会上传账号信息。" +
                    "若二维码长时间未失效，可返回后重新进入本页刷新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TipLine(index: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// 登录成功
// ---------------------------------------------------------------------------

@Composable
private fun LoginSuccessContent(
    profile: UserProfile?,
    onLoggedIn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Spacing.xxxl))
        if (profile != null) {
            CoverImage(
                url = profile.avatarUrl,
                modifier = Modifier.size(96.dp),
                cornerRadius = 48.dp,
            )
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = profile.name,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "UID ${profile.mid}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = "登录成功，正在获取账号信息…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = "已登录",
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(Spacing.xxl))
        Button(
            onClick = onLoggedIn,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("进入应用")
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "登录信息已保存，可在设置中管理账号",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xxl))
    }
}

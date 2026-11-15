package com.wonderlandkid.biliaudio.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.wonderlandkid.biliaudio.BiliAudioApp
import com.wonderlandkid.biliaudio.MainActivity
import com.wonderlandkid.biliaudio.R
import com.wonderlandkid.biliaudio.util.Diag

/**
 * 后台播放服务（Media3 MediaSessionService）。
 *
 * - 复用 [PlaybackEngine] 的**同一个 ExoPlayer** 构建 MediaSession（不额外创建播放器）；
 * - 播放时由媒体通知管理器自动前台化，提供媒体通知 / 锁屏 / 蓝牙控制；
 * - 通知标题/艺术家来自 MediaItem 的 MediaMetadata，封面优先用引擎预取的 artworkData
 *   （B 站图床需要 Referer，所以用带默认头的 [DataSourceBitmapLoader] 作为回退）。
 *
 * 服务启动方式：播放引擎在开始播放时调用 [ensureStarted]（`ContextCompat.startForegroundService`），
 * 不阻塞 UI；离开 App 后由系统继续持有前台服务。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        private const val TAG = "PlaybackService"
        /** 维测日志 tag（后台播放链）。 */
        private const val DIAG_TAG = "Service"
        private const val NOTIFICATION_CHANNEL_ID = "biliaudio_playback"
        private const val NOTIFICATION_ID = 0x1010

        /**
         * 确保后台播放服务已启动（可重复调用，已启动时等价于一次 onStartCommand）。
         *
         * 由 [PlaybackEngine] 在 setMediaItems + play() 之后调用，此时播放器已进入播放/缓冲状态，
         * 服务创建 MediaSession 后 Media3 会立即发布前台通知，不会触发前台服务超时。
         */
        fun ensureStarted(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "启动播放服务失败", it) }
        }
    }

    private var mediaSession: MediaSession? = null
    private var engine: PlaybackEngine? = null
    private var artworkBitmapLoader: DataSourceBitmapLoader? = null

    override fun onCreate() {
        super.onCreate()
        val playbackEngine = obtainEngine()
        if (playbackEngine == null) {
            // 理论上不会发生：服务只由播放引擎拉起；兜底避免空指针崩溃
            Log.w(TAG, "播放引擎尚未初始化，播放服务退出")
            Diag.log(DIAG_TAG, "service onCreate FAILED: engine not initialized → stopSelf")
            stopSelf()
            return
        }
        engine = playbackEngine
        Diag.log(DIAG_TAG, "service onCreate engine=ok")

        // 通知渠道（中文名用 App 名）+ 固定通知 id
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.app_name)
                .setNotificationId(NOTIFICATION_ID)
                .build(),
        )
        // 播放器空闲时也展示通知：保证 startForegroundService 后能立刻前台化，避免超时崩溃
        setShowNotificationForIdlePlayer(MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)

        // 通知栏封面加载器：带 UA/Referer，artworkData（bytes）与 artworkUri 都支持
        val bitmapLoader = DataSourceBitmapLoader.Builder(this)
            .setDataSourceFactory(
                OkHttpDataSource.Factory(playbackEngine.httpClient)
                    .setDefaultRequestProperties(MediaHttp.baseHeaders()),
            )
            .build()
        artworkBitmapLoader = bitmapLoader

        mediaSession = MediaSession.Builder(this, playbackEngine.exoPlayer)
            // 点通知回到 App
            .setSessionActivity(buildSessionActivity())
            .setBitmapLoader(bitmapLoader)
            .build()
        // 本 App 的 UI 不创建 MediaController（只通过单例引擎控制 ExoPlayer），
        // 所以这里主动把 session 注册给服务：注册后 Media3 才会根据播放状态生成/更新
        // 媒体通知并调用 startForeground（否则 startForegroundService 会超时崩溃）。
        mediaSession?.let { addSession(it) }
        Diag.log(DIAG_TAG, "service session created (notification/lockscreen ready)")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉 App：正在播放则继续后台播放，否则关闭服务
        val player = mediaSession?.player
        val keepAlive = player != null && player.playWhenReady && player.mediaItemCount > 0
        Diag.log(
            DIAG_TAG,
            "taskRemoved playWhenReady=${player?.playWhenReady} " +
                "items=${player?.mediaItemCount} → ${if (keepAlive) "keep" else "stopSelf"}",
        )
        if (!keepAlive) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        Diag.log(DIAG_TAG, "service onDestroy")
        mediaSession?.player?.pause()
        mediaSession?.let { session ->
            removeSession(session)
            session.release()
        }
        mediaSession = null
        artworkBitmapLoader = null
        engine = null
        super.onDestroy()
    }

    /** 从进程内单例或 AppContainer 取播放引擎（服务与 UI 共用同一个 ExoPlayer）。 */
    private fun obtainEngine(): PlaybackEngine? {
        PlaybackEngine.peek()?.let { return it }
        return runCatching {
            val app = application as BiliAudioApp
            app.container.playbackController as? PlaybackEngine
        }.getOrNull()
    }

    /** 媒体通知点击后回到应用主界面。 */
    private fun buildSessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

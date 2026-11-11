package com.wonderlandkid.biliaudio.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI 架构一致性守卫（v0.24.0）。
 *
 * 这些测试不是测像素，而是防止未来页面重新出现“同类组件各写一套”的回归：
 * - 详情页必须使用 BiliDetailScaffold；
 * - 禁止空点击；
 * - 发现页区块必须走 BiliSection/BiliAlbumRow；
 * - 设置卡片首行必须走 BiliGroupHeader；
 * - 新页面底部迷你条留白必须走 BiliLayout token。
 */
class UiArchitectureConsistencyTest {

    private val uiRoot: File = listOf(
        File("src/main/java/com/wonderlandkid/biliaudio/ui"),
        File("app/src/main/java/com/wonderlandkid/biliaudio/ui"),
    ).firstOrNull { it.isDirectory }
        ?: error("找不到 ui 源码目录，user.dir=${System.getProperty("user.dir")}")

    private fun source(relativePath: String): String =
        File(uiRoot, relativePath).readText()

    private fun allKotlin(): List<File> =
        uiRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `no empty onClick handlers`() {
        val offenders = allKotlin().filter { file ->
            val text = file.readText()
            text.contains("onClick = {}") || text.contains("onClick={}")
        }.map { it.relativeTo(uiRoot).path }
        assertTrue("禁止 onClick={{}} 空实现：$offenders", offenders.isEmpty())
    }

    @Test
    fun `detail pages use BiliDetailScaffold`() {
        val detailPages = listOf(
            "album/AlbumDetailScreen.kt",
            "album/SplitEditorScreen.kt",
            "album/MultiPageTrackEditorScreen.kt",
            "library/ArtistDetailScreen.kt",
            "imports/ImportScreen.kt",
            "login/LoginScreen.kt",
            "settings/DiagnosticsScreen.kt",
            "settings/CacheManagementScreen.kt",
            "playlist/PlaylistsScreen.kt",
            "playlist/PlaylistDetailScreen.kt",
            "stats/StatsScreen.kt",
            "home/RecentAlbumsScreen.kt",
        )
        detailPages.forEach { page ->
            assertTrue("$page 必须使用 BiliDetailScaffold", source(page).contains("BiliDetailScaffold("))
        }
    }

    @Test
    fun `home sections use shared templates`() {
        // v0.31.1：回退发现页大胆版式，恢复严格 Material 3 标准页头 + 区块/行模板
        val home = source("home/HomeScreen.kt")
        assertTrue("发现页页头必须使用 BiliPageHeader", home.contains("BiliPageHeader("))
        assertTrue("发现页区块必须使用 BiliSection", home.contains("BiliSection("))
        assertTrue("发现页专辑行必须使用 BiliAlbumRow", home.contains("BiliAlbumRow("))
        assertFalse("发现页不能私有实现 HomeSectionCard", home.contains("private fun HomeSectionCard"))
        assertFalse("发现页不能私有实现 HomeAlbumRow", home.contains("private fun HomeAlbumRow"))
    }

    @Test
    fun `page transitions are defined only in the navigation layer`() {
        // 页面级转场统一由 AppNavHost 决定，页面不得各自发明 enter/exit 转场
        val offenders = allKotlin().filter { file ->
            val relative = file.relativeTo(uiRoot).path
            if (relative.startsWith("navigation/")) return@filter false
            val text = file.readText()
            text.contains("enterTransition") || text.contains("popEnterTransition")
        }.map { it.relativeTo(uiRoot).path }
        assertTrue("页面转场只能在 navigation/AppNavHost.kt 统一定义：$offenders", offenders.isEmpty())
    }

    @Test
    fun `no hardcoded colors outside theme`() {
        // 颜色必须走 MaterialTheme / 主题资源，页面/组件不得硬编码 0xAARRGGBB
        val hexColor = Regex("""Color\(0x""")
        val offenders = allKotlin().filter { file ->
            val relative = file.relativeTo(uiRoot).path
            if (relative.startsWith("theme/")) return@filter false
            hexColor.containsMatchIn(file.readText())
        }.map { it.relativeTo(uiRoot).path }
        assertTrue("页面/组件不得硬编码色值，必须使用 MaterialTheme 语义色：$offenders", offenders.isEmpty())
    }

    @Test
    fun `settings cards use shared header`() {
        val settings = source("settings/SettingsScreen.kt")
        assertTrue("设置卡片首行必须使用 BiliGroupHeader", settings.contains("BiliGroupHeader("))
        assertFalse("设置页不能再私有实现 SettingsHeaderRow", settings.contains("private fun SettingsHeaderRow"))
    }

    @Test
    fun `new pages use mini player bottom token`() {
        val pages = listOf(
            "home/RecentAlbumsScreen.kt",
            "playlist/PlaylistDetailScreen.kt",
            "stats/StatsScreen.kt",
        )
        pages.forEach { page ->
            val text = source(page)
            assertFalse("$page 不应硬编码 96.dp 底部留白", text.contains("bottom = 96.dp"))
            assertTrue("$page 应使用 BiliLayout.contentBottomWithMiniPlayer", text.contains("contentBottomWithMiniPlayer"))
        }
    }
}

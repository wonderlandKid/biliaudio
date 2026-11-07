package com.wonderlandkid.biliaudio.ui.navigation

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val IMPORT = "import"
    const val LOGIN = "login"
    const val PLAYER = "player"
    const val VIDEO = "video"
    const val SEARCH = "search"

    const val ALBUM = "album/{albumId}"
    fun album(albumId: Long) = "album/$albumId"

    const val SPLIT_EDITOR = "split/{albumId}"
    fun splitEditor(albumId: Long) = "split/$albumId"

    const val ARG_ALBUM_ID = "albumId"
}

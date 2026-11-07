package com.wonderlandkid.biliaudio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 应用数据库（单例）。
 *
 * 两张表：
 * - `albums`：专辑元信息 + 最近播放/播放次数；
 * - `tracks`：专辑切分出来的曲目（startMs/endMs），外键级联删除。
 *
 * 装配：`AppDatabase.getInstance(context)`。
 */
@Database(
    entities = [AlbumEntity::class, TrackEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun albumDao(): AlbumDao

    abstract fun trackDao(): TrackDao

    companion object {
        private const val DB_NAME = "biliaudio.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).build().also { instance = it }
            }
    }
}

package com.wonderlandkid.biliaudio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun albumDao(): AlbumDao

    abstract fun trackDao(): TrackDao

    companion object {
        private const val DB_NAME = "biliaudio.db"

        /** v1 → v2：新增用户自定义封面本地路径列。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE albums ADD COLUMN coverLocalPath TEXT")
            }
        }

        /** v2 → v3：多来源/多 P 稳定键（source+bvid+page）、tracks.uid。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // albums: 增加 source，唯一索引从 bvid 换成 (source,bvid,page)
                db.execSQL("ALTER TABLE albums ADD COLUMN source TEXT NOT NULL DEFAULT 'bilibili'")
                db.execSQL("DROP INDEX IF EXISTS index_albums_bvid")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_albums_source_bvid_page " +
                        "ON albums(source, bvid, page)",
                )
                // tracks: 增加 uid 并回填随机 UUID
                db.execSQL("ALTER TABLE tracks ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE tracks SET uid = lower(hex(randomblob(16))) WHERE uid = ''")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_tracks_uid ON tracks(uid)",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}

package com.shiping.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.shiping.app.BuildConfig
import com.shiping.app.data.model.ApiSourceEntity
import com.shiping.app.data.model.DownloadEntity
import com.shiping.app.data.model.FavoriteEntity
import com.shiping.app.data.model.ParseSourceEntity
import com.shiping.app.data.model.PlayHistoryEntity
import com.shiping.app.data.model.TvSourceEntity

@Database(
    entities = [ApiSourceEntity::class, ParseSourceEntity::class, DownloadEntity::class, PlayHistoryEntity::class, TvSourceEntity::class, FavoriteEntity::class],
    version = 13,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun apiSourceDao(): ApiSourceDao
    abstract fun parseSourceDao(): ParseSourceDao
    abstract fun downloadDao(): DownloadDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun tvSourceDao(): TvSourceDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v6→v7：播放历史改为 (vodId, sourceUrl) 复合主键，旧记录来源置空串保留 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `play_history_new` (" +
                        "`vodId` INTEGER NOT NULL, " +
                        "`sourceUrl` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`vodPic` TEXT NOT NULL, " +
                        "`lastEpisodeName` TEXT NOT NULL, " +
                        "`lastPositionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`watchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`vodId`, `sourceUrl`))",
                )
                db.execSQL(
                    "INSERT INTO `play_history_new` " +
                        "(`vodId`, `sourceUrl`, `title`, `vodPic`, `lastEpisodeName`, `lastPositionMs`, `durationMs`, `watchedAt`) " +
                        "SELECT `vodId`, '', `title`, `vodPic`, `lastEpisodeName`, `lastPositionMs`, `durationMs`, `watchedAt` FROM `play_history`",
                )
                db.execSQL("DROP TABLE `play_history`")
                db.execSQL("ALTER TABLE `play_history_new` RENAME TO `play_history`")
            }
        }

        /** v7→v8：新增收藏表 */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorites` (" +
                        "`vodId` INTEGER NOT NULL, " +
                        "`sourceUrl` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`vodPic` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`vodId`, `sourceUrl`))",
                )
            }
        }

        /** v8→v9：api_sources 新增爬虫源字段（siteType/jarUrl/spiderKey），支持 TVBox 爬虫源落库 */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `api_sources` ADD COLUMN `siteType` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `api_sources` ADD COLUMN `jarUrl` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `api_sources` ADD COLUMN `spiderKey` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v9→v10：api_sources 新增 ext 字段，保存爬虫站点的 extend 配置 */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `api_sources` ADD COLUMN `ext` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v10→v11：downloads 新增 headersJson 字段，导出时注入 UA/Referer 防盗链头 */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `headersJson` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v11→v12：api_sources 新增 locked/playRecord/searchable/userAgent，与桌面端字段对齐 */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `api_sources` ADD COLUMN `locked` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `api_sources` ADD COLUMN `playRecord` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `api_sources` ADD COLUMN `searchable` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `api_sources` ADD COLUMN `userAgent` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v12→v13：downloads 新增 completedSegments，支持 HLS 分片级断点续传 */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `completedSegments` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            val builder = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "shiping.db",
            )
            // 仅在 Debug 构建中允许破坏性迁移，避免线上用户数据丢失
            if (BuildConfig.DEBUG) {
                builder.fallbackToDestructiveMigration()
            }
            builder.addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
            return builder.build()
        }
    }
}

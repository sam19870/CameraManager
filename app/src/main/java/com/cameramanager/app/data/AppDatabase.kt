package com.cameramanager.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cameramanager.app.data.model.AlarmEvent
import com.cameramanager.app.data.model.DetectionRule
import com.cameramanager.app.data.model.Device
import com.cameramanager.app.data.model.Recording
import com.cameramanager.app.data.model.Tunnel

@Database(
    entities = [Device::class, DetectionRule::class, AlarmEvent::class, Recording::class, Tunnel::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun detectionRuleDao(): DetectionRuleDao
    abstract fun alarmDao(): AlarmDao
    abstract fun recordingDao(): RecordingDao
    abstract fun tunnelDao(): TunnelDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2：
         *  - 新增 tunnels 表（内网穿透通道）。
         *  - devices 表追加内网穿透相关字段：lanSsid / tunnelId / publicHost /
         *    publicPort / publicOnvifPort。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tunnels` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `name` TEXT NOT NULL,
                        `host` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `onvifPort` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `remark` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("ALTER TABLE devices ADD COLUMN lanSsid TEXT")
                db.execSQL("ALTER TABLE devices ADD COLUMN tunnelId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE devices ADD COLUMN publicHost TEXT")
                db.execSQL("ALTER TABLE devices ADD COLUMN publicPort INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE devices ADD COLUMN publicOnvifPort INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "camera_manager.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}

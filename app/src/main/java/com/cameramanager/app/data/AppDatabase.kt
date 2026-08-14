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
    version = 4,
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
         *
         * v2 -> v3：
         *  - tunnels 表按 SakuraFrp 官方文档（公网入口模型）重写：
         *    新增 token / authUser / authPass / lanCidr / lanGateway 字段。
         *    host/port/onvifPort 三个字段保留为必填核心列（原来有）。
         *
         * v3 -> v4：【核心BUG修复】
         *  - devices 表新增 rtspPort 列，用于区分「管理端口 port」和「视频 RTSP 端口 rtspPort」：
         *     port = HTTP/HTTPS/ONVIF/Tapo 握手的管理端口（默认80）
         *     rtspPort = RTSP 视频流端口（默认554）
         *    之前两者混用导致 Tapo 握手错误地请求 RTSP 554 端口（不是 HTTP 80），
         *    用户反馈"操作提示连接443端口、无视频画面"的根因。
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 只在 tunnels 已经存在的情况下 ALTER（老数据不会因此挂掉）
                runCatching {
                    db.execSQL("ALTER TABLE tunnels ADD COLUMN token TEXT")
                    db.execSQL("ALTER TABLE tunnels ADD COLUMN authUser TEXT")
                    db.execSQL("ALTER TABLE tunnels ADD COLUMN authPass TEXT")
                    db.execSQL("ALTER TABLE tunnels ADD COLUMN lanCidr TEXT")
                    db.execSQL("ALTER TABLE tunnels ADD COLUMN lanGateway TEXT")
                }
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                runCatching {
                    // 新增 rtspPort 列：默认值 554（绝大多数摄像头的 RTSP 端口）。
                    // 旧数据：如果之前 port=554/34567/37777/8554 等典型 RTSP 端口，
                    // 保持原值，同时把 port 回退到 80（管理端口）。
                    db.execSQL("ALTER TABLE devices ADD COLUMN rtspPort INTEGER NOT NULL DEFAULT 554")
                    // 典型 RTSP 端口集合：如果旧 port 在这个集合内，说明之前把 RTSP 口填到管理口了，
                    // 迁移时把 port 置成 80（管理口），rtspPort 保留原 port 值。
                    val rtspPorts = listOf(554, 8554, 10554, 34567, 37777, 7447, 8557, 9554, 1554, 5554)
                    rtspPorts.forEach { p ->
                        runCatching {
                            db.execSQL(
                                "UPDATE devices SET rtspPort=$p, port=80 WHERE port=$p AND (rtspPort IS NULL OR rtspPort=554)"
                            )
                        }
                    }
                }
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "camera_manager.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}

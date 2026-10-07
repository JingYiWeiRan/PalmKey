package com.jywr.pcbuapk.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jywr.pcbuapk.data.database.AppDatabase
import com.jywr.pcbuapk.data.preferences.UserPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 依赖注入模块 - 提供数据库和偏好设置实例
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    /**
     * 数据库迁移：1 -> 2
     * 添加 passwordKey 字段
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE paired_devices ADD COLUMN passwordKey TEXT")
        }
    }

    /**
     * 数据库迁移：2 -> 3
     * 为蓝牙设备匹配所依赖的两列补上索引（原先每次蓝牙唤醒都是两次全表扫描）。
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_paired_devices_bluetoothAddress " +
                        "ON paired_devices(bluetoothAddress)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_paired_devices_deviceName " +
                        "ON paired_devices(deviceName)"
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
    
    @Provides
    @Singleton
    fun providePairedDeviceDao(database: AppDatabase) = database.pairedDeviceDao()
    
    @Provides
    @Singleton
    fun provideUserPreferences(@ApplicationContext context: Context): UserPreferences {
        return UserPreferences(context)
    }
}

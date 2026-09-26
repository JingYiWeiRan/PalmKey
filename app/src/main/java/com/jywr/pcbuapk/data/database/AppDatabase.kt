package com.jywr.pcbuapk.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.jywr.pcbuapk.data.dao.PairedDeviceDao
import com.jywr.pcbuapk.data.entity.PairedDeviceEntity

/**
 * 应用数据库
 */
@Database(
    entities = [PairedDeviceEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    
    abstract fun pairedDeviceDao(): PairedDeviceDao
    
    companion object {
        const val DATABASE_NAME = "pcbu_database"
    }
}

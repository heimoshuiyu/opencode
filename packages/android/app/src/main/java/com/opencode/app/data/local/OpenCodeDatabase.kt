package com.opencode.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ServerConfigEntity::class, OpenedDirectoryEntity::class], version = 2, exportSchema = false)
abstract class OpenCodeDatabase : RoomDatabase() {
    abstract fun serverConfigDao(): ServerConfigDao
    abstract fun openedDirectoryDao(): OpenedDirectoryDao
}

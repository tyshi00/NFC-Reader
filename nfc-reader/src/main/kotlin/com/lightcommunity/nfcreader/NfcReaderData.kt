package com.lightcommunity.nfcreader

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

// ── Entities ─────────────────────────────────────────────────────────────

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serialNumber: String,
    val uri: String?,
    val text: String?,
    val textLanguage: String?,
    val binaryRecordCount: Int,
    val totalRecordCount: Int,
    val timestampMs: Long,
)

/** Generic key/value store for app-wide settings. */
@Entity(tableName = "preferences")
data class PreferenceEntity(
    @PrimaryKey val key: String,
    val value: String,
)

// ── DAOs ─────────────────────────────────────────────────────────────────

@Dao
interface ScanDao {
    @Query("SELECT * FROM scans ORDER BY timestampMs DESC")
    suspend fun getAll(): List<ScanEntity>

    @Query("SELECT * FROM scans WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ScanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ScanEntity): Long

    @Query("DELETE FROM scans WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM scans")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM scans")
    suspend fun count(): Int
}

@Dao
interface PreferenceDao {
    @Query("SELECT * FROM preferences WHERE key = :key LIMIT 1")
    suspend fun get(key: String): PreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(entity: PreferenceEntity)

    @Query("DELETE FROM preferences WHERE key = :key")
    suspend fun delete(key: String)
}

// ── Database ─────────────────────────────────────────────────────────────

@Database(
    entities = [ScanEntity::class, PreferenceEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class NfcReaderDatabase : RoomDatabase() {
    abstract fun scanDao(): ScanDao
    abstract fun preferenceDao(): PreferenceDao
}

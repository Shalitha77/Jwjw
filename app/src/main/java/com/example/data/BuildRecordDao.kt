package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BuildRecordDao {
    @Query("SELECT * FROM build_records ORDER BY createdAtMillis DESC")
    fun observeAllBuilds(): Flow<List<BuildRecordEntity>>

    @Query("SELECT * FROM build_records WHERE id = :id LIMIT 1")
    suspend fun getBuildById(id: Long): BuildRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBuild(record: BuildRecordEntity): Long

    @Query("DELETE FROM build_records WHERE id = :id")
    suspend fun deleteBuildById(id: Long)

    @Query("DELETE FROM build_records")
    suspend fun deleteAllBuilds()
}

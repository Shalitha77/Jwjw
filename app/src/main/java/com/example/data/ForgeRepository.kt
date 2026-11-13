package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class ForgeRepository(private val dao: BuildRecordDao) {
    val allBuilds: Flow<List<BuildRecordEntity>> = dao.observeAllBuilds()

    suspend fun insertBuild(record: BuildRecordEntity): Long = dao.insertBuild(record)

    suspend fun getBuildById(id: Long): BuildRecordEntity? = dao.getBuildById(id)

    suspend fun deleteBuild(record: BuildRecordEntity) {
        withContext(Dispatchers.IO) {
            runCatching { File(record.apkFilePath).delete() }
        }
        dao.deleteBuildById(record.id)
    }

    suspend fun clearAllBuilds() {
        dao.deleteAllBuilds()
    }
}

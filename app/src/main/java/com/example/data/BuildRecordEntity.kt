package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "build_records")
data class BuildRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectName: String,
    val packageName: String,
    val appLabel: String,
    val versionName: String,
    val versionCode: Int,
    val buildVariant: String,
    val minSdk: Int,
    val targetSdk: Int,
    val apkFilePath: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val sha256Fingerprint: String,
    val certSha256: String,
    val entryCount: Int,
    val hasBinaryManifest: Boolean,
    val hasDex: Boolean,
    val hasResourcesArsc: Boolean,
    val durationMs: Long,
    val status: String,
    val buildLog: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

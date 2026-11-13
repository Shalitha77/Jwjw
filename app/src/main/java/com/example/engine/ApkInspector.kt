package com.example.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ApkInspector {

    suspend fun inspectFile(apkFile: File): Result<ApkInspectionResult> = withContext(Dispatchers.IO) {
        runCatching {
            FileInputStream(apkFile).use { fis ->
                inspectStream(apkFile.name, apkFile.length(), fis)
            }
        }
    }

    suspend fun inspectUri(context: Context, uri: Uri): Result<ApkInspectionResult> = withContext(Dispatchers.IO) {
        runCatching {
            var name = "external.apk"
            var size = 0L
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0) ?: name
                    size = c.getLong(1)
                }
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                inspectStream(name, size, stream)
            } ?: throw IllegalStateException("Cannot open selected APK file")
        }
    }

    private fun inspectStream(
        fileName: String,
        knownSize: Long,
        rawStream: InputStream
    ): ApkInspectionResult {
        val bytes = rawStream.readBytes()
        val sha256 = ApkSignerAndPackager.sha256ColonHex(bytes)

        val entries = mutableListOf<ApkEntryInfo>()
        var hasBinaryManifest = false
        var hasResourcesArsc = false
        var manifestByteSize = 0L
        val dexFiles = mutableListOf<String>()
        val signatureFiles = mutableListOf<String>()

        ZipInputStream(BufferedInputStream(bytes.inputStream())).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                val entryBytes = readLimitedBytes(zis)
                val uncompressedLen = if (entry.size >= 0) entry.size else entryBytes.size.toLong()
                val compressedLen = if (entry.compressedSize >= 0) entry.compressedSize else uncompressedLen

                if (entryName == "AndroidManifest.xml") {
                    manifestByteSize = uncompressedLen
                    // Check RES_XML_TYPE little-endian magic 0x0003
                    if (entryBytes.size >= 4 && entryBytes[0] == 0x03.toByte() && entryBytes[1] == 0x00.toByte()) {
                        hasBinaryManifest = true
                    }
                } else if (entryName == "resources.arsc") {
                    if (entryBytes.size >= 4 && entryBytes[0] == 0x02.toByte() && entryBytes[1] == 0x00.toByte()) {
                        hasResourcesArsc = true
                    }
                } else if (entryName.startsWith("classes") && entryName.endsWith(".dex")) {
                    if (entryBytes.size >= 4 && entryBytes[0] == 'd'.code.toByte() &&
                        entryBytes[1] == 'e'.code.toByte() && entryBytes[2] == 'x'.code.toByte()
                    ) {
                        dexFiles.add(entryName)
                    }
                } else if (entryName.startsWith("META-INF/") &&
                    (entryName.endsWith(".RSA") || entryName.endsWith(".DSA") ||
                        entryName.endsWith(".EC") || entryName.endsWith(".SF"))
                ) {
                    signatureFiles.add(entryName)
                }

                entries.add(
                    ApkEntryInfo(
                        name = entryName,
                        compressedSize = compressedLen,
                        uncompressedSize = uncompressedLen,
                        isStoredUncompressed = entry.method == ZipEntry.STORED,
                        crcHex = "%08X".format(entry.crc and 0xFFFFFFFFL)
                    )
                )
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val hasV1Sig = signatureFiles.any { it.endsWith(".SF") } &&
            signatureFiles.any { it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC") }

        return ApkInspectionResult(
            fileName = fileName,
            totalSizeBytes = if (knownSize > 0) knownSize else bytes.size.toLong(),
            sha256 = sha256,
            entryCount = entries.size,
            hasBinaryManifest = hasBinaryManifest,
            hasResourcesArsc = hasResourcesArsc,
            dexFiles = dexFiles,
            hasV1Signature = hasV1Sig,
            signatureFiles = signatureFiles,
            manifestByteSize = manifestByteSize,
            entries = entries
        )
    }

    private fun readLimitedBytes(zis: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n: Int
        while (zis.read(buf).also { n = it } != -1) {
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

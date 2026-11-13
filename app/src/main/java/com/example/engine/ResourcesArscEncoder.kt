package com.example.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure Kotlin Android Resource Table (resources.arsc) compiler producing
 * a valid RES_TABLE_TYPE (0x0002) containing string/app_name (0x7f010000).
 */
object ResourcesArscEncoder {

    private const val RES_TABLE_TYPE: Short = 0x0002
    private const val RES_TABLE_PACKAGE_TYPE: Short = 0x0200
    private const val RES_TABLE_TYPE_TYPE: Short = 0x0201
    private const val RES_TABLE_TYPE_SPEC_TYPE: Short = 0x0202

    private const val TYPE_STRING: Byte = 0x03

    fun encode(packageName: String, appLabel: String): ByteArray {
        val globalPool = BinaryXmlEncoder.buildUtf16StringPool(listOf(appLabel))
        val typePool = BinaryXmlEncoder.buildUtf16StringPool(listOf("string"))
        val keyPool = BinaryXmlEncoder.buildUtf16StringPool(listOf("app_name"))

        // RES_TABLE_TYPE_SPEC_TYPE (0x0202): 16-byte header + 4-byte flags
        val typeSpecChunk = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(RES_TABLE_TYPE_SPEC_TYPE)
            putShort(16)
            putInt(20)
            put(1) // type id = 1 ("string")
            put(0) // res0
            putShort(0) // res1
            putInt(1) // entryCount
            putInt(0) // config flags for app_name
        }.array()

        // RES_TABLE_TYPE_TYPE (0x0201): 20-byte header + 64-byte ResTable_config + 4-byte offset + 16-byte entry
        val resTableConfigSize = 64
        val headerSize = 20 + resTableConfigSize // 84
        val entriesStart = headerSize + 4 // 88
        val typeChunkSize = entriesStart + 16 // 104
        val typeChunk = ByteBuffer.allocate(typeChunkSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(RES_TABLE_TYPE_TYPE)
            putShort(headerSize.toShort())
            putInt(typeChunkSize)
            put(1) // type id = 1
            put(0) // res0
            putShort(0) // res1
            putInt(1) // entryCount
            putInt(entriesStart)
            // ResTable_config (64 bytes of default config = 0s, first uint32 = size 64)
            putInt(resTableConfigSize)
            repeat(resTableConfigSize - 4) { put(0) }
            // entryOffsets[0] = 0
            putInt(0)
            // ResTable_entry (8 bytes)
            putShort(8) // size
            putShort(0) // flags
            putInt(0) // key StringPool index ("app_name")
            // Res_value (8 bytes)
            putShort(8) // size
            put(0) // res0
            put(TYPE_STRING)
            putInt(0) // index in global string pool (appLabel)
        }.array()

        // RES_TABLE_PACKAGE_TYPE (0x0200): 288-byte header
        val pkgHeaderSize = 288
        val typeStringsOffset = pkgHeaderSize
        val keyStringsOffset = typeStringsOffset + typePool.size
        val pkgChunkSize = pkgHeaderSize + typePool.size + keyPool.size + typeSpecChunk.size + typeChunk.size

        val pkgChunk = ByteBuffer.allocate(pkgChunkSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(RES_TABLE_PACKAGE_TYPE)
            putShort(pkgHeaderSize.toShort())
            putInt(pkgChunkSize)
            putInt(0x7f) // package id
            // 128 UTF-16LE chars (256 bytes)
            val pkgChars = packageName.take(127).toCharArray()
            for (i in 0 until 128) {
                val ch = if (i < pkgChars.size) pkgChars[i].code else 0
                putShort(ch.toShort())
            }
            putInt(typeStringsOffset)
            putInt(1) // lastPublicType
            putInt(keyStringsOffset)
            putInt(1) // lastPublicKey
            putInt(0) // typeIdOffset
            put(typePool)
            put(keyPool)
            put(typeSpecChunk)
            put(typeChunk)
        }.array()

        val tableSize = 12 + globalPool.size + pkgChunk.size
        return ByteBuffer.allocate(tableSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(RES_TABLE_TYPE)
            putShort(12)
            putInt(tableSize)
            putInt(1) // packageCount
            put(globalPool)
            put(pkgChunk)
        }.array()
    }
}

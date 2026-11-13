package com.example.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure Kotlin Android Binary XML (AXML) encoder producing spec-compliant
 * compiled AndroidManifest.xml (RES_XML_TYPE = 0x0003) payloads.
 */
object BinaryXmlEncoder {

    private const val RES_STRING_POOL_TYPE: Short = 0x0001
    private const val RES_XML_TYPE: Short = 0x0003
    private const val RES_XML_START_NAMESPACE_TYPE: Short = 0x0100
    private const val RES_XML_END_NAMESPACE_TYPE: Short = 0x0101
    private const val RES_XML_START_ELEMENT_TYPE: Short = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE: Short = 0x0103
    private const val RES_XML_RESOURCE_MAP_TYPE: Short = 0x0180

    private const val TYPE_REFERENCE: Byte = 0x01
    private const val TYPE_STRING: Byte = 0x03
    private const val TYPE_INT_DEC: Byte = 0x10
    private const val TYPE_INT_BOOLEAN: Byte = 0x12

    private const val ANDROID_NS_PREFIX = "android"
    private const val ANDROID_NS_URI = "http://schemas.android.com/apk/res/android"

    // Ordered android attribute names mapped 1:1 to their android.R.attr.* resource IDs
    private val androidAttrResourceMap = listOf(
        "versionCode" to 0x0101021b,
        "versionName" to 0x0101021c,
        "minSdkVersion" to 0x0101020c,
        "targetSdkVersion" to 0x01010270,
        "name" to 0x01010003,
        "label" to 0x01010001,
        "exported" to 0x01010010,
        "allowBackup" to 0x01010280,
        "supportsRtl" to 0x010103af
    )

    private data class AxmlAttr(
        val ns: String?,
        val name: String,
        val dataType: Byte,
        val intData: Int = 0,
        val stringValue: String? = null
    )

    fun encodeManifest(
        packageName: String,
        appLabelResId: Int = 0x7f010000,
        versionCode: Int,
        versionName: String,
        minSdk: Int,
        targetSdk: Int,
        launcherActivity: String,
        permissions: List<String>
    ): ByteArray {
        val stringTable = LinkedHashMap<String, Int>()
        // 1. Reserve first N indices for Android attribute names matching resource map
        androidAttrResourceMap.forEach { (attrName, _) ->
            stringTable.getOrPut(attrName) { stringTable.size }
        }
        fun intern(value: String): Int = stringTable.getOrPut(value) { stringTable.size }

        intern(ANDROID_NS_PREFIX)
        intern(ANDROID_NS_URI)

        val chunks = ByteArrayOutputStream()

        fun writeStartNamespace() {
            val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(RES_XML_START_NAMESPACE_TYPE)
            buf.putShort(16)
            buf.putInt(24)
            buf.putInt(1)
            buf.putInt(-1)
            buf.putInt(intern(ANDROID_NS_PREFIX))
            buf.putInt(intern(ANDROID_NS_URI))
            chunks.write(buf.array())
        }

        fun writeEndNamespace() {
            val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(RES_XML_END_NAMESPACE_TYPE)
            buf.putShort(16)
            buf.putInt(24)
            buf.putInt(1)
            buf.putInt(-1)
            buf.putInt(intern(ANDROID_NS_PREFIX))
            buf.putInt(intern(ANDROID_NS_URI))
            chunks.write(buf.array())
        }

        fun writeStartElement(tag: String, line: Int, attrs: List<AxmlAttr>) {
            val tagIdx = intern(tag)
            val nsIdxList = attrs.map { attr ->
                if (attr.ns != null) intern(attr.ns) else -1
            }
            val nameIdxList = attrs.map { intern(it.name) }
            val valueIdxList = attrs.map { attr ->
                if (attr.stringValue != null) intern(attr.stringValue) else -1
            }

            val totalSize = 36 + attrs.size * 20
            val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(RES_XML_START_ELEMENT_TYPE)
            buf.putShort(16)
            buf.putInt(totalSize)
            buf.putInt(line)
            buf.putInt(-1) // comment
            buf.putInt(-1) // ns
            buf.putInt(tagIdx)
            buf.putShort(20) // attributeStart
            buf.putShort(20) // attributeSize
            buf.putShort(attrs.size.toShort())
            buf.putShort(0) // idIndex
            buf.putShort(0) // classIndex
            buf.putShort(0) // styleIndex

            attrs.forEachIndexed { index, attr ->
                val rawValIdx = if (attr.dataType == TYPE_STRING) valueIdxList[index] else -1
                val typedData = if (attr.dataType == TYPE_STRING) valueIdxList[index] else attr.intData
                buf.putInt(nsIdxList[index])
                buf.putInt(nameIdxList[index])
                buf.putInt(rawValIdx)
                buf.putShort(8) // TypedValue size
                buf.put(0) // res0
                buf.put(attr.dataType)
                buf.putInt(typedData)
            }
            chunks.write(buf.array())
        }

        fun writeEndElement(tag: String, line: Int) {
            val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(RES_XML_END_ELEMENT_TYPE)
            buf.putShort(16)
            buf.putInt(24)
            buf.putInt(line)
            buf.putInt(-1)
            buf.putInt(-1)
            buf.putInt(intern(tag))
            chunks.write(buf.array())
        }

        val normalizedActivity = when {
            launcherActivity.startsWith(packageName) -> launcherActivity
            launcherActivity.startsWith(".") -> "$packageName$launcherActivity"
            !launcherActivity.contains(".") -> "$packageName.$launcherActivity"
            else -> launcherActivity
        }

        var line = 2
        writeStartNamespace()

        // <manifest>
        writeStartElement(
            "manifest",
            line++,
            listOf(
                AxmlAttr(ANDROID_NS_URI, "versionCode", TYPE_INT_DEC, intData = versionCode),
                AxmlAttr(ANDROID_NS_URI, "versionName", TYPE_STRING, stringValue = versionName),
                AxmlAttr(null, "package", TYPE_STRING, stringValue = packageName)
            )
        )

        // <uses-sdk>
        writeStartElement(
            "uses-sdk",
            line++,
            listOf(
                AxmlAttr(ANDROID_NS_URI, "minSdkVersion", TYPE_INT_DEC, intData = minSdk),
                AxmlAttr(ANDROID_NS_URI, "targetSdkVersion", TYPE_INT_DEC, intData = targetSdk)
            )
        )
        writeEndElement("uses-sdk", line++)

        // <uses-permission>
        permissions.distinct().forEach { perm ->
            writeStartElement(
                "uses-permission",
                line++,
                listOf(
                    AxmlAttr(ANDROID_NS_URI, "name", TYPE_STRING, stringValue = perm)
                )
            )
            writeEndElement("uses-permission", line++)
        }

        // <application>
        writeStartElement(
            "application",
            line++,
            listOf(
                AxmlAttr(ANDROID_NS_URI, "label", TYPE_REFERENCE, intData = appLabelResId),
                AxmlAttr(ANDROID_NS_URI, "allowBackup", TYPE_INT_BOOLEAN, intData = -1),
                AxmlAttr(ANDROID_NS_URI, "supportsRtl", TYPE_INT_BOOLEAN, intData = -1)
            )
        )

        // <activity>
        writeStartElement(
            "activity",
            line++,
            listOf(
                AxmlAttr(ANDROID_NS_URI, "name", TYPE_STRING, stringValue = normalizedActivity),
                AxmlAttr(ANDROID_NS_URI, "exported", TYPE_INT_BOOLEAN, intData = -1)
            )
        )

        // <intent-filter>
        writeStartElement("intent-filter", line++, emptyList())
        writeStartElement(
            "action",
            line++,
            listOf(AxmlAttr(ANDROID_NS_URI, "name", TYPE_STRING, stringValue = "android.intent.action.MAIN"))
        )
        writeEndElement("action", line++)
        writeStartElement(
            "category",
            line++,
            listOf(AxmlAttr(ANDROID_NS_URI, "name", TYPE_STRING, stringValue = "android.intent.category.LAUNCHER"))
        )
        writeEndElement("category", line++)
        writeEndElement("intent-filter", line++)

        writeEndElement("activity", line++)
        writeEndElement("application", line++)
        writeEndElement("manifest", line++)
        writeEndNamespace()

        val stringPoolBytes = buildUtf16StringPool(stringTable.keys.toList())
        val resourceMapBytes = buildResourceMapChunk(androidAttrResourceMap.map { it.second })
        val bodyBytes = chunks.toByteArray()

        val totalFileSize = 8 + stringPoolBytes.size + resourceMapBytes.size + bodyBytes.size
        val out = ByteBuffer.allocate(totalFileSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(RES_XML_TYPE)
        out.putShort(8)
        out.putInt(totalFileSize)
        out.put(stringPoolBytes)
        out.put(resourceMapBytes)
        out.put(bodyBytes)
        return out.array()
    }

    internal fun buildUtf16StringPool(strings: List<String>): ByteArray {
        val offsets = IntArray(strings.size)
        val stringData = ByteArrayOutputStream()
        strings.forEachIndexed { idx, s ->
            offsets[idx] = stringData.size()
            val chars = s.toCharArray()
            val lenBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            lenBuf.putShort(chars.size.coerceAtMost(32767).toShort())
            stringData.write(lenBuf.array())

            val charBuf = ByteBuffer.allocate(chars.size * 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
            chars.forEach { c -> charBuf.putShort(c.code.toShort()) }
            charBuf.putShort(0) // null terminator
            stringData.write(charBuf.array())
        }

        // Pad to 4-byte boundary
        while (stringData.size() % 4 != 0) {
            stringData.write(0)
        }

        val headerSize = 28
        val offsetsSize = strings.size * 4
        val stringsStart = headerSize + offsetsSize
        val chunkSize = stringsStart + stringData.size()

        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_STRING_POOL_TYPE)
        buf.putShort(headerSize.toShort())
        buf.putInt(chunkSize)
        buf.putInt(strings.size)
        buf.putInt(0) // styleCount
        buf.putInt(0) // UTF-16 flags
        buf.putInt(stringsStart)
        buf.putInt(0) // stylesStart
        offsets.forEach { buf.putInt(it) }
        buf.put(stringData.toByteArray())
        return buf.array()
    }

    private fun buildResourceMapChunk(resIds: List<Int>): ByteArray {
        val chunkSize = 8 + resIds.size * 4
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(RES_XML_RESOURCE_MAP_TYPE)
        buf.putShort(8)
        buf.putInt(chunkSize)
        resIds.forEach { buf.putInt(it) }
        return buf.array()
    }
}

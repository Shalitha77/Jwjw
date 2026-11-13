package com.example.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * Pure Kotlin Dalvik Executable (classes.dex, DEX v035) builder.
 * Generates a valid, 4-byte aligned, checksum-verified DEX file containing
 * the project's launcher Activity class extending android.app.Activity.
 */
object DexContainerBuilder {

    private data class MethodSpec(
        val classTypeIdx: Int,
        val protoIdx: Int,
        val nameStrIdx: Int
    )

    private data class ProtoSpec(
        val shortyStrIdx: Int,
        val returnTypeIdx: Int,
        val paramTypeIndices: List<Int>
    )

    fun buildMainActivityDex(launcherActivityClassName: String): ByteArray {
        val activityDescriptor = "L${launcherActivityClassName.replace('.', '/')};"
        val superDescriptor = "Landroid/app/Activity;"
        val bundleDescriptor = "Landroid/os/Bundle;"
        val voidDescriptor = "V"

        // All strings sorted lexicographically by UTF-8 bytes as required by DEX spec
        val strings = listOf(
            "<init>",
            "MainActivity.kt",
            "V",
            "VL",
            bundleDescriptor,
            superDescriptor,
            activityDescriptor,
            "onCreate"
        ).distinct().sorted()

        fun strIdx(s: String): Int = strings.indexOf(s)

        // Type descriptors sorted by string_ids index
        val typeDescriptors = listOf(
            voidDescriptor,
            bundleDescriptor,
            superDescriptor,
            activityDescriptor
        ).distinct().sortedBy { strIdx(it) }

        fun typeIdx(desc: String): Int = typeDescriptors.indexOf(desc)

        // Protos: ()V and (Landroid/os/Bundle;)V
        val protoVoid = ProtoSpec(strIdx("V"), typeIdx(voidDescriptor), emptyList())
        val protoOnCreate = ProtoSpec(strIdx("VL"), typeIdx(voidDescriptor), listOf(typeIdx(bundleDescriptor)))
        val protos = listOf(protoVoid, protoOnCreate).sortedWith(
            compareBy<ProtoSpec> { it.returnTypeIdx }.thenBy { it.paramTypeIndices.size }
        )
        val protoVoidIdx = protos.indexOf(protoVoid)
        val protoOnCreateIdx = protos.indexOf(protoOnCreate)

        // Methods sorted by (class_idx, name_idx, proto_idx)
        val superInit = MethodSpec(typeIdx(superDescriptor), protoVoidIdx, strIdx("<init>"))
        val superOnCreate = MethodSpec(typeIdx(superDescriptor), protoOnCreateIdx, strIdx("onCreate"))
        val appInit = MethodSpec(typeIdx(activityDescriptor), protoVoidIdx, strIdx("<init>"))
        val appOnCreate = MethodSpec(typeIdx(activityDescriptor), protoOnCreateIdx, strIdx("onCreate"))

        val methods = listOf(superInit, superOnCreate, appInit, appOnCreate).sortedWith(
            compareBy<MethodSpec> { it.classTypeIdx }.thenBy { it.nameStrIdx }.thenBy { it.protoIdx }
        )
        val superInitIdx = methods.indexOf(superInit)
        val superOnCreateIdx = methods.indexOf(superOnCreate)
        val appInitIdx = methods.indexOf(appInit)
        val appOnCreateIdx = methods.indexOf(appOnCreate)

        // Compute fixed section offsets
        val headerSize = 0x70 // 112 bytes
        val stringIdsOff = headerSize
        val stringIdsSize = strings.size
        val typeIdsOff = stringIdsOff + stringIdsSize * 4
        val typeIdsSize = typeDescriptors.size
        val protoIdsOff = typeIdsOff + typeIdsSize * 4
        val protoIdsSize = protos.size
        val fieldIdsOff = 0
        val methodIdsOff = protoIdsOff + protoIdsSize * 12
        val methodIdsSize = methods.size
        val classDefsOff = methodIdsOff + methodIdsSize * 8
        val classDefsSize = 1
        val dataOff = classDefsOff + classDefsSize * 32

        // Build data section sequentially while recording exact file offsets
        val dataBuf = ByteArrayOutputStream()
        fun currentOffset(): Int = dataOff + dataBuf.size()
        fun align4() {
            while (currentOffset() % 4 != 0) {
                dataBuf.write(0)
            }
        }

        // 1. string_data_item entries
        val stringDataOffsets = IntArray(strings.size)
        strings.forEachIndexed { idx, s ->
            stringDataOffsets[idx] = currentOffset()
            val utf8 = s.toByteArray(Charsets.UTF_8)
            writeUleb128(dataBuf, s.length)
            dataBuf.write(utf8)
            dataBuf.write(0)
        }
        align4()

        // 2. type_list for (Landroid/os/Bundle;)
        val bundleTypeListOff = currentOffset()
        writeIntLE(dataBuf, 1) // size = 1
        writeShortLE(dataBuf, typeIdx(bundleDescriptor))
        align4()

        // 3. code_item for <init>()V:
        // invoke-direct {p0}, Landroid/app/Activity;-><init>()V  [70 10 <superInitIdx> 00 00]
        // return-void                                          [0e 00]
        val initCodeOff = currentOffset()
        writeCodeItem(
            out = dataBuf,
            registersSize = 1,
            insSize = 1,
            outsSize = 1,
            insns = shortArrayOf(
                0x1070.toShort(),
                superInitIdx.toShort(),
                0x0000.toShort(),
                0x000e.toShort()
            )
        )
        align4()

        // 4. code_item for onCreate(Bundle)V:
        // invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V [6f 20 <superOnCreateIdx> 10 00]
        // return-void                                                                  [0e 00]
        val onCreateCodeOff = currentOffset()
        writeCodeItem(
            out = dataBuf,
            registersSize = 2,
            insSize = 2,
            outsSize = 2,
            insns = shortArrayOf(
                0x206f.toShort(),
                superOnCreateIdx.toShort(),
                0x0010.toShort(),
                0x000e.toShort()
            )
        )
        align4()

        // 5. class_data_item
        val classDataOff = currentOffset()
        writeUleb128(dataBuf, 0) // static_fields_size
        writeUleb128(dataBuf, 0) // instance_fields_size
        writeUleb128(dataBuf, 2) // direct_methods_size (<init>, onCreate or virtual)
        // Wait: <init> is direct (ACC_PUBLIC | ACC_CONSTRUCTOR = 0x10001), onCreate is virtual (ACC_PUBLIC = 0x1)!
        // Let's rewrite properly: 1 direct method (<init>), 1 virtual method (onCreate)
        dataBuf.reset()
        // Re-record clean deterministic layout without reset overhead:
        return assembleDexFile(
            strings = strings,
            typeDescriptors = typeDescriptors,
            protos = protos,
            methods = methods,
            activityTypeIdx = typeIdx(activityDescriptor),
            superTypeIdx = typeIdx(superDescriptor),
            sourceFileStrIdx = strIdx("MainActivity.kt"),
            bundleTypeIdx = typeIdx(bundleDescriptor),
            superInitIdx = superInitIdx,
            superOnCreateIdx = superOnCreateIdx,
            appInitIdx = appInitIdx,
            appOnCreateIdx = appOnCreateIdx,
            headerSize = headerSize,
            stringIdsOff = stringIdsOff,
            typeIdsOff = typeIdsOff,
            protoIdsOff = protoIdsOff,
            fieldIdsOff = fieldIdsOff,
            methodIdsOff = methodIdsOff,
            classDefsOff = classDefsOff,
            dataOff = dataOff
        )
    }

    private fun assembleDexFile(
        strings: List<String>,
        typeDescriptors: List<String>,
        protos: List<ProtoSpec>,
        methods: List<MethodSpec>,
        activityTypeIdx: Int,
        superTypeIdx: Int,
        sourceFileStrIdx: Int,
        bundleTypeIdx: Int,
        superInitIdx: Int,
        superOnCreateIdx: Int,
        appInitIdx: Int,
        appOnCreateIdx: Int,
        headerSize: Int,
        stringIdsOff: Int,
        typeIdsOff: Int,
        protoIdsOff: Int,
        fieldIdsOff: Int,
        methodIdsOff: Int,
        classDefsOff: Int,
        dataOff: Int
    ): ByteArray {
        val dataBuf = ByteArrayOutputStream()
        fun currentOffset(): Int = dataOff + dataBuf.size()
        fun align4() {
            while (currentOffset() % 4 != 0) dataBuf.write(0)
        }

        // string_data_item
        val stringDataOffsets = IntArray(strings.size)
        strings.forEachIndexed { idx, s ->
            stringDataOffsets[idx] = currentOffset()
            val utf8 = s.toByteArray(Charsets.UTF_8)
            writeUleb128(dataBuf, s.length)
            dataBuf.write(utf8)
            dataBuf.write(0)
        }
        align4()

        // type_list
        val bundleTypeListOff = currentOffset()
        writeIntLE(dataBuf, 1)
        writeShortLE(dataBuf, bundleTypeIdx)
        align4()

        // code_item: <init>
        val initCodeOff = currentOffset()
        writeCodeItem(
            out = dataBuf,
            registersSize = 1,
            insSize = 1,
            outsSize = 1,
            insns = shortArrayOf(
                0x1070.toShort(),
                superInitIdx.toShort(),
                0x0000.toShort(),
                0x000e.toShort()
            )
        )
        align4()

        // code_item: onCreate
        val onCreateCodeOff = currentOffset()
        writeCodeItem(
            out = dataBuf,
            registersSize = 2,
            insSize = 2,
            outsSize = 2,
            insns = shortArrayOf(
                0x206f.toShort(),
                superOnCreateIdx.toShort(),
                0x0010.toShort(),
                0x000e.toShort()
            )
        )
        align4()

        // class_data_item: 1 direct method (<init>), 1 virtual method (onCreate)
        val classDataOff = currentOffset()
        writeUleb128(dataBuf, 0) // static_fields_size
        writeUleb128(dataBuf, 0) // instance_fields_size
        writeUleb128(dataBuf, 1) // direct_methods_size
        writeUleb128(dataBuf, 1) // virtual_methods_size
        // direct[0]: <init>
        writeUleb128(dataBuf, appInitIdx) // method_idx_diff
        writeUleb128(dataBuf, 0x10001) // ACC_PUBLIC | ACC_CONSTRUCTOR
        writeUleb128(dataBuf, initCodeOff)
        // virtual[0]: onCreate
        writeUleb128(dataBuf, appOnCreateIdx) // method_idx_diff (from 0)
        writeUleb128(dataBuf, 0x0001) // ACC_PUBLIC
        writeUleb128(dataBuf, onCreateCodeOff)
        align4()

        // map_list
        val mapOff = currentOffset()
        val mapItems = listOf(
            Triple(0x0000, 1, 0), // HEADER_ITEM
            Triple(0x0001, strings.size, stringIdsOff), // STRING_ID_ITEM
            Triple(0x0002, typeDescriptors.size, typeIdsOff), // TYPE_ID_ITEM
            Triple(0x0003, protos.size, protoIdsOff), // PROTO_ID_ITEM
            Triple(0x0005, methods.size, methodIdsOff), // METHOD_ID_ITEM
            Triple(0x0006, 1, classDefsOff), // CLASS_DEF_ITEM
            Triple(0x1001, 1, bundleTypeListOff), // TYPE_LIST
            Triple(0x2000, 1, classDataOff), // CLASS_DATA_ITEM
            Triple(0x2001, 2, initCodeOff), // CODE_ITEM
            Triple(0x2002, strings.size, stringDataOffsets[0]), // STRING_DATA_ITEM
            Triple(0x1000, 1, mapOff) // MAP_LIST
        )
        writeIntLE(dataBuf, mapItems.size)
        mapItems.forEach { (type, count, off) ->
            writeShortLE(dataBuf, type)
            writeShortLE(dataBuf, 0)
            writeIntLE(dataBuf, count)
            writeIntLE(dataBuf, off)
        }
        align4()

        val dataBytes = dataBuf.toByteArray()
        val fileSize = dataOff + dataBytes.size

        val dex = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        // Header (112 bytes)
        dex.put("dex\n035\u0000".toByteArray(Charsets.US_ASCII)) // 8 bytes magic
        dex.putInt(0) // checksum placeholder (bytes 8..11)
        dex.put(ByteArray(20)) // SHA-1 placeholder (bytes 12..31)
        dex.putInt(fileSize) // file_size
        dex.putInt(headerSize) // header_size = 0x70
        dex.putInt(0x12345678) // endian_tag
        dex.putInt(0) // link_size
        dex.putInt(0) // link_off
        dex.putInt(mapOff) // map_off
        dex.putInt(strings.size) // string_ids_size
        dex.putInt(stringIdsOff) // string_ids_off
        dex.putInt(typeDescriptors.size) // type_ids_size
        dex.putInt(typeIdsOff) // type_ids_off
        dex.putInt(protos.size) // proto_ids_size
        dex.putInt(protoIdsOff) // proto_ids_off
        dex.putInt(0) // field_ids_size
        dex.putInt(fieldIdsOff) // field_ids_off
        dex.putInt(methods.size) // method_ids_size
        dex.putInt(methodIdsOff) // method_ids_off
        dex.putInt(1) // class_defs_size
        dex.putInt(classDefsOff) // class_defs_off
        dex.putInt(dataBytes.size) // data_size
        dex.putInt(dataOff) // data_off

        // string_ids
        stringDataOffsets.forEach { dex.putInt(it) }

        // type_ids
        typeDescriptors.forEach { desc -> dex.putInt(strings.indexOf(desc)) }

        // proto_ids
        protos.forEach { p ->
            dex.putInt(p.shortyStrIdx)
            dex.putInt(p.returnTypeIdx)
            dex.putInt(if (p.paramTypeIndices.isEmpty()) 0 else bundleTypeListOff)
        }

        // method_ids
        methods.forEach { m ->
            dex.putShort(m.classTypeIdx.toShort())
            dex.putShort(m.protoIdx.toShort())
            dex.putInt(m.nameStrIdx)
        }

        // class_defs[0]
        dex.putInt(activityTypeIdx) // class_idx
        dex.putInt(0x0001) // access_flags = ACC_PUBLIC
        dex.putInt(superTypeIdx) // superclass_idx
        dex.putInt(0) // interfaces_off
        dex.putInt(sourceFileStrIdx) // source_file_idx
        dex.putInt(0) // annotations_off
        dex.putInt(classDataOff) // class_data_off
        dex.putInt(0) // static_values_off

        // data section
        dex.put(dataBytes)

        val bytes = dex.array()

        // Compute SHA-1 signature over bytes 32..EOF -> store in bytes 12..31
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(bytes, 32, bytes.size - 32)
        val signature = sha1.digest()
        System.arraycopy(signature, 0, bytes, 12, 20)

        // Compute Adler-32 checksum over bytes 12..EOF -> store LE in bytes 8..11
        val adler = Adler32()
        adler.update(bytes, 12, bytes.size - 12)
        val checksum = adler.value.toInt()
        ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(checksum)

        return bytes
    }

    private fun writeCodeItem(
        out: ByteArrayOutputStream,
        registersSize: Int,
        insSize: Int,
        outsSize: Int,
        insns: ShortArray
    ) {
        writeShortLE(out, registersSize)
        writeShortLE(out, insSize)
        writeShortLE(out, outsSize)
        writeShortLE(out, 0) // tries_size
        writeIntLE(out, 0) // debug_info_off
        writeIntLE(out, insns.size)
        insns.forEach { writeShortLE(out, it.toInt()) }
    }

    private fun writeUleb128(out: ByteArrayOutputStream, value: Int) {
        var remaining = value
        do {
            var b = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining != 0) b = b or 0x80
            out.write(b)
        } while (remaining != 0)
    }

    private fun writeShortLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }

    private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }
}

package com.apm.memory.oom

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InterruptedIOException

/**
 * Clears primitive-array payloads in ART and standard Hprof files while preserving their structure.
 * Identifier widths come from the header, including ART's four-byte IDs on 64-bit devices.
 * Equal-length zero filling reduces sensitive content; it does not reduce the file's byte length.
 * Records are streamed with one fixed buffer so an OOM diagnostic does not copy whole heap segments.
 */
internal class HprofStripProcessor {
    /** Redacts one complete file, removing the partial destination on malformed or cancelled input. */
    fun strip(input: File, output: File): Boolean = try {
        input.inputStream().buffered().use { source ->
            output.outputStream().buffered().use { destination ->
                processHprof(DataInputStream(source), DataOutputStream(destination), input.length())
            }
        }
        true
    } catch (_: Exception) {
        output.delete()
        false
    }

    /** Copies the validated header and processes each length-delimited record without large allocations. */
    private fun processHprof(input: DataInputStream, output: DataOutputStream, fileBytes: Long) {
        val format = StringBuilder()
        while (true) {
            val value = input.readUnsignedByte()
            output.writeByte(value)
            if (value == 0) break
            require(format.length < MAX_HEADER_CHARS) { "Hprof header is too long" }
            format.append(value.toChar())
        }
        require(format.toString() in SUPPORTED_FORMATS) { "Unsupported Hprof format" }
        val idSize = input.readInt()
        require(idSize == ID_SIZE_32 || idSize == ID_SIZE_64) { "Unsupported Hprof identifier width" }
        output.writeInt(idSize)
        output.writeLong(input.readLong())
        var remainingFileBytes = fileBytes - format.length - 1 - HEADER_FIXED_BYTES
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (remainingFileBytes > 0L) {
            checkCancellation()
            require(remainingFileBytes >= RECORD_HEADER_BYTES) { "Truncated Hprof record header" }
            val tag = input.readUnsignedByte()
            val time = input.readInt()
            val encodedLength = input.readInt()
            val length = encodedLength.toLong() and UNSIGNED_INT_MASK
            remainingFileBytes -= RECORD_HEADER_BYTES
            require(length <= remainingFileBytes) { "Truncated Hprof record body" }
            output.writeByte(tag)
            output.writeInt(time)
            output.writeInt(encodedLength)
            val record = Record(input, output, length, buffer)
            if (tag == TAG_HEAP_DUMP || tag == TAG_HEAP_DUMP_SEGMENT) {
                stripHeapDump(record, idSize)
            } else record.copy(length)
            remainingFileBytes -= length
        }
        require(remainingFileBytes == 0L && input.read() == -1) { "Invalid Hprof file size" }
    }

    /** Copies heap metadata and roots exactly, replacing only valid primitive-array data. */
    private fun stripHeapDump(record: Record, idSize: Int) {
        while (record.remaining > 0L) {
            checkCancellation()
            when (val tag = record.byte()) {
                GC_CLASS_DUMP -> {
                    record.copy(idSize.toLong() * CLASS_ID_COUNT + U4_BYTES * CLASS_U4_COUNT)
                    repeat(record.unsignedShort()) {
                        record.copy(U2_BYTES.toLong())
                        record.copy(typeSize(record.byte(), idSize).toLong())
                    }
                    repeat(record.unsignedShort()) {
                        record.copy(idSize.toLong())
                        record.copy(typeSize(record.byte(), idSize).toLong())
                    }
                    repeat(record.unsignedShort()) {
                        record.copy(idSize.toLong())
                        typeSize(record.byte(), idSize)
                    }
                }
                GC_INSTANCE_DUMP -> {
                    record.copy(idSize.toLong() * INSTANCE_ID_COUNT + U4_BYTES)
                    record.copy(record.unsignedInt())
                }
                GC_OBJ_ARRAY_DUMP -> {
                    record.copy(idSize.toLong() + U4_BYTES)
                    val elements = record.unsignedInt()
                    record.copy(idSize.toLong())
                    record.copy(elements * idSize)
                }
                GC_PRIMITIVE_ARRAY_DUMP, GC_PRIMITIVE_ARRAY_NO_DATA_DUMP -> {
                    record.copy(idSize.toLong() + U4_BYTES)
                    val elements = record.unsignedInt()
                    val type = record.byte()
                    require(type != TYPE_OBJECT) { "Object type in primitive array" }
                    val bytes = elements * typeSize(type, idSize)
                    if (tag == GC_PRIMITIVE_ARRAY_DUMP) record.zero(bytes)
                }
                GC_ROOT_UNKNOWN, GC_ROOT_STICKY_CLASS, GC_ROOT_MONITOR_USED,
                GC_ROOT_INTERNED_STRING, GC_ROOT_FINALIZING, GC_ROOT_DEBUGGER,
                GC_ROOT_REFERENCE_CLEANUP, GC_ROOT_VM_INTERNAL, GC_UNREACHABLE -> record.copy(idSize.toLong())
                GC_ROOT_JNI_GLOBAL -> record.copy(idSize.toLong() * JNI_GLOBAL_ID_COUNT)
                GC_ROOT_JNI_LOCAL, GC_ROOT_JAVA_FRAME, GC_ROOT_THREAD_OBJ,
                GC_ROOT_JNI_MONITOR -> record.copy(idSize.toLong() + U4_BYTES * ROOT_SERIAL_COUNT)
                GC_ROOT_NATIVE_STACK, GC_ROOT_THREAD_BLOCK -> record.copy(idSize.toLong() + U4_BYTES)
                GC_HEAP_DUMP_INFO -> record.copy(U4_BYTES + idSize.toLong())
                else -> throw IllegalArgumentException("Unknown Hprof heap tag")
            }
        }
    }

    /** Returns the exact Hprof value width and rejects unknown types instead of desynchronizing parsing. */
    private fun typeSize(type: Int, idSize: Int): Int = when (type) {
        TYPE_OBJECT -> idSize
        TYPE_BOOLEAN, TYPE_BYTE -> 1
        TYPE_CHAR, TYPE_SHORT -> U2_BYTES
        TYPE_FLOAT, TYPE_INT -> U4_BYTES
        TYPE_DOUBLE, TYPE_LONG -> U8_BYTES
        else -> throw IllegalArgumentException("Unknown Hprof value type")
    }

    /** One record's remaining-byte budget prevents subrecords from consuming the next record. */
    private class Record(
        /** Source positioned at the record body. */
        private val input: DataInputStream,
        /** Destination retaining all record lengths and metadata. */
        private val output: DataOutputStream,
        /** Remaining bytes within this record. */
        var remaining: Long,
        /** Shared fixed-size copy buffer. */
        private val buffer: ByteArray
    ) {
        /** Checks the budget before any read; malformed lengths never allocate payload-sized arrays. */
        private fun consume(bytes: Long) {
            if (bytes < 0L || bytes > remaining) throw EOFException("Truncated Hprof subrecord")
            remaining -= bytes
        }

        /** Copies and returns one metadata byte. */
        fun byte(): Int {
            consume(1)
            return input.readUnsignedByte().also(output::writeByte)
        }

        /** Copies and returns an unsigned field count. */
        fun unsignedShort(): Int {
            consume(U2_BYTES.toLong())
            return input.readUnsignedShort().also(output::writeShort)
        }

        /** Copies and returns an unsigned array or instance length. */
        fun unsignedInt(): Long {
            consume(U4_BYTES.toLong())
            val value = input.readInt()
            output.writeInt(value)
            return value.toLong() and UNSIGNED_INT_MASK
        }

        /** Streams exact metadata bytes without copying an entire heap segment into memory. */
        fun copy(bytes: Long) = transfer(bytes, clear = false)

        /** Reads every original payload byte before writing an equal number of zeros. */
        fun zero(bytes: Long) = transfer(bytes, clear = true)

        /** Performs bounded transfer and rejects truncated primitive data instead of silently padding it. */
        private fun transfer(bytes: Long, clear: Boolean) {
            consume(bytes)
            var left = bytes
            while (left > 0L) {
                checkCancellation()
                val count = minOf(left, buffer.size.toLong()).toInt()
                input.readFully(buffer, 0, count)
                if (clear) buffer.fill(0, 0, count)
                output.write(buffer, 0, count)
                left -= count
            }
        }
    }

    companion object {
        /** Cancels strip work promptly when the dump executor is stopped. */
        private fun checkCancellation() {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Hprof strip cancelled")
        }
        /** Header formats emitted by standard Hprof and ART. */
        private val SUPPORTED_FORMATS = setOf("JAVA PROFILE 1.0.1", "JAVA PROFILE 1.0.2", "JAVA PROFILE 1.0.3")
        /** Maximum format-string length before its null terminator. */
        private const val MAX_HEADER_CHARS = 64
        /** Supported identifier widths, independent of process ABI. */
        private const val ID_SIZE_32 = 4
        private const val ID_SIZE_64 = 8
        /** Fixed fields after the format string: identifier width and timestamp. */
        private const val HEADER_FIXED_BYTES = 12
        /** Tag, relative timestamp and unsigned length. */
        private const val RECORD_HEADER_BYTES = 9L
        /** Fixed auxiliary memory regardless of record size. */
        private const val COPY_BUFFER_BYTES = 8192
        /** Unsigned conversion for Hprof's u4 lengths. */
        private const val UNSIGNED_INT_MASK = 0xffffffffL
        /** Hprof scalar widths. */
        private const val U2_BYTES = 2
        private const val U4_BYTES = 4
        private const val U8_BYTES = 8
        /** CLASS_DUMP contains seven IDs, a stack serial and an instance size. */
        private const val CLASS_ID_COUNT = 7
        private const val CLASS_U4_COUNT = 2
        /** INSTANCE_DUMP contains its object and class IDs. */
        private const val INSTANCE_ID_COUNT = 2
        /** JNI global roots retain the object and reference IDs. */
        private const val JNI_GLOBAL_ID_COUNT = 2
        /** Thread/frame roots carry two u4 serial fields. */
        private const val ROOT_SERIAL_COUNT = 2
        /** Primitive and object value tags. */
        private const val TYPE_OBJECT = 2
        private const val TYPE_BOOLEAN = 4
        private const val TYPE_CHAR = 5
        private const val TYPE_FLOAT = 6
        private const val TYPE_DOUBLE = 7
        private const val TYPE_BYTE = 8
        private const val TYPE_SHORT = 9
        private const val TYPE_INT = 10
        private const val TYPE_LONG = 11
        /** Standard top-level heap records. */
        const val TAG_HEAP_DUMP = 0x0c
        const val TAG_HEAP_DUMP_SEGMENT = 0x1c
        /** Standard heap subrecords. */
        const val GC_ROOT_UNKNOWN = 0xff
        const val GC_ROOT_JNI_GLOBAL = 0x01
        const val GC_ROOT_JNI_LOCAL = 0x02
        const val GC_ROOT_JAVA_FRAME = 0x03
        const val GC_ROOT_NATIVE_STACK = 0x04
        const val GC_ROOT_STICKY_CLASS = 0x05
        const val GC_ROOT_THREAD_BLOCK = 0x06
        const val GC_ROOT_MONITOR_USED = 0x07
        const val GC_ROOT_THREAD_OBJ = 0x08
        const val GC_CLASS_DUMP = 0x20
        const val GC_INSTANCE_DUMP = 0x21
        const val GC_OBJ_ARRAY_DUMP = 0x22
        const val GC_PRIMITIVE_ARRAY_DUMP = 0x23
        /** Android heap metadata, roots and historical no-data array tag. */
        const val GC_HEAP_DUMP_INFO = 0xfe
        const val GC_ROOT_INTERNED_STRING = 0x89
        const val GC_ROOT_FINALIZING = 0x8a
        const val GC_ROOT_DEBUGGER = 0x8b
        const val GC_ROOT_REFERENCE_CLEANUP = 0x8c
        const val GC_ROOT_VM_INTERNAL = 0x8d
        const val GC_ROOT_JNI_MONITOR = 0x8e
        const val GC_UNREACHABLE = 0x90
        const val GC_PRIMITIVE_ARRAY_NO_DATA_DUMP = 0xc3
    }
}

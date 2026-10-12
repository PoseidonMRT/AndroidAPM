package com.apm.memory.oom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Contract tests for primitive-array redaction in Hprof strip output. */
class HprofStripProcessorTest {
    /** Temporary input and output files owned by each test. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Primitive array payload bytes are replaced while record structure is retained. */
    @Test
    fun `primitive array contents are zeroed`() {
        val input = temporaryFolder.newFile("input.hprof")
        val output = temporaryFolder.newFile("output.hprof")
        input.writeBytes(buildPrimitiveArrayHprof(byteArrayOf(1, 2, 3)))

        assertTrue(HprofStripProcessor().strip(input, output))

        assertArrayEquals(byteArrayOf(0, 0, 0), readPrimitiveArrayPayload(output.readBytes()))
    }

    /** Malformed input returns failure and removes the partial destination. */
    @Test
    fun `malformed input removes output`() {
        val input = temporaryFolder.newFile("broken.hprof")
        val output = temporaryFolder.newFile("broken-output.hprof")
        input.writeBytes(byteArrayOf(1, 2, 3))

        assertFalse(HprofStripProcessor().strip(input, output))
        assertFalse(output.exists())
    }

    /** ART emits four-byte IDs even in a 64-bit process; every supported root retains its exact layout. */
    @Test
    fun `ART four byte identifiers roots and heap metadata stay intact`() {
        verifyStructuredHeap(4)
    }

    /** Standard eight-byte files retain classes, references and array lengths just like ART files. */
    @Test
    fun `eight byte identifiers roots and heap metadata stay intact`() {
        verifyStructuredHeap(8)
    }

    /** Missing primitive data must fail, rather than being manufactured by unchecked skipBytes. */
    @Test
    fun `truncated primitive array is rejected without padding`() {
        val body = heapBytes { output ->
            output.writeByte(HprofStripProcessor.GC_PRIMITIVE_ARRAY_DUMP)
            output.writeLong(1)
            output.writeInt(0)
            output.writeInt(3)
            output.writeByte(TYPE_BYTE)
            // No payload follows this declared three-byte array.
        }
        assertRejected(buildHprof(body, 8))
    }

    /** Unsigned/overflowing lengths are checked against the record before any large allocation. */
    @Test
    fun `oversized record and array lengths are rejected with bounded memory`() {
        val array = heapBytes { output ->
            output.writeByte(HprofStripProcessor.GC_PRIMITIVE_ARRAY_DUMP)
            output.writeInt(1)
            output.writeInt(0)
            output.writeInt(-1)
            output.writeByte(TYPE_LONG)
        }
        assertRejected(buildHprof(array, 4))
        val input = buildHprof(byteArrayOf(), 4)
        // Replace the zero-length record's final u4 with 0xffffffff.
        input.fill((-1).toByte(), input.size - 4)
        assertRejected(input)
    }

    /** Unknown tags and types cannot desynchronize metadata parsing into a false successful strip. */
    @Test
    fun `unknown heap tags types and identifier widths fail closed`() {
        assertRejected(buildHprof(byteArrayOf(0x55), 4))
        assertRejected(buildHprof(byteArrayOf(), 3))
        val array = heapBytes { output ->
            output.writeByte(HprofStripProcessor.GC_PRIMITIVE_ARRAY_DUMP)
            output.writeInt(1)
            output.writeInt(0)
            output.writeInt(0)
            output.writeByte(0x55)
        }
        assertRejected(buildHprof(array, 4))
    }

    /** Repeated chunks are all cleared and the output remains equal length across the copy-buffer boundary. */
    @Test
    fun `large primitive array is streamed and preserves file length`() {
        val payload = ByteArray(LARGE_ARRAY_BYTES) { 0x7f }
        val input = temporaryFolder.newFile("large-input.hprof")
        val output = temporaryFolder.newFile("large-output.hprof")
        input.writeBytes(buildPrimitiveArrayHprof(payload))
        assertTrue(HprofStripProcessor().strip(input, output))
        assertEquals(input.length(), output.length())
        assertArrayEquals(ByteArray(payload.size), readPrimitiveArrayPayload(output.readBytes()))
    }

    /** Runs the real processor against a complete mixed layout and compares every output byte. */
    private fun verifyStructuredHeap(idSize: Int) {
        val input = temporaryFolder.newFile("structured-$idSize.hprof")
        val output = temporaryFolder.newFile("structured-$idSize-output.hprof")
        input.writeBytes(buildHprof(structuredHeap(idSize, clear = false), idSize))
        assertTrue(HprofStripProcessor().strip(input, output))
        assertArrayEquals(buildHprof(structuredHeap(idSize, clear = true), idSize), output.readBytes())
        assertEquals(input.length(), output.length())
    }

    /** Builds official ART/standard root layouts, mixed field types, and both array variants. */
    private fun structuredHeap(idSize: Int, clear: Boolean): ByteArray = heapBytes { output ->
        output.writeByte(HprofStripProcessor.GC_HEAP_DUMP_INFO)
        output.writeInt(0x5a)
        output.writeId(idSize, 1)
        val oneIdRoots = listOf(0xff, 0x05, 0x07, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x90)
        for (tag in oneIdRoots) {
            output.writeByte(tag)
            output.writeId(idSize, 2)
        }
        output.writeByte(0x01)
        output.writeId(idSize, 3)
        output.writeId(idSize, 4)
        for (tag in listOf(0x02, 0x03, 0x08, 0x8e)) {
            output.writeByte(tag)
            output.writeId(idSize, 5)
            output.writeInt(6)
            output.writeInt(7)
        }
        for (tag in listOf(0x04, 0x06)) {
            output.writeByte(tag)
            output.writeId(idSize, 8)
            output.writeInt(9)
        }
        output.writeByte(HprofStripProcessor.GC_CLASS_DUMP)
        output.writeId(idSize, 10)
        output.writeInt(0)
        repeat(6) { output.writeId(idSize, 11 + it.toLong()) }
        output.writeInt(16)
        // Constant pool: an object and an int exercise identifier-dependent type sizes.
        output.writeShort(2)
        output.writeShort(1)
        output.writeByte(TYPE_OBJECT)
        output.writeId(idSize, 17)
        output.writeShort(2)
        output.writeByte(TYPE_INT)
        output.writeInt(18)
        // Static fields: an object and a long.
        output.writeShort(2)
        output.writeId(idSize, 19)
        output.writeByte(TYPE_OBJECT)
        output.writeId(idSize, 20)
        output.writeId(idSize, 21)
        output.writeByte(TYPE_LONG)
        output.writeLong(22)
        // Instance field descriptors carry types but no values.
        output.writeShort(2)
        output.writeId(idSize, 23)
        output.writeByte(TYPE_OBJECT)
        output.writeId(idSize, 24)
        output.writeByte(TYPE_BYTE)
        output.writeByte(HprofStripProcessor.GC_INSTANCE_DUMP)
        output.writeId(idSize, 25)
        output.writeInt(0)
        output.writeId(idSize, 10)
        output.writeInt(idSize)
        output.writeId(idSize, 26)
        output.writeByte(HprofStripProcessor.GC_OBJ_ARRAY_DUMP)
        output.writeId(idSize, 27)
        output.writeInt(0)
        output.writeInt(2)
        output.writeId(idSize, 10)
        output.writeId(idSize, 25)
        output.writeId(idSize, 26)
        output.writeByte(HprofStripProcessor.GC_PRIMITIVE_ARRAY_NO_DATA_DUMP)
        output.writeId(idSize, 28)
        output.writeInt(0)
        output.writeInt(2)
        output.writeByte(TYPE_BYTE)
        output.writeByte(HprofStripProcessor.GC_PRIMITIVE_ARRAY_DUMP)
        output.writeId(idSize, 29)
        output.writeInt(0)
        output.writeInt(3)
        output.writeByte(TYPE_BYTE)
        output.write(if (clear) byteArrayOf(0, 0, 0) else byteArrayOf(1, 2, 3))
    }

    /** Writes either permitted ID width; process architecture does not dictate Hprof width. */
    private fun DataOutputStream.writeId(idSize: Int, value: Long) {
        if (idSize == 4) writeInt(value.toInt()) else writeLong(value)
    }

    /** Encodes a small structured heap body for deterministic format regressions. */
    private fun heapBytes(write: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use(write)
    }.toByteArray()

    /** Adds the actual identifier header and one length-delimited heap segment. */
    private fun buildHprof(body: ByteArray, idSize: Int): ByteArray = heapBytes { output ->
        output.write(HPROF_HEADER)
        output.writeByte(0)
        output.writeInt(idSize)
        output.writeLong(0)
        output.writeByte(HprofStripProcessor.TAG_HEAP_DUMP_SEGMENT)
        output.writeInt(0)
        output.writeInt(body.size)
        output.write(body)
    }

    /** Verifies that malformed input leaves no destination behind. */
    private fun assertRejected(bytes: ByteArray) {
        val input = temporaryFolder.newFile()
        val output = temporaryFolder.newFile()
        input.writeBytes(bytes)
        assertFalse(HprofStripProcessor().strip(input, output))
        assertFalse(output.exists())
    }

    /** Builds one minimal heap-dump segment containing a byte array. */
    private fun buildPrimitiveArrayHprof(payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(HprofStripProcessor.GC_PRIMITIVE_ARRAY_DUMP)
                output.writeLong(1L)
                output.writeInt(0)
                output.writeInt(payload.size)
                output.writeByte(TYPE_BYTE)
                output.write(payload)
            }
        }.toByteArray()
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(HPROF_HEADER)
                output.writeByte(0)
                output.writeInt(IDENTIFIER_SIZE)
                output.writeLong(0L)
                output.writeByte(HprofStripProcessor.TAG_HEAP_DUMP_SEGMENT)
                output.writeInt(0)
                output.writeInt(body.size)
                output.write(body)
            }
        }.toByteArray()
    }

    /** Extracts the byte-array payload from the generated stripped record. */
    private fun readPrimitiveArrayPayload(hprof: ByteArray): ByteArray {
        val input = DataInputStream(hprof.inputStream())
        while (input.readByte().toInt() != 0) {
            // Skip the null-terminated format string.
        }
        input.readInt()
        input.readLong()
        input.readByte()
        input.readInt()
        input.readInt()
        input.readByte()
        input.readLong()
        input.readInt()
        val elementCount = input.readInt()
        input.readByte()
        return ByteArray(elementCount).also(input::readFully)
    }

    companion object {
        /** Minimal Hprof format string. */
        private val HPROF_HEADER = "JAVA PROFILE 1.0.3".toByteArray()
        /** Identifier width used by the current strip implementation. */
        private const val IDENTIFIER_SIZE = 8
        /** Hprof primitive type code for byte. */
        private const val TYPE_BYTE = 8
        /** Hprof object, int and long type tags. */
        private const val TYPE_OBJECT = 2
        private const val TYPE_INT = 10
        private const val TYPE_LONG = 11
        /** Several fixed-copy-buffer chunks plus a tail. */
        private const val LARGE_ARRAY_BYTES = 131_079
    }
}

package app.zenten.vault.bravesync.proto

import java.io.ByteArrayOutputStream

/**
 * Small, deliberately scoped protobuf wire codec for the password-only Sync messages.
 * It supports the wire types used by Chromium Sync (varint and length-delimited) and
 * rejects malformed/truncated payloads rather than attempting best-effort parsing.
 */
internal object ProtoWire {
    const val VARINT = 0
    const val LENGTH_DELIMITED = 2

    fun message(build: Writer.() -> Unit): ByteArray = Writer().apply(build).toByteArray()

    class Writer {
        private val output = ByteArrayOutputStream()

        fun varint(field: Int, value: Long) {
            tag(field, VARINT)
            rawVarint(value)
        }

        fun bytes(field: Int, value: ByteArray) {
            tag(field, LENGTH_DELIMITED)
            rawVarint(value.size.toLong())
            output.write(value)
        }

        fun string(field: Int, value: String) = bytes(field, value.encodeToByteArray())
        fun message(field: Int, value: ByteArray) = bytes(field, value)
        fun toByteArray(): ByteArray = output.toByteArray()

        private fun tag(field: Int, wireType: Int) {
            require(field > 0) { "Protobuf field number must be positive" }
            rawVarint((field.toLong() shl 3) or wireType.toLong())
        }

        private fun rawVarint(value: Long) {
            require(value >= 0) { "This encoder only supports non-negative protobuf varints" }
            var remaining = value
            while (remaining and 0x7f.inv().toLong() != 0L) {
                output.write(((remaining and 0x7f) or 0x80).toInt())
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }
    }

    data class Field(
        val number: Int,
        val wireType: Int,
        val varint: Long? = null,
        val bytes: ByteArray? = null,
        val group: List<Field>? = null,
    )

    fun fields(payload: ByteArray): List<Field> {
        val reader = Reader(payload)
        return buildList {
            while (!reader.atEnd()) add(reader.field())
        }
    }

    private class Reader(private val data: ByteArray) {
        private var offset = 0
        fun atEnd(): Boolean = offset == data.size

        fun field(): Field {
            val tag = readVarint()
            return fieldFromTag(tag)
        }

        private fun fieldFromTag(tag: Long): Field {
            val number = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            require(number > 0) { "Invalid protobuf field number" }
            return when (wireType) {
                VARINT -> Field(number, wireType, varint = readVarint())
                LENGTH_DELIMITED -> {
                    val length = readVarint()
                    require(length <= Int.MAX_VALUE && offset + length <= data.size) { "Truncated protobuf bytes field" }
                    val bytes = data.copyOfRange(offset, offset + length.toInt())
                    offset += length.toInt()
                    Field(number, wireType, bytes = bytes)
                }
                3 -> { // Deprecated protobuf groups remain in CommitResponse.EntryResponse.
                    val members = buildList {
                        while (true) {
                            val childTag = readVarint()
                            if ((childTag and 0x07) == 4L) {
                                require((childTag ushr 3).toInt() == number) { "Mismatched protobuf end-group" }
                                break
                            }
                            add(fieldFromTag(childTag))
                        }
                    }
                    Field(number, wireType, group = members)
                }
                4 -> throw IllegalArgumentException("Unexpected protobuf end-group")
                else -> throw IllegalArgumentException("Unsupported protobuf wire type: $wireType")
            }
        }

        private fun readVarint(): Long {
            var result = 0L
            repeat(10) { index ->
                require(offset < data.size) { "Truncated protobuf varint" }
                val byte = data[offset++].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl (index * 7))
                if (byte and 0x80 == 0) return result
            }
            throw IllegalArgumentException("Malformed protobuf varint")
        }
    }
}

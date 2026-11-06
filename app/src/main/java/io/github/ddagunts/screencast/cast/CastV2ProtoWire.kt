package io.github.ddagunts.screencast.cast

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

/**
 * Pure Kotlin wire encoder/decoder for the Google Cast V2 `CastMessage` protocol buffer
 * sent over TLS port 8009.
 *
 * Protobuf schema (`cast_channel.proto`):
 * ```
 * message CastMessage {
 *   required ProtocolVersion protocol_version = 1; // 0 = CASTV2_1_0
 *   required string source_id = 2;
 *   required string destination_id = 3;
 *   required string namespace = 4;
 *   required PayloadType payload_type = 5; // 0 = STRING, 1 = BINARY
 *   optional string payload_utf8 = 6;
 * }
 * ```
 */
data class CastV2Message(
    val protocolVersion: Int = 0,
    val sourceId: String = DEFAULT_SOURCE_ID,
    val destinationId: String = RECEIVER_DESTINATION_ID,
    val namespace: String,
    val payloadType: Int = PAYLOAD_TYPE_STRING,
    val payloadUtf8: String
) {
    companion object {
        const val DEFAULT_SOURCE_ID = "sender-0"
        const val RECEIVER_DESTINATION_ID = "receiver-0"
        const val PAYLOAD_TYPE_STRING = 0

        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    }
}

object CastV2ProtoWire {

    fun writeDelimitedMessage(out: DataOutputStream, message: CastV2Message) {
        val protoBytes = encodeToProtoBytes(message)
        synchronized(out) {
            out.writeInt(protoBytes.size) // 4-byte Big-Endian length prefix
            out.write(protoBytes)
            out.flush()
        }
    }

    fun readDelimitedMessage(input: DataInputStream): CastV2Message? {
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length <= 0 || length > MAX_FRAME_SIZE_BYTES) {
            throw IllegalStateException("Invalid CastV2 frame length: $length")
        }
        val frame = ByteArray(length)
        input.readFully(frame)
        return decodeFromProtoBytes(frame)
    }

    internal fun encodeToProtoBytes(message: CastV2Message): ByteArray {
        val bos = ByteArrayOutputStream(256)
        // Field 1: protocol_version (varint, tag = (1 shl 3) | 0 = 0x08)
        bos.write(0x08)
        writeVarint(bos, message.protocolVersion)

        // Field 2: source_id (length-delimited string, tag = (2 shl 3) | 2 = 0x12)
        writeStringField(bos, 0x12, message.sourceId)

        // Field 3: destination_id (length-delimited string, tag = (3 shl 3) | 2 = 0x1A)
        writeStringField(bos, 0x1A, message.destinationId)

        // Field 4: namespace (length-delimited string, tag = (4 shl 3) | 2 = 0x22)
        writeStringField(bos, 0x22, message.namespace)

        // Field 5: payload_type (varint, tag = (5 shl 3) | 0 = 0x28)
        bos.write(0x28)
        writeVarint(bos, message.payloadType)

        // Field 6: payload_utf8 (length-delimited string, tag = (6 shl 3) | 2 = 0x32)
        writeStringField(bos, 0x32, message.payloadUtf8)

        return bos.toByteArray()
    }

    internal fun decodeFromProtoBytes(bytes: ByteArray): CastV2Message {
        var pos = 0
        var protocolVersion = 0
        var sourceId = ""
        var destinationId = ""
        var namespace = ""
        var payloadType = 0
        var payloadUtf8 = ""

        while (pos < bytes.size) {
            val tagPair = readVarint(bytes, pos)
            val tag = tagPair.first
            pos = tagPair.second
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (wireType) {
                0 -> {
                    val valuePair = readVarint(bytes, pos)
                    pos = valuePair.second
                    when (fieldNumber) {
                        1 -> protocolVersion = valuePair.first
                        5 -> payloadType = valuePair.first
                    }
                }
                2 -> {
                    val lenPair = readVarint(bytes, pos)
                    val len = lenPair.first
                    pos = lenPair.second
                    val end = (pos + len).coerceAtMost(bytes.size)
                    val str = String(bytes, pos, end - pos, StandardCharsets.UTF_8)
                    pos = end
                    when (fieldNumber) {
                        2 -> sourceId = str
                        3 -> destinationId = str
                        4 -> namespace = str
                        6 -> payloadUtf8 = str
                    }
                }
                else -> {
                    // Skip unknown field safely or exit loop
                    break
                }
            }
        }

        return CastV2Message(
            protocolVersion = protocolVersion,
            sourceId = sourceId,
            destinationId = destinationId,
            namespace = namespace,
            payloadType = payloadType,
            payloadUtf8 = payloadUtf8
        )
    }

    private fun writeStringField(bos: ByteArrayOutputStream, tag: Int, value: String) {
        val utf8 = value.toByteArray(StandardCharsets.UTF_8)
        bos.write(tag)
        writeVarint(bos, utf8.size)
        bos.write(utf8)
    }

    private fun writeVarint(bos: ByteArrayOutputStream, value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            bos.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        bos.write(v and 0x7F)
    }

    private fun readVarint(bytes: ByteArray, startPos: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var pos = startPos
        while (pos < bytes.size) {
            val b = bytes[pos].toInt()
            pos++
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return result to pos
    }

    private const val MAX_FRAME_SIZE_BYTES = 256 * 1024
}

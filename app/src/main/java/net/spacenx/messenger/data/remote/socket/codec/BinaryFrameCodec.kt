package net.spacenx.messenger.data.remote.socket.codec

/**
 * 바이너리 프로토콜 프레임 인코딩/디코딩
 *
 * 프레임 포맷 (8-byte header + JSON body):
 *   [0..1] commandCode  - uint16 BE
 *   [2..4] bodyLength   - uint24 BE
 *   [5..7] invokeId     - uint24 BE
 *   [8..N] body         - UTF-8 JSON (bodyLength bytes)
 */
object BinaryFrameCodec {

    const val HEADER_SIZE = 8

    data class BinaryFrame(
        val commandCode: Int,
        val invokeId: Int,
        val body: ByteArray
    ) {
        /** body를 UTF-8 문자열로 변환 */
        fun bodyAsString(): String = body.toString(Charsets.UTF_8)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BinaryFrame) return false
            return commandCode == other.commandCode &&
                    invokeId == other.invokeId &&
                    body.contentEquals(other.body)
        }

        override fun hashCode(): Int {
            var result = commandCode
            result = 31 * result + invokeId
            result = 31 * result + body.contentHashCode()
            return result
        }

        override fun toString(): String =
            "BinaryFrame(cmd=0x${commandCode.toString(16)}, invokeId=$invokeId, bodyLen=${body.size})"
    }

    /**
     * 프레임 인코딩: header + body → ByteArray
     */
    fun encode(commandCode: Int, invokeId: Int, jsonBody: ByteArray): ByteArray {
        val frame = ByteArray(HEADER_SIZE + jsonBody.size)
        // uint16 BE - commandCode
        frame[0] = ((commandCode shr 8) and 0xFF).toByte()
        frame[1] = (commandCode and 0xFF).toByte()
        // uint24 BE - bodyLength
        frame[2] = ((jsonBody.size shr 16) and 0xFF).toByte()
        frame[3] = ((jsonBody.size shr 8) and 0xFF).toByte()
        frame[4] = (jsonBody.size and 0xFF).toByte()
        // uint24 BE - invokeId
        frame[5] = ((invokeId shr 16) and 0xFF).toByte()
        frame[6] = ((invokeId shr 8) and 0xFF).toByte()
        frame[7] = (invokeId and 0xFF).toByte()
        // body
        System.arraycopy(jsonBody, 0, frame, HEADER_SIZE, jsonBody.size)
        return frame
    }

    //java code
    /*public static byte[] encode(int commandCode, int invokeId, byte[] jsonBody) {
        byte[] frame = new byte[HEADER_SIZE + jsonBody.length];
        // uint16 BE - commandCode
        frame[0] = (byte) ((commandCode >> 8) & 0xFF);
        frame[1] = (byte) (commandCode & 0xFF);
        // uint24 BE - bodyLength
        frame[2] = (byte) ((jsonBody.length >> 16) & 0xFF);
        frame[3] = (byte) ((jsonBody.length >> 8) & 0xFF);
        frame[4] = (byte) (jsonBody.length & 0xFF);
        // uint24 BE - invokeId
        frame[5] = (byte) ((invokeId >> 16) & 0xFF);
        frame[6] = (byte) ((invokeId >> 8) & 0xFF);
        frame[7] = (byte) (invokeId & 0xFF);
        // body
        System.arraycopy(jsonBody, 0, frame, HEADER_SIZE, jsonBody.length);
        return frame;
    }*/

    /**
     * 헤더에서 uint16 읽기 (Big Endian)
     */
    fun readUint16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)

    /**
     * 헤더에서 uint24 읽기 (Big Endian)
     */
    fun readUint24(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 16) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                (data[offset + 2].toInt() and 0xFF)

    /**
     * 완전한 프레임 데이터에서 BinaryFrame 디코딩
     */
    fun decode(data: ByteArray): BinaryFrame {
        require(data.size >= HEADER_SIZE) { "Frame too short: ${data.size} < $HEADER_SIZE" }
        val commandCode = readUint16(data, 0)
        val bodyLength = readUint24(data, 2)
        val invokeId = readUint24(data, 5)
        require(data.size >= HEADER_SIZE + bodyLength) {
            "Incomplete frame: have ${data.size}, need ${HEADER_SIZE + bodyLength}"
        }
        val body = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + bodyLength)
        return BinaryFrame(commandCode, invokeId, body)
    }
}

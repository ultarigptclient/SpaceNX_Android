package net.spacenx.messenger.data.remote.socket.codec

import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * SEED 암호화/복호화 래퍼
 * - 16바이트 블록 단위 SEED 암호화 + Base64 인코딩
 * - 원본: javamodule/codec/AmCodec.java
 */
object AmCodec {

    private val DEFAULT_KEY = byteArrayOf(
        0x75, 0x01, 0x65, 0x41, 0x56, 0x73,
        0xAA.toByte(), 0xF0.toByte(),
        0x02, 0x78, 0x84.toByte(), 0x24,
        0x80.toByte(), 0x01, 0x13, 0x01
    )

    private const val BLOCK_SIZE = 16
    private const val TOKEN = "=="

    fun encryptSeed(plainText: String): String {
        return encryptSeed(plainText.toByteArray(), 0, plainText.toByteArray().size)
    }

    fun encryptSeed(plainData: ByteArray, offset: Int, len: Int): String {
        val roundKey = IntArray(32)
        SeedCipher.generateRoundKey(roundKey, DEFAULT_KEY)

        val sb = StringBuilder()
        val pbData = ByteArray(BLOCK_SIZE)
        val outData = ByteArray(BLOCK_SIZE)

        var nIndex = offset
        while (nIndex < len) {
            pbData.fill(0)
            outData.fill(0)
            val copyLen = minOf(BLOCK_SIZE, len - nIndex)
            System.arraycopy(plainData, nIndex, pbData, 0, copyLen)
            SeedCipher.encrypt(pbData, roundKey, outData)
            sb.append(Base64.encodeToString(outData, Base64.NO_WRAP))
            nIndex += BLOCK_SIZE
        }

        return sb.toString()
    }

    fun decryptSeed(cipherText: String): String? {
        val roundKey = IntArray(32)
        SeedCipher.generateRoundKey(roundKey, DEFAULT_KEY)

        val outData = ByteArray(BLOCK_SIZE)
        val parts = cipherText.split(TOKEN)

        return try {
            val baos = ByteArrayOutputStream()
            for (part in parts) {
                if (part.isEmpty()) break
                val temp = part + TOKEN
                val decoded = Base64.decode(temp.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
                SeedCipher.decrypt(decoded, roundKey, outData)
                baos.write(outData)
            }
            // trailing null 바이트 제거 후 UTF-8 변환 (블록 패딩 정리)
            val bytes = baos.toByteArray()
            var end = bytes.size
            while (end > 0 && bytes[end - 1] == 0.toByte()) end--
            String(bytes, 0, end, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun decryptSeedToBytes(cipherText: String): ByteArray? {
        val roundKey = IntArray(32)
        SeedCipher.generateRoundKey(roundKey, DEFAULT_KEY)

        val outData = ByteArray(BLOCK_SIZE)
        val parts = cipherText.split(TOKEN)

        return try {
            val baos = ByteArrayOutputStream()
            for (part in parts) {
                if (part.isEmpty()) break
                val temp = part + TOKEN
                val decoded = Base64.decode(temp.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
                SeedCipher.decrypt(decoded, roundKey, outData)
                baos.write(outData)
            }
            baos.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
}

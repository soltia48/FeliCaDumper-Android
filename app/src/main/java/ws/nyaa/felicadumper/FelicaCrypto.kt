package ws.nyaa.felicadumper

import ws.nyaa.felicadumper.felica_standard.FelicaProtocolException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val DES_BLOCK_SIZE = 8
private const val KEY_LENGTH_BYTES = 8
private val secureRandom = SecureRandom()

data class AuthenticationContext(
    val l: ByteArray,
    val alpha: ByteArray,
    val beta: ByteArray,
) {
    companion object {
        fun create(
            idm: ByteArray,
            groupServiceKey: ByteArray,
            userServiceKey: ByteArray
        ): AuthenticationContext {
            val l = xorBlocks(groupServiceKey, idm)
            val alpha = encryptDesBlock(userServiceKey, l)
            val beta = encryptDesBlock(l, alpha)
            return AuthenticationContext(l = l, alpha = alpha, beta = beta)
        }
    }

    fun encryptChallenge1a(random1: ByteArray): ByteArray = encrypt3DesBlock(random1, alpha, l)

    fun verifyChallenge1b(random1: ByteArray, challenge1b: ByteArray): Boolean {
        return encrypt3DesBlock(random1, l, beta).contentEquals(challenge1b)
    }

    fun decryptChallenge2a(challenge2a: ByteArray): ByteArray =
        decrypt3DesBlock(challenge2a, l, beta)

    fun encryptChallenge2b(random2: ByteArray): ByteArray = encrypt3DesBlock(random2, alpha, l)
}

fun requireKeyLength(bytes: ByteArray, label: String): ByteArray {
    if (bytes.size != KEY_LENGTH_BYTES) {
        throw FelicaProtocolException("Invalid $label length: expected 8 bytes, got ${bytes.size}")
    }
    return bytes.copyOf()
}

fun randomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    secureRandom.nextBytes(bytes)
    return bytes
}

private fun xorBlocks(a: ByteArray, b: ByteArray): ByteArray {
    if (a.size != DES_BLOCK_SIZE || b.size != DES_BLOCK_SIZE) {
        throw FelicaProtocolException("xorBlocks requires 8-byte inputs")
    }
    val out = ByteArray(DES_BLOCK_SIZE)
    for (i in 0 until DES_BLOCK_SIZE) {
        out[i] = ((a[i].toInt() and 0xFF) xor (b[i].toInt() and 0xFF)).toByte()
    }
    return out
}

fun encryptDesBlock(data: ByteArray, key: ByteArray): ByteArray {
    if (data.size != DES_BLOCK_SIZE || key.size != DES_BLOCK_SIZE) {
        throw FelicaProtocolException("DES block encryption requires 8-byte data and key")
    }
    val cipher = Cipher.getInstance("DES/ECB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
    return cipher.doFinal(data)
}

fun decryptDesBlock(data: ByteArray, key: ByteArray): ByteArray {
    if (data.size != DES_BLOCK_SIZE || key.size != DES_BLOCK_SIZE) {
        throw FelicaProtocolException("DES block decryption requires 8-byte data and key")
    }
    val cipher = Cipher.getInstance("DES/ECB/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "DES"))
    return cipher.doFinal(data)
}

fun encrypt3DesBlock(data: ByteArray, key1: ByteArray, key2: ByteArray): ByteArray {
    if (data.size != DES_BLOCK_SIZE || key1.size != DES_BLOCK_SIZE || key2.size != DES_BLOCK_SIZE) {
        throw FelicaProtocolException("3DES block encryption requires 8-byte data and keys")
    }
    val tripleKey = ByteArray(24)
    key1.copyInto(tripleKey, 0)
    key2.copyInto(tripleKey, 8)
    key1.copyInto(tripleKey, 16)
    val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(tripleKey, "DESede"))
    return cipher.doFinal(data)
}

fun decrypt3DesBlock(data: ByteArray, key1: ByteArray, key2: ByteArray): ByteArray {
    if (data.size != DES_BLOCK_SIZE || key1.size != DES_BLOCK_SIZE || key2.size != DES_BLOCK_SIZE) {
        throw FelicaProtocolException("3DES block decryption requires 8-byte data and keys")
    }
    val tripleKey = ByteArray(24)
    key1.copyInto(tripleKey, 0)
    key2.copyInto(tripleKey, 8)
    key1.copyInto(tripleKey, 16)
    val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(tripleKey, "DESede"))
    return cipher.doFinal(data)
}

fun encryptDesCbcZeroIv(data: ByteArray, key: ByteArray): ByteArray {
    if (data.size % DES_BLOCK_SIZE != 0) {
        throw FelicaProtocolException("Secure command payload length must be multiple of 8 bytes")
    }
    val cipher = Cipher.getInstance("DES/CBC/NoPadding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        SecretKeySpec(requireKeyLength(key, "DES CBC key"), "DES"),
        IvParameterSpec(ByteArray(DES_BLOCK_SIZE)),
    )
    return cipher.doFinal(data)
}

fun decryptDesCbcZeroIv(data: ByteArray, key: ByteArray): ByteArray {
    if (data.size % DES_BLOCK_SIZE != 0) {
        throw FelicaProtocolException("Encrypted payload length must be multiple of 8 bytes")
    }
    val cipher = Cipher.getInstance("DES/CBC/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(requireKeyLength(key, "DES CBC key"), "DES"),
        IvParameterSpec(ByteArray(DES_BLOCK_SIZE)),
    )
    return cipher.doFinal(data)
}

fun padToDesBlockSize(data: ByteArray): ByteArray {
    val remainder = data.size % DES_BLOCK_SIZE
    if (remainder == 0) {
        return data
    }
    val padLength = DES_BLOCK_SIZE - remainder
    val output = ByteArray(data.size + padLength)
    data.copyInto(output)
    for (i in data.size until output.size) {
        output[i] = padLength.toByte()
    }
    return output
}

fun calculateCommandMac(commandCode: Int, payload: ByteArray): ByteArray {
    if (payload.size % DES_BLOCK_SIZE != 0) {
        throw FelicaProtocolException("Secure command payload must be multiple of 8 bytes")
    }
    val totalLength = 2 + payload.size + DES_BLOCK_SIZE
    if (totalLength > 0xFF) {
        throw FelicaProtocolException("Secure command payload exceeds maximum frame length")
    }

    var mac = ByteArray(DES_BLOCK_SIZE)
    mac[0] = totalLength.toByte()
    mac[1] = (commandCode and 0xFF).toByte()

    for (offset in payload.indices step DES_BLOCK_SIZE) {
        val block = payload.copyOfRange(offset, offset + DES_BLOCK_SIZE)
        mac = encryptDesBlock(mac, block)
    }
    return mac
}

fun checkPacketMac(data: ByteArray, expectedResponseCode: Int): Boolean {
    if (data.size < DES_BLOCK_SIZE * 2 || data.size % DES_BLOCK_SIZE != 0) {
        return false
    }

    val payload = data.copyOfRange(0, data.size - DES_BLOCK_SIZE)
    if (payload.isEmpty()) {
        return false
    }
    var current = data.copyOfRange(data.size - DES_BLOCK_SIZE, data.size)
    for (offset in (payload.size - DES_BLOCK_SIZE) downTo 0 step DES_BLOCK_SIZE) {
        val block = payload.copyOfRange(offset, offset + DES_BLOCK_SIZE)
        current = decryptDesBlock(current, block)
    }
    return (current[0].toInt() and 0xFF) == ((data.size + 2) and 0xFF) &&
            (current[1].toInt() and 0xFF) == (expectedResponseCode and 0xFF)
}

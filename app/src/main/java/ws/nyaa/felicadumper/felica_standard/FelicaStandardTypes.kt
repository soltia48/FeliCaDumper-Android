package ws.nyaa.felicadumper.felica_standard

import ws.nyaa.felicadumper.AreaNode

data class MutualAuthenticationResult(
    val issueId: ByteArray,
    val issueParameter: ByteArray,
    val session: SecureSession,
)

data class SecureSession(
    var transactionNumber: Int,
    val transactionId: ByteArray,
    val transactionKey: ByteArray,
)

data class SecureResponse(
    val transactionNumber: Int,
    val transactionId: ByteArray,
    val payload: ByteArray,
)

data class AreaCollectionResult(
    val area: AreaNode,
    val nextIndex: Int,
)

data class PollingResult(
    val idm: ByteArray,
    val pmm: ByteArray,
)

sealed interface SearchServiceResult {
    data class Service(val serviceCodeRaw: Int) : SearchServiceResult
    data class Area(val areaCode: Int, val endServiceCode: Int) : SearchServiceResult
}

data class BlockListElement(
    val blockNumber: Int,
    val serviceCodeListIndex: Int,
    val accessMode: Int,
) {
    fun pack(): ByteArray {
        return if (blockNumber in 0..0xFF) {
            byteArrayOf(
                (0x80 or ((accessMode and 0x07) shl 4) or (serviceCodeListIndex and 0x0F)).toByte(),
                blockNumber.toByte(),
            )
        } else {
            byteArrayOf(
                (((accessMode and 0x07) shl 4) or (serviceCodeListIndex and 0x0F)).toByte(),
                (blockNumber and 0xFF).toByte(),
                ((blockNumber ushr 8) and 0xFF).toByte(),
            )
        }
    }
}

package ws.nyaa.felicadumper.felica_standard

import ws.nyaa.felicadumper.KeyVersionSummary
import ws.nyaa.felicadumper.checkPacketMac

internal fun parseAuthentication1Response(response: ByteArray): Pair<ByteArray, ByteArray> {
    fsEnsureMinLength(response, 26, "Authentication1 response too short")
    return response.copyOfRange(10, 18) to response.copyOfRange(18, 26)
}

internal fun parseAuthentication2Response(response: ByteArray): ByteArray {
    fsEnsureMinLength(response, 10, "Authentication2 response too short")
    return response.copyOfRange(2, response.size)
}

internal fun parseSecureReadPayload(decryptedPayload: ByteArray): List<ByteArray> {
    if (decryptedPayload.size < 2) {
        throw FelicaProtocolException("Encrypted read response shorter than status flags")
    }
    val statusFlag1 = fsUByte(decryptedPayload[0])
    val statusFlag2 = fsUByte(decryptedPayload[1])
    if (statusFlag1 != 0) {
        throw FelicaStatusException("Read", statusFlag1, statusFlag2)
    }
    if (decryptedPayload.size < 3) {
        throw FelicaProtocolException("Encrypted read response shorter than block count")
    }

    val blockCount = fsUByte(decryptedPayload[2])
    if (blockCount == 0) {
        return emptyList()
    }

    val expectedLength = 3 + blockCount * FS_BLOCK_SIZE
    fsEnsureMinLength(
        decryptedPayload,
        expectedLength,
        "Encrypted read response truncated before block data",
    )
    return buildList(blockCount) {
        for (index in 0 until blockCount) {
            val start = 3 + index * FS_BLOCK_SIZE
            add(decryptedPayload.copyOfRange(start, start + FS_BLOCK_SIZE))
        }
    }
}

internal fun parseSecureResponse(
    data: ByteArray,
    expectedResponseCode: Int,
): SecureResponse {
    if (data.size < FS_DES_BLOCK_SIZE * 2) {
        throw FelicaProtocolException("Secure response shorter than minimum encrypted payload")
    }
    if (!checkPacketMac(data, expectedResponseCode)) {
        throw FelicaProtocolException("Secure response MAC verification failed")
    }
    if (data.size < 8) {
        throw FelicaProtocolException("Secure response shorter than transaction header")
    }

    return SecureResponse(
        transactionNumber = fsLeU16(data, 0),
        transactionId = data.copyOfRange(2, 8),
        payload = data.copyOfRange(8, data.size),
    )
}

internal fun parseRequestServiceV2Response(response: ByteArray): List<KeyVersionSummary> {
    fsEnsureMinLength(response, 14, "Request Service v2 response too short")
    val statusFlag1 = fsUByte(response[10])
    val statusFlag2 = fsUByte(response[11])
    if (statusFlag1 != 0) {
        throw FelicaStatusException("Request Service v2", statusFlag1, statusFlag2)
    }

    val cryptoId = fsUByte(response[12])
    val count = fsUByte(response[13])
    if (count == 0 || count > FS_MAX_SERVICE_CODES_PER_REQUEST) {
        throw FelicaProtocolException("Request Service v2 node count out of range: $count")
    }

    val payloadOffset = 14
    return if (cryptoId == 0x41 || cryptoId == 0x43) {
        val expectedLength = payloadOffset + count * 4
        fsEnsureMinLength(response, expectedLength, "Request Service v2 dual key list truncated")
        buildList(count) {
            for (index in 0 until count) {
                val aesOffset = payloadOffset + index * 2
                val desOffset = payloadOffset + count * 2 + index * 2
                val aesVersion = fsLeU16(response, aesOffset)
                val desVersion = fsLeU16(response, desOffset)
                add(
                    KeyVersionSummary(
                        aesKeyVersionHex = fsHexU16(aesVersion),
                        desKeyVersionHex = fsHexU16(desVersion),
                    )
                )
            }
        }
    } else {
        val expectedLength = payloadOffset + count * 2
        fsEnsureMinLength(response, expectedLength, "Request Service v2 key list truncated")
        buildList(count) {
            for (index in 0 until count) {
                val offset = payloadOffset + index * 2
                val desVersion = fsLeU16(response, offset)
                add(
                    KeyVersionSummary(
                        aesKeyVersionHex = null,
                        desKeyVersionHex = fsHexU16(desVersion),
                    )
                )
            }
        }
    }
}

internal fun parseRequestServiceResponse(response: ByteArray): List<KeyVersionSummary> {
    fsEnsureMinLength(response, 11, "Request Service response too short")
    val count = fsUByte(response[10])
    if (count == 0 || count > FS_MAX_SERVICE_CODES_PER_REQUEST) {
        throw FelicaProtocolException("Request Service node count out of range: $count")
    }

    val expectedLength = 11 + count * 2
    fsEnsureMinLength(response, expectedLength, "Request Service key list truncated")

    return buildList(count) {
        for (index in 0 until count) {
            val version = fsLeU16(response, 11 + index * 2)
            add(
                KeyVersionSummary(
                    aesKeyVersionHex = null,
                    desKeyVersionHex = fsHexU16(version),
                )
            )
        }
    }
}

internal fun parseReadWithoutEncryptionResponse(response: ByteArray): List<ByteArray> {
    fsEnsureMinLength(response, 12, "Read Without Encryption response too short")
    val statusFlag1 = fsUByte(response[10])
    val statusFlag2 = fsUByte(response[11])
    if (statusFlag1 != 0) {
        throw FelicaStatusException("Read Without Encryption", statusFlag1, statusFlag2)
    }

    fsEnsureMinLength(response, 13, "Read Without Encryption missing block count")
    val blockCount = fsUByte(response[12])
    if (blockCount == 0) {
        return emptyList()
    }

    val expectedLength = 13 + blockCount * FS_BLOCK_SIZE
    fsEnsureMinLength(response, expectedLength, "Read Without Encryption block payload truncated")

    return buildList(blockCount) {
        for (index in 0 until blockCount) {
            val start = 13 + index * FS_BLOCK_SIZE
            add(response.copyOfRange(start, start + FS_BLOCK_SIZE))
        }
    }
}

internal fun parseSearchServiceCodeResponse(response: ByteArray): SearchServiceResult? {
    fsEnsureMinLength(response, 12, "Search Service Code response too short")
    val payloadSize = response.size - 10

    return when (payloadSize) {
        2 -> {
            val first = fsUByte(response[10])
            val second = fsUByte(response[11])
            if (first == 0xFF && second == 0xFF) {
                null
            } else {
                SearchServiceResult.Service(fsLeU16(response, 10))
            }
        }

        4 -> SearchServiceResult.Area(
            areaCode = fsLeU16(response, 10),
            endServiceCode = fsLeU16(response, 12),
        )

        else -> throw FelicaProtocolException(
            "Search Service Code response payload must be 2 or 4 bytes, got $payloadSize"
        )
    }
}

internal fun parseRequestSystemCodeResponse(response: ByteArray): List<Int> {
    fsEnsureMinLength(response, 12, "Request System Code response too short")
    val count = fsUByte(response[10])
    if (count == 0) {
        return emptyList()
    }

    val expectedLength = 11 + count * 2
    fsEnsureMinLength(response, expectedLength, "Request System Code list truncated")

    return buildList(count) {
        for (index in 0 until count) {
            val offset = 11 + index * 2
            add(fsBeU16(response, offset))
        }
    }
}

internal fun parsePollingResponse(response: ByteArray): PollingResult {
    fsEnsureMinLength(response, 18, "Polling response too short")
    return PollingResult(
        idm = response.copyOfRange(2, 10),
        pmm = response.copyOfRange(10, 18),
    )
}

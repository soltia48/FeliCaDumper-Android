package ws.nyaa.felicadumper.felica_standard

import android.nfc.tech.NfcF
import java.io.ByteArrayOutputStream
import java.util.Locale

internal fun frameWithLengthPrefix(payload: ByteArray): ByteArray {
    val frame = ByteArray(payload.size + 1)
    frame[0] = (payload.size + 1).toByte()
    payload.copyInto(frame, destinationOffset = 1)
    return frame
}

internal fun transceiveWithCode(
    nfcF: NfcF,
    payload: ByteArray,
    expectedResponseCode: Int,
    commandName: String,
): ByteArray {
    val response = transceive(nfcF, payload)
    val responseCode = fsUByte(response[1])
    if (responseCode != expectedResponseCode) {
        throw FelicaProtocolException(
            "$commandName unexpected response code: ${fsHexU8(responseCode)}"
        )
    }
    return response
}

internal fun transceive(nfcF: NfcF, payload: ByteArray): ByteArray {
    val rawResponse = nfcF.transceive(frameWithLengthPrefix(payload))
    fsEnsureMinLength(rawResponse, 2, "FeliCa response too short")

    val declaredLength = fsUByte(rawResponse[0])
    if (declaredLength < 2) {
        throw FelicaProtocolException("Invalid FeliCa response length byte: $declaredLength")
    }
    if (declaredLength > rawResponse.size) {
        throw FelicaProtocolException(
            "Truncated FeliCa response: declared=$declaredLength actual=${rawResponse.size}"
        )
    }

    return if (declaredLength == rawResponse.size) {
        rawResponse
    } else {
        rawResponse.copyOf(declaredLength)
    }
}

internal fun buildAuthentication1Command(
    idm: ByteArray,
    areas: List<Int>,
    services: List<Int>,
    challenge1a: ByteArray,
): ByteArray {
    val payload = ByteArrayOutputStream()
    payload.write(FS_AUTHENTICATION1_COMMAND_CODE)
    payload.write(idm)
    payload.write(areas.size)
    for (area in areas) {
        fsWriteLe16(payload, area)
    }
    payload.write(services.size)
    for (service in services) {
        fsWriteLe16(payload, service)
    }
    payload.write(challenge1a)
    return payload.toByteArray()
}

internal fun buildAuthentication2Command(
    idm: ByteArray,
    challenge2b: ByteArray,
): ByteArray {
    val payload = ByteArrayOutputStream()
    payload.write(FS_AUTHENTICATION2_COMMAND_CODE)
    payload.write(idm)
    payload.write(challenge2b)
    return payload.toByteArray()
}

internal fun buildSecureReadPayload(blockList: List<BlockListElement>): ByteArray {
    val payload = ByteArrayOutputStream()
    payload.write(blockList.size)
    for (block in blockList) {
        payload.write(block.pack())
    }
    return payload.toByteArray()
}

internal fun buildSecureCommand(commandCode: Int, encryptedPayload: ByteArray): ByteArray {
    val payload = ByteArrayOutputStream()
    payload.write(commandCode)
    payload.write(encryptedPayload)
    return payload.toByteArray()
}

internal fun buildRequestServiceV2Command(
    idm: ByteArray,
    serviceCodes: List<Int>,
): ByteArray {
    val payload = ByteArrayOutputStream()
    payload.write(FS_REQUEST_SERVICE_V2_COMMAND_CODE)
    payload.write(idm)
    payload.write(serviceCodes.size)
    for (serviceCode in serviceCodes) {
        fsWriteLe16(payload, serviceCode)
    }
    return payload.toByteArray()
}

internal fun buildRequestServiceCommand(
    idm: ByteArray,
    serviceCodes: List<Int>,
): ByteArray {
    val payload = ByteArrayOutputStream()
    payload.write(FS_REQUEST_SERVICE_COMMAND_CODE)
    payload.write(idm)
    payload.write(serviceCodes.size)
    for (serviceCode in serviceCodes) {
        fsWriteLe16(payload, serviceCode)
    }
    return payload.toByteArray()
}

internal fun buildReadWithoutEncryptionCommand(
    idm: ByteArray,
    serviceCodes: List<Int>,
    blockList: List<BlockListElement>,
): ByteArray {
    val payload = ByteArrayOutputStream()
    payload.write(FS_READ_WITHOUT_ENCRYPTION_COMMAND_CODE)
    payload.write(idm)
    payload.write(serviceCodes.size)
    for (serviceCode in serviceCodes) {
        fsWriteLe16(payload, serviceCode)
    }
    payload.write(blockList.size)
    for (block in blockList) {
        payload.write(block.pack())
    }
    return payload.toByteArray()
}

internal fun buildSearchServiceCodeCommand(idm: ByteArray, serviceIndex: Int): ByteArray {
    val payload = ByteArrayOutputStream()
    payload.write(FS_SEARCH_SERVICE_CODE_COMMAND_CODE)
    payload.write(idm)
    fsWriteLe16(payload, serviceIndex)
    return payload.toByteArray()
}

internal fun buildRequestSystemCodeCommand(idm: ByteArray): ByteArray {
    val payload = ByteArrayOutputStream()
    payload.write(FS_REQUEST_SYSTEM_CODE_COMMAND_CODE)
    payload.write(idm)
    return payload.toByteArray()
}

internal fun buildPollingCommand(systemCode: Int): ByteArray {
    val payload = ByteArrayOutputStream()
    payload.write(FS_POLLING_COMMAND_CODE)
    fsWriteBe16(payload, systemCode)
    payload.write(0x00)
    payload.write(0x00)
    return payload.toByteArray()
}

internal fun fsWriteLe16(output: ByteArrayOutputStream, value: Int) {
    output.write(value and 0xFF)
    output.write((value ushr 8) and 0xFF)
}

internal fun fsWriteBe16(output: ByteArrayOutputStream, value: Int) {
    output.write((value ushr 8) and 0xFF)
    output.write(value and 0xFF)
}

internal fun fsBeU16(bytes: ByteArray, offset: Int): Int {
    return (fsUByte(bytes[offset]) shl 8) or fsUByte(bytes[offset + 1])
}

internal fun fsLeU16(bytes: ByteArray, offset: Int): Int {
    return fsUByte(bytes[offset]) or (fsUByte(bytes[offset + 1]) shl 8)
}

internal fun fsUByte(value: Byte): Int = value.toInt() and 0xFF

internal fun fsEnsureMinLength(data: ByteArray, expected: Int, label: String) {
    if (data.size < expected) {
        throw FelicaProtocolException("$label (expected >= $expected bytes, got ${data.size})")
    }
}

internal fun fsHexU16(value: Int): String = String.format(Locale.US, "0x%04X", value and 0xFFFF)

internal fun fsHexU8(value: Int): String = String.format(Locale.US, "0x%02X", value and 0xFF)

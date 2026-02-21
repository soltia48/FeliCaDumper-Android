package ws.nyaa.felicadumper.felica_standard

import android.nfc.tech.NfcF
import ws.nyaa.felicadumper.KeyVersionSummary

internal fun requestServiceV2(
    nfcF: NfcF,
    idm: ByteArray,
    serviceCodes: List<Int>,
): List<KeyVersionSummary> {
    if (serviceCodes.isEmpty()) {
        return emptyList()
    }

    val response = transceiveWithCode(
        nfcF = nfcF,
        payload = buildRequestServiceV2Command(idm, serviceCodes),
        expectedResponseCode = FS_REQUEST_SERVICE_V2_RESPONSE_CODE,
        commandName = "Request Service v2",
    )
    return parseRequestServiceV2Response(response)
}

internal fun requestService(
    nfcF: NfcF,
    idm: ByteArray,
    serviceCodes: List<Int>,
): List<KeyVersionSummary> {
    if (serviceCodes.isEmpty()) {
        return emptyList()
    }

    val response = transceiveWithCode(
        nfcF = nfcF,
        payload = buildRequestServiceCommand(idm, serviceCodes),
        expectedResponseCode = FS_REQUEST_SERVICE_RESPONSE_CODE,
        commandName = "Request Service",
    )
    return parseRequestServiceResponse(response)
}

internal fun readWithoutEncryption(
    nfcF: NfcF,
    idm: ByteArray,
    serviceCodes: List<Int>,
    blockList: List<BlockListElement>,
): List<ByteArray> {
    if (serviceCodes.isEmpty()) {
        throw FelicaProtocolException("Read Without Encryption requires at least one service code")
    }
    if (blockList.isEmpty()) {
        throw FelicaProtocolException("Read Without Encryption requires at least one block")
    }

    val response = transceiveWithCode(
        nfcF = nfcF,
        payload = buildReadWithoutEncryptionCommand(idm, serviceCodes, blockList),
        expectedResponseCode = FS_READ_WITHOUT_ENCRYPTION_RESPONSE_CODE,
        commandName = "Read Without Encryption",
    )
    return parseReadWithoutEncryptionResponse(response)
}

internal fun searchServiceCode(
    nfcF: NfcF,
    idm: ByteArray,
    serviceIndex: Int,
): SearchServiceResult? {
    val response = transceiveWithCode(
        nfcF = nfcF,
        payload = buildSearchServiceCodeCommand(idm, serviceIndex),
        expectedResponseCode = FS_SEARCH_SERVICE_CODE_RESPONSE_CODE,
        commandName = "Search Service Code",
    )
    return parseSearchServiceCodeResponse(response)
}

internal fun requestSystemCode(nfcF: NfcF, idm: ByteArray): List<Int> {
    val response = transceiveWithCode(
        nfcF = nfcF,
        payload = buildRequestSystemCodeCommand(idm),
        expectedResponseCode = FS_REQUEST_SYSTEM_CODE_RESPONSE_CODE,
        commandName = "Request System Code",
    )
    return parseRequestSystemCodeResponse(response)
}

internal fun polling(nfcF: NfcF, systemCode: Int): PollingResult {
    val response = transceiveWithCode(
        nfcF = nfcF,
        payload = buildPollingCommand(systemCode),
        expectedResponseCode = FS_POLLING_RESPONSE_CODE,
        commandName = "Polling",
    )
    return parsePollingResponse(response)
}

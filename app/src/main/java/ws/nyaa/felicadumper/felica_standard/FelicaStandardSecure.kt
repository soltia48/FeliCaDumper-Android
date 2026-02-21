package ws.nyaa.felicadumper.felica_standard

import android.nfc.tech.NfcF
import ws.nyaa.felicadumper.AuthenticationContext
import ws.nyaa.felicadumper.calculateCommandMac
import ws.nyaa.felicadumper.checkPacketMac
import ws.nyaa.felicadumper.decryptDesCbcZeroIv
import ws.nyaa.felicadumper.encryptDesCbcZeroIv
import ws.nyaa.felicadumper.padToDesBlockSize
import ws.nyaa.felicadumper.randomBytes
import ws.nyaa.felicadumper.requireKeyLength
import java.io.ByteArrayOutputStream

internal fun mutualAuthentication(
    nfcF: NfcF,
    idm: ByteArray,
    areas: List<Int>,
    services: List<Int>,
    groupServiceKey: ByteArray,
    userServiceKey: ByteArray,
): MutualAuthenticationResult {
    if (areas.isEmpty() && services.isEmpty()) {
        throw FelicaProtocolException("Mutual authentication requires at least one area or service")
    }

    val idmBytes = requireKeyLength(idm, "IDm")
    val random1 = randomBytes(FS_DES_BLOCK_SIZE)
    val authContext = AuthenticationContext.create(
        idm = idmBytes,
        groupServiceKey = requireKeyLength(groupServiceKey, "group service key"),
        userServiceKey = requireKeyLength(userServiceKey, "user service key"),
    )

    val challenge1a = authContext.encryptChallenge1a(random1)
    val (challenge1b, challenge2a) = authentication1(nfcF, idmBytes, areas, services, challenge1a)
    if (!authContext.verifyChallenge1b(random1, challenge1b)) {
        throw FelicaProtocolException("Authentication1 verification failed")
    }

    val random2 = authContext.decryptChallenge2a(challenge2a)
    val challenge2b = authContext.encryptChallenge2b(random2)
    val auth2EncryptedPayload = authentication2(nfcF, idmBytes, challenge2b)
    val auth2Payload = decryptAuthentication2Payload(auth2EncryptedPayload, random2)
    if (auth2Payload.size < 24) {
        throw FelicaProtocolException("Authentication2 response payload too short")
    }

    val transactionNumber = fsLeU16(auth2Payload, 0)
    val transactionId = auth2Payload.copyOfRange(2, 8)
    val expectedTransactionId = random1.copyOfRange(2, 8)
    if (!transactionId.contentEquals(expectedTransactionId)) {
        throw FelicaProtocolException("Authentication2 transaction ID mismatch")
    }

    return MutualAuthenticationResult(
        issueId = auth2Payload.copyOfRange(8, 16),
        issueParameter = auth2Payload.copyOfRange(16, 24),
        session = SecureSession(
            transactionNumber = transactionNumber,
            transactionId = transactionId,
            transactionKey = random2,
        ),
    )
}

internal fun authentication1(
    nfcF: NfcF,
    idm: ByteArray,
    areas: List<Int>,
    services: List<Int>,
    challenge1a: ByteArray,
): Pair<ByteArray, ByteArray> {
    val response = transceiveWithCode(
        nfcF = nfcF,
        payload = buildAuthentication1Command(idm, areas, services, challenge1a),
        expectedResponseCode = FS_AUTHENTICATION1_RESPONSE_CODE,
        commandName = "Authentication1",
    )
    return parseAuthentication1Response(response)
}

internal fun authentication2(
    nfcF: NfcF,
    idm: ByteArray,
    challenge2b: ByteArray,
): ByteArray {
    val response = transceiveWithCode(
        nfcF = nfcF,
        payload = buildAuthentication2Command(idm, challenge2b),
        expectedResponseCode = FS_AUTHENTICATION2_RESPONSE_CODE,
        commandName = "Authentication2",
    )
    return parseAuthentication2Response(response)
}

internal fun decryptAuthentication2Payload(
    encryptedPayload: ByteArray,
    sessionKey: ByteArray,
): ByteArray {
    val plaintext = decryptDesCbcZeroIv(encryptedPayload, sessionKey)
    if (!checkPacketMac(plaintext, FS_AUTHENTICATION2_RESPONSE_CODE)) {
        throw FelicaProtocolException("Authentication2 response MAC verification failed")
    }
    if (plaintext.size < FS_DES_BLOCK_SIZE) {
        throw FelicaProtocolException("Authentication2 response payload too short")
    }
    return plaintext.copyOfRange(0, plaintext.size - FS_DES_BLOCK_SIZE)
}

internal fun secureRead(
    nfcF: NfcF,
    session: SecureSession,
    blockList: List<BlockListElement>,
): List<ByteArray> {
    val decryptedPayload = encryptedCommandExchange(
        nfcF = nfcF,
        commandCode = FS_READ_COMMAND_CODE,
        commandPayload = buildSecureReadPayload(blockList),
        session = session,
    )
    return parseSecureReadPayload(decryptedPayload)
}

internal fun encryptedCommandExchange(
    nfcF: NfcF,
    commandCode: Int,
    commandPayload: ByteArray,
    session: SecureSession,
): ByteArray {
    if (session.transactionNumber == 0xFFFF) {
        throw FelicaProtocolException("Transaction number overflow during secure session")
    }

    session.transactionNumber += 1
    val payload = ByteArrayOutputStream()
    fsWriteLe16(payload, session.transactionNumber)
    payload.write(session.transactionId)
    payload.write(commandPayload)

    val paddedPayload = padToDesBlockSize(payload.toByteArray())
    val mac = calculateCommandMac(commandCode, paddedPayload)
    val commandData = ByteArrayOutputStream()
    commandData.write(paddedPayload)
    commandData.write(mac)

    val encrypted = encryptDesCbcZeroIv(commandData.toByteArray(), session.transactionKey)
    val response = transceiveWithCode(
        nfcF = nfcF,
        payload = buildSecureCommand(commandCode, encrypted),
        expectedResponseCode = (commandCode + 1) and 0xFF,
        commandName = "Secure command ${fsHexU8(commandCode)}",
    )
    val encryptedResponsePayload = response.copyOfRange(2, response.size)
    val decryptedResponsePayload =
        decryptDesCbcZeroIv(encryptedResponsePayload, session.transactionKey)
    val secureResponse = parseSecureResponse(
        data = decryptedResponsePayload,
        expectedResponseCode = (commandCode + 1) and 0xFF,
    )

    if (secureResponse.transactionNumber <= session.transactionNumber) {
        throw FelicaProtocolException("Secure response transaction number did not advance")
    }
    if (!secureResponse.transactionId.contentEquals(session.transactionId)) {
        throw FelicaProtocolException("Secure response transaction ID mismatch")
    }
    session.transactionNumber = secureResponse.transactionNumber

    return secureResponse.payload
}

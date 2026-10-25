package ws.nyaa.felicadumper

import android.nfc.Tag
import android.nfc.tech.NfcF
import android.os.Build
import ws.nyaa.felicadumper.felica_standard.AreaCollectionResult
import ws.nyaa.felicadumper.felica_standard.BlockListElement
import ws.nyaa.felicadumper.felica_standard.FelicaProtocolException
import ws.nyaa.felicadumper.felica_standard.FelicaStatusException
import ws.nyaa.felicadumper.felica_standard.SearchServiceResult
import ws.nyaa.felicadumper.felica_standard.mutualAuthentication
import ws.nyaa.felicadumper.felica_standard.polling
import ws.nyaa.felicadumper.felica_standard.readWithoutEncryption
import ws.nyaa.felicadumper.felica_standard.requestService
import ws.nyaa.felicadumper.felica_standard.requestServiceV2
import ws.nyaa.felicadumper.felica_standard.requestSystemCode
import ws.nyaa.felicadumper.felica_standard.searchServiceCode
import ws.nyaa.felicadumper.felica_standard.secureRead
import java.io.File
import java.io.IOException
import java.util.Locale

private const val IDM_LENGTH = 8
private const val MAX_SERVICE_CODES_PER_REQUEST = 0x20
private const val BLOCKS_PER_READ_ATTEMPT = 1
private const val SYSTEM_SERVICE_CODE = 0xFFFF
private const val ALL_SYSTEMS_CODE = 0xFFFF
private const val ROOT_AREA_CODE = 0x0000

object FelicaDumpEngine {
    fun loadKeysFromJsonlFile(file: File): LoadedKeys {
        return FelicaKeyStoreLoader.loadFromJsonlFile(file)
    }

    @Throws(IOException::class)
    fun dump(tag: Tag, keyStore: SystemKeys? = null): DumpOutput {
        val nfcF = NfcF.get(tag) ?: throw IOException("NfcF tech is not available for this tag")
        val warnings = mutableListOf<DumpWarning>()

        try {
            nfcF.connect()
            nfcF.timeout = 1000

            val initialPolling = runCatching { polling(nfcF, ALL_SYSTEMS_CODE) }
                .getOrElse { error ->
                    warnings += DumpWarning(message = "Polling ${hexU16(ALL_SYSTEMS_CODE)} failed: ${error.message}")
                    null
                }

            val systemCodes = discoverSystemCodes(nfcF, initialPolling?.idm, warnings)
            val systems = mutableListOf<SystemSummary>()

            for (systemCode in systemCodes.distinct()) {
                val summary = runCatching { summarizeSystem(nfcF, systemCode, keyStore) }
                    .getOrElse { error ->
                        warnings += DumpWarning(message = "Failed to dump system ${hexU16(systemCode)}: ${error.message}")
                        null
                    }
                if (summary != null) {
                    systems += summary
                }
            }

            return DumpOutput(
                device = DeviceInfo(
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                ),
                systems = systems,
                warnings = warnings,
                generatedAtEpochMs = System.currentTimeMillis(),
            )
        } finally {
            runCatching { nfcF.close() }
        }
    }

    private fun discoverSystemCodes(
        nfcF: NfcF,
        idmFromPolling: ByteArray?,
        warnings: MutableList<DumpWarning>,
    ): List<Int> {
        val idm = idmFromPolling ?: nfcF.tag.id.takeIf { it.size == IDM_LENGTH }
        if (idm != null) {
            val requested = runCatching { requestSystemCode(nfcF, idm) }
                .getOrElse { error ->
                    warnings += DumpWarning(message = "Request System Code failed: ${error.message}")
                    emptyList()
                }
            if (requested.isNotEmpty()) {
                return requested
            }
        }

        val fromTag = parseSystemCodeBytes(nfcF.systemCode)
        if (fromTag != null) {
            return listOf(fromTag)
        }

        warnings += DumpWarning(message = "System code discovery failed; fallback to ${hexU16(ALL_SYSTEMS_CODE)}")
        return listOf(ALL_SYSTEMS_CODE)
    }

    private fun resolveKeysForCard(
        keyStore: SystemKeys,
        systemCode: Int,
        idmHex: String,
    ): Map<Int, ByteArray>? {
        val idmScoped = keyStore[systemCode] ?: return null
        val merged = mutableMapOf<Int, ByteArray>()

        idmScoped[""]?.forEach { (code, key) ->
            merged[code] = key.copyOf()
        }
        idmScoped[idmHex]?.forEach { (code, key) ->
            merged[code] = key.copyOf()
        }

        return merged.takeIf { it.isNotEmpty() }
    }

    private fun summarizeSystem(
        nfcF: NfcF,
        systemCode: Int,
        keyStore: SystemKeys?,
    ): SystemSummary {
        val polling = polling(nfcF, systemCode)
        val idmHex = polling.idm.toHexUpper()
        val warnings = mutableListOf<DumpWarning>()
        val keyRequestCodes = mutableListOf<Int>()
        val seenCodes = mutableSetOf<Int>()

        registerServiceCode(keyRequestCodes, seenCodes, SYSTEM_SERVICE_CODE)

        val (areas, systemServices) = collectSystemAreas(
            nfcF = nfcF,
            idm = polling.idm,
            keyRequestCodes = keyRequestCodes,
            seenCodes = seenCodes,
            warnings = warnings,
        )

        val keyVersions = fetchKeyVersions(
            nfcF = nfcF,
            idm = polling.idm,
            keyRequestCodes = keyRequestCodes,
            warnings = warnings,
        )

        val systemKey = keyVersions.remove(SYSTEM_SERVICE_CODE)
        assignSystemKeyVersions(systemServices, keyVersions)
        for (area in areas) {
            assignAreaKeyVersions(area, keyVersions)
        }

        runCatching {
            readPlaintextServices(
                nfcF = nfcF,
                idm = polling.idm,
                areas = areas,
                systemServices = systemServices,
                warnings = warnings,
            )
        }.onFailure { error ->
            warnings += DumpWarning(message = "Reading plaintext blocks failed for ${hexU16(systemCode)}: ${error.message}")
        }

        val cardKeys = keyStore?.let { resolveKeysForCard(it, systemCode, idmHex) }
        var mutualAuthSummary: MutualAuthSummary? = null
        if (cardKeys != null) {
            mutualAuthSummary = readAuthenticatedServices(
                nfcF = nfcF,
                idm = polling.idm,
                areas = areas,
                systemServices = systemServices,
                keys = cardKeys,
                warnings = warnings,
            )
        } else if (keyStore != null) {
            warnings += DumpWarning(message = "No external keys available for ${hexU16(systemCode)} and IDm $idmHex")
        }

        if (keyVersions.isNotEmpty()) {
            val unknownCodes = keyVersions.keys.sorted().joinToString(", ") { hexU16(it) }
            warnings += DumpWarning(message = "Key versions returned for unknown codes: [$unknownCodes]")
        }

        return SystemSummary(
            idm = idmHex,
            pmm = polling.pmm.toHexUpper(),
            idi = mutualAuthSummary?.idi,
            pmi = mutualAuthSummary?.pmi,
            systemCodeHex = hexU16(systemCode),
            systemKey = systemKey,
            systemServices = systemServices,
            areas = areas,
            warnings = warnings,
        )
    }

    private fun collectSystemAreas(
        nfcF: NfcF,
        idm: ByteArray,
        keyRequestCodes: MutableList<Int>,
        seenCodes: MutableSet<Int>,
        warnings: MutableList<DumpWarning>,
    ): Pair<MutableList<AreaNode>, MutableList<ServiceGroupSummary>> {
        val areas = mutableListOf<AreaNode>()
        val systemServices = mutableListOf<ServiceGroupSummary>()
        var index = 0

        loop@ while (true) {
            when (val result = searchServiceCode(nfcF, idm, index)) {
                null -> break@loop
                is SearchServiceResult.Area -> {
                    if (index >= 0xFFFF) {
                        warnings += DumpWarning(
                            node = result.areaCode,
                            message = "Directory index overflow while descending into the area",
                        )
                        break@loop
                    }
                    val childIndex = index + 1
                    val collected = collectArea(
                        nfcF = nfcF,
                        idm = idm,
                        areaCode = result.areaCode,
                        endServiceCode = result.endServiceCode,
                        index = childIndex,
                        keyRequestCodes = keyRequestCodes,
                        seenCodes = seenCodes,
                        warnings = warnings,
                    )
                    areas += collected.area
                    if (collected.nextIndex <= index) {
                        warnings += DumpWarning(message = "Area traversal did not advance at ${hexU16(index)}")
                        break@loop
                    }
                    index = collected.nextIndex
                }

                is SearchServiceResult.Service -> {
                    warnings += DumpWarning(
                        node = result.serviceCodeRaw,
                        message = "Service at index ${hexU16(index)} has no parent area",
                    )
                    registerServiceCode(keyRequestCodes, seenCodes, result.serviceCodeRaw)
                    appendServiceGroup(systemServices, buildServiceSummary(result.serviceCodeRaw))
                    if (index >= 0xFFFF) {
                        warnings += DumpWarning(message = "Directory index overflow while reading system-level services")
                        break@loop
                    }
                    index += 1
                }
            }
        }

        return areas to systemServices
    }

    private fun collectArea(
        nfcF: NfcF,
        idm: ByteArray,
        areaCode: Int,
        endServiceCode: Int,
        index: Int,
        keyRequestCodes: MutableList<Int>,
        seenCodes: MutableSet<Int>,
        warnings: MutableList<DumpWarning>,
    ): AreaCollectionResult {
        registerServiceCode(keyRequestCodes, seenCodes, areaCode)

        val children = mutableListOf<AreaChild>()
        var cursor = index

        loop@ while (true) {
            when (val result = searchServiceCode(nfcF, idm, cursor)) {
                null -> break@loop
                is SearchServiceResult.Service -> {
                    val serviceCodeRaw = result.serviceCodeRaw
                    if (serviceCodeRaw < areaCode || serviceCodeRaw > endServiceCode) {
                        break@loop
                    }

                    registerServiceCode(keyRequestCodes, seenCodes, serviceCodeRaw)
                    appendServiceChild(children, buildServiceSummary(serviceCodeRaw))

                    if (cursor >= 0xFFFF) {
                        warnings += DumpWarning(
                            node = areaCode,
                            message = "Directory index overflow while traversing the area",
                        )
                        break@loop
                    }
                    cursor += 1
                }

                is SearchServiceResult.Area -> {
                    if (result.endServiceCode < result.areaCode) {
                        warnings += DumpWarning(
                            node = result.areaCode,
                            message = "Invalid end code ${hexU16(result.endServiceCode)}",
                        )
                        break@loop
                    }
                    if (result.areaCode < areaCode || result.endServiceCode > endServiceCode) {
                        break@loop
                    }
                    if (cursor >= 0xFFFF) {
                        warnings += DumpWarning(
                            node = result.areaCode,
                            message = "Directory index overflow while descending into the area",
                        )
                        break@loop
                    }

                    val child = collectArea(
                        nfcF = nfcF,
                        idm = idm,
                        areaCode = result.areaCode,
                        endServiceCode = result.endServiceCode,
                        index = cursor + 1,
                        keyRequestCodes = keyRequestCodes,
                        seenCodes = seenCodes,
                        warnings = warnings,
                    )
                    children += AreaChild(kind = "area", area = child.area)
                    if (child.nextIndex <= cursor) {
                        warnings += DumpWarning(message = "Child area traversal did not advance at index ${hexU16(cursor)}")
                        break@loop
                    }
                    cursor = child.nextIndex
                }
            }
        }

        return AreaCollectionResult(
            area = AreaNode(
                areaCodeRaw = areaCode,
                areaCodeHex = hexU16(areaCode),
                endServiceCodeHex = hexU16(endServiceCode),
                keyVersion = null,
                children = children,
            ),
            nextIndex = cursor,
        )
    }

    private fun fetchKeyVersions(
        nfcF: NfcF,
        idm: ByteArray,
        keyRequestCodes: List<Int>,
        warnings: MutableList<DumpWarning>,
    ): MutableMap<Int, KeyVersionSummary> {
        val keyVersions = mutableMapOf<Int, KeyVersionSummary>()
        for (chunk in keyRequestCodes.chunked(MAX_SERVICE_CODES_PER_REQUEST)) {
            try {
                val (summaries, warning) = requestKeyVersions(nfcF, idm, chunk)
                for (i in chunk.indices) {
                    summaries.getOrNull(i)?.let { summary ->
                        keyVersions[chunk[i]] = summary
                    }
                }
                if (warning != null) {
                    warnings += DumpWarning(message = warning)
                }
            } catch (error: Exception) {
                warnings += DumpWarning(message = "Request Service failed: ${error.message}")
            }
        }
        return keyVersions
    }

    private fun requestKeyVersions(
        nfcF: NfcF,
        idm: ByteArray,
        serviceCodes: List<Int>,
    ): Pair<List<KeyVersionSummary>, String?> {
        return try {
            requestServiceV2(nfcF, idm, serviceCodes) to null
        } catch (error: Exception) {
            requestService(nfcF, idm, serviceCodes) to "Request Service v2 failed: ${error.message}"
        }
    }

    private fun readPlaintextServices(
        nfcF: NfcF,
        idm: ByteArray,
        areas: List<AreaNode>,
        systemServices: List<ServiceGroupSummary>,
        warnings: MutableList<DumpWarning>,
    ) {
        val targets = mutableListOf<Int>()
        val seen = mutableSetOf<Int>()
        collectPlaintextServiceCodes(areas, systemServices, targets, seen)

        if (targets.isEmpty()) {
            return
        }

        val readResults = mutableMapOf<Int, List<String>>()
        for (serviceCodeRaw in targets) {
            try {
                val blocks = readServiceBlocks(nfcF, idm, serviceCodeRaw)
                if (blocks.isNotEmpty()) {
                    readResults[serviceCodeRaw] = blocks
                }
            } catch (error: Exception) {
                warnings += DumpWarning(
                    node = serviceCodeRaw,
                    message = "Read Without Encryption failed: ${error.message}",
                )
            }
        }

        assignBlocks(areas, systemServices, readResults, authenticated = false)
    }

    private fun readAuthenticatedServices(
        nfcF: NfcF,
        idm: ByteArray,
        areas: List<AreaNode>,
        systemServices: List<ServiceGroupSummary>,
        keys: Map<Int, ByteArray>,
        warnings: MutableList<DumpWarning>,
    ): MutualAuthSummary? {
        val targets = mutableListOf<AuthReadTarget>()
        val seen = mutableSetOf<Int>()
        collectAuthenticatedServiceCodes(areas, systemServices, targets, seen)
        if (targets.isEmpty()) {
            return null
        }

        val readResults = mutableMapOf<Int, List<String>>()
        var mutualAuthSummary: MutualAuthSummary? = null
        for (target in targets) {
            try {
                val result = readServiceBlocksWithAuth(nfcF, idm, target, keys)
                if (mutualAuthSummary == null) {
                    mutualAuthSummary = result.authSummary
                }
                if (result.blocks.isNotEmpty()) {
                    readResults[target.serviceCodeRaw] = result.blocks
                }
            } catch (error: Exception) {
                warnings += DumpWarning(
                    node = target.serviceCodeRaw,
                    message = "Authenticated Read failed: ${error.message}",
                )
            }
        }

        assignBlocks(areas, systemServices, readResults, authenticated = true)
        return mutualAuthSummary
    }

    private fun collectAuthenticatedServiceCodes(
        areas: List<AreaNode>,
        systemServices: List<ServiceGroupSummary>,
        targets: MutableList<AuthReadTarget>,
        seen: MutableSet<Int>,
    ) {
        for (group in systemServices) {
            collectGroupAuthenticatedTargets(group, emptyList(), targets, seen)
        }

        for (area in areas) {
            val areaPath = mutableListOf(area.areaCodeRaw)
            collectAuthenticatedChildren(area.children, areaPath, targets, seen)
        }
    }

    private fun collectAuthenticatedChildren(
        children: List<AreaChild>,
        areaPath: MutableList<Int>,
        targets: MutableList<AuthReadTarget>,
        seen: MutableSet<Int>,
    ) {
        for (child in children) {
            if (child.area != null) {
                areaPath += child.area.areaCodeRaw
                collectAuthenticatedChildren(child.area.children, areaPath, targets, seen)
                areaPath.removeAt(areaPath.lastIndex)
            }
            if (child.serviceGroup != null) {
                collectGroupAuthenticatedTargets(child.serviceGroup, areaPath, targets, seen)
            }
        }
    }

    private fun collectGroupAuthenticatedTargets(
        group: ServiceGroupSummary,
        areaPath: List<Int>,
        targets: MutableList<AuthReadTarget>,
        seen: MutableSet<Int>,
    ) {
        if (group.blocks != null) {
            return
        }

        for (service in group.services) {
            if (service.attributesRaw and 0x01 == 0 && seen.add(service.serviceCodeRaw)) {
                targets += AuthReadTarget(
                    serviceCodeRaw = service.serviceCodeRaw,
                    areaCodes = areaPath.toList(),
                )
            }
        }
    }

    private fun readServiceBlocksWithAuth(
        nfcF: NfcF,
        idm: ByteArray,
        target: AuthReadTarget,
        keys: Map<Int, ByteArray>,
    ): AuthReadResult {
        val areaCodes = normalizeAreaPathForAuth(target.areaCodes)
        val (groupServiceKey, userServiceKey) =
            deriveServiceKeysForAuth(keys, areaCodes, target.serviceCodeRaw)

        val auth = mutualAuthentication(
            nfcF = nfcF,
            idm = idm,
            areas = areaCodes,
            services = listOf(target.serviceCodeRaw),
            groupServiceKey = groupServiceKey,
            userServiceKey = userServiceKey,
        )

        val blocksHex = mutableListOf<String>()
        var blockNumber = 0
        while (blockNumber <= 0xFFFF) {
            val blockList = listOf(BlockListElement(blockNumber, 0, 0))
            val blocks = try {
                secureRead(nfcF, auth.session, blockList)
            } catch (_: FelicaStatusException) {
                break
            }

            if (blocks.isEmpty()) {
                break
            }
            for (block in blocks) {
                blocksHex += block.toHexUpper()
            }
            if (blockNumber == 0xFFFF) {
                break
            }
            blockNumber += 1
        }

        return AuthReadResult(
            blocks = blocksHex,
            authSummary = MutualAuthSummary(
                idi = auth.issueId.toHexUpper(),
                pmi = auth.issueParameter.toHexUpper(),
            )
        )
    }

    private fun normalizeAreaPathForAuth(areaCodes: List<Int>): List<Int> {
        val normalized = mutableListOf(ROOT_AREA_CODE)
        for (areaCode in areaCodes) {
            if (areaCode != ROOT_AREA_CODE) {
                normalized += areaCode
            }
        }
        return normalized
    }

    private fun deriveServiceKeysForAuth(
        keys: Map<Int, ByteArray>,
        areaCodes: List<Int>,
        serviceCodeRaw: Int,
    ): Pair<ByteArray, ByteArray> {
        val systemKey = keys[SYSTEM_SERVICE_CODE]
            ?: throw FelicaProtocolException("Missing system key ${hexU16(SYSTEM_SERVICE_CODE)}")

        val areaKeys = mutableListOf<ByteArray>()
        for (areaCode in areaCodes) {
            val key = keys[areaCode]
                ?: throw FelicaProtocolException("Missing area key ${hexU16(areaCode)}")
            areaKeys += key
        }

        val serviceKey = keys[serviceCodeRaw]
            ?: throw FelicaProtocolException("Missing service key ${hexU16(serviceCodeRaw)}")

        return generateServiceKeys(systemKey, areaKeys, listOf(serviceKey))
    }

    private fun generateServiceKeys(
        systemKey: ByteArray,
        areaKeys: List<ByteArray>,
        serviceKeys: List<ByteArray>,
    ): Pair<ByteArray, ByteArray> {
        var currentKey = requireKeyLength(systemKey, "system key")
        for (key in areaKeys) {
            currentKey = encryptDesBlock(currentKey, requireKeyLength(key, "area key"))
        }
        val groupServiceKey = currentKey.copyOf()
        for (key in serviceKeys) {
            currentKey = encryptDesBlock(currentKey, requireKeyLength(key, "service key"))
        }
        return groupServiceKey to currentKey
    }

    private fun collectPlaintextServiceCodes(
        areas: List<AreaNode>,
        systemServices: List<ServiceGroupSummary>,
        targets: MutableList<Int>,
        seen: MutableSet<Int>,
    ) {
        for (area in areas) {
            collectPlaintextChildren(area.children, targets, seen)
        }
        for (group in systemServices) {
            collectPlaintextServiceGroup(group, targets, seen)
        }
    }

    private fun collectPlaintextChildren(
        children: List<AreaChild>,
        targets: MutableList<Int>,
        seen: MutableSet<Int>,
    ) {
        for (child in children) {
            if (child.area != null) {
                collectPlaintextChildren(child.area.children, targets, seen)
            }
            if (child.serviceGroup != null) {
                collectPlaintextServiceGroup(child.serviceGroup, targets, seen)
            }
        }
    }

    private fun collectPlaintextServiceGroup(
        group: ServiceGroupSummary,
        targets: MutableList<Int>,
        seen: MutableSet<Int>,
    ) {
        val service = group.services.firstOrNull { it.attributesRaw and 0x01 == 0x01 } ?: return
        if (seen.add(service.serviceCodeRaw)) {
            targets += service.serviceCodeRaw
        }
    }

    private fun readServiceBlocks(
        nfcF: NfcF,
        idm: ByteArray,
        serviceCodeRaw: Int,
    ): List<String> {
        val blocksHex = mutableListOf<String>()
        var blockNumber = 0

        while (blockNumber <= 0xFFFF) {
            val blockList = buildList {
                for (offset in 0 until BLOCKS_PER_READ_ATTEMPT) {
                    val number = blockNumber + offset
                    if (number <= 0xFFFF) {
                        add(BlockListElement(number, 0, 0))
                    }
                }
            }
            if (blockList.isEmpty()) {
                break
            }

            val blocks = try {
                readWithoutEncryption(
                    nfcF = nfcF,
                    idm = idm,
                    serviceCodes = listOf(serviceCodeRaw),
                    blockList = blockList,
                )
            } catch (_: FelicaStatusException) {
                break
            }

            if (blocks.isEmpty()) {
                break
            }

            for (block in blocks) {
                blocksHex += block.toHexUpper()
            }

            val next = blockNumber + blockList.size
            if (next <= blockNumber) {
                break
            }
            blockNumber = next
        }

        return blocksHex
    }

    private fun assignBlocks(
        areas: List<AreaNode>,
        systemServices: List<ServiceGroupSummary>,
        results: MutableMap<Int, List<String>>,
        authenticated: Boolean,
    ) {
        for (area in areas) {
            assignBlocksInArea(area, results, authenticated)
        }
        for (group in systemServices) {
            assignBlocksInGroup(group, results, authenticated)
        }
    }

    private fun assignBlocksInArea(
        area: AreaNode,
        results: MutableMap<Int, List<String>>,
        authenticated: Boolean,
    ) {
        for (child in area.children) {
            if (child.area != null) {
                assignBlocksInArea(child.area, results, authenticated)
            }
            if (child.serviceGroup != null) {
                assignBlocksInGroup(child.serviceGroup, results, authenticated)
            }
        }
    }

    private fun assignBlocksInGroup(
        group: ServiceGroupSummary,
        results: MutableMap<Int, List<String>>,
        authenticated: Boolean,
    ) {
        if (group.blocks != null) {
            return
        }

        for (service in group.services) {
            val blocks = results.remove(service.serviceCodeRaw) ?: continue
            group.blocks = BlockData(
                serviceCodeRaw = service.serviceCodeRaw,
                authenticated = authenticated,
                blocks = blocks,
            )
            return
        }
    }

    private fun assignAreaKeyVersions(
        area: AreaNode,
        keyVersions: MutableMap<Int, KeyVersionSummary>,
    ) {
        area.keyVersion = keyVersions.remove(area.areaCodeRaw)
        for (child in area.children) {
            if (child.area != null) {
                assignAreaKeyVersions(child.area, keyVersions)
            }
            if (child.serviceGroup != null) {
                assignGroupKeyVersions(child.serviceGroup, keyVersions)
            }
        }
    }

    private fun assignSystemKeyVersions(
        systemServices: List<ServiceGroupSummary>,
        keyVersions: MutableMap<Int, KeyVersionSummary>,
    ) {
        for (group in systemServices) {
            assignGroupKeyVersions(group, keyVersions)
        }
    }

    private fun assignGroupKeyVersions(
        group: ServiceGroupSummary,
        keyVersions: MutableMap<Int, KeyVersionSummary>,
    ) {
        for (service in group.services) {
            service.keyVersion = keyVersions.remove(service.serviceCodeRaw)
        }
    }

    private fun buildServiceSummary(serviceCodeRaw: Int): ServiceSummary {
        val attributes = serviceCodeRaw and 0x3F
        val number = serviceCodeRaw ushr 6
        return ServiceSummary(
            serviceCodeRaw = serviceCodeRaw,
            attributesRaw = attributes,
            codeHex = hexU16(serviceCodeRaw),
            number = number,
            attributesHex = hexU8(attributes),
            attributesDescription = attributesDescription(attributes),
            keyVersion = null,
        )
    }

    private fun newServiceGroup(summary: ServiceSummary): ServiceGroupSummary {
        return ServiceGroupSummary(
            number = summary.number,
            numberHex = hexU16(summary.number),
            services = mutableListOf(summary),
            blocks = null,
        )
    }

    private fun appendServiceGroup(
        groups: MutableList<ServiceGroupSummary>,
        summary: ServiceSummary
    ) {
        val existing = groups.firstOrNull { it.number == summary.number }
        if (existing != null) {
            existing.services += summary
        } else {
            groups += newServiceGroup(summary)
        }
    }

    private fun appendServiceChild(children: MutableList<AreaChild>, summary: ServiceSummary) {
        val existing = children
            .firstOrNull { it.kind == "service_group" && it.serviceGroup?.number == summary.number }
            ?.serviceGroup
        if (existing != null) {
            existing.services += summary
        } else {
            children += AreaChild(kind = "service_group", serviceGroup = newServiceGroup(summary))
        }
    }

    private fun registerServiceCode(
        serviceCodes: MutableList<Int>,
        seenCodes: MutableSet<Int>,
        code: Int,
    ) {
        if (seenCodes.add(code)) {
            serviceCodes += code
        }
    }

}

private fun uByte(value: Byte): Int = value.toInt() and 0xFF

private fun parseSystemCodeBytes(bytes: ByteArray?): Int? {
    if (bytes == null || bytes.size < 2) {
        return null
    }
    return (uByte(bytes[0]) shl 8) or uByte(bytes[1])
}

private fun attributesDescription(attributes: Int): String? {
    return when (attributes) {
        0b001000 -> "Random read/write with key"
        0b001001 -> "Random read/write without key"
        0b001010 -> "Random read-only with key"
        0b001011 -> "Random read-only without key"
        0b001100 -> "Cyclic read/write with key"
        0b001101 -> "Cyclic read/write without key"
        0b001110 -> "Cyclic read-only with key"
        0b001111 -> "Cyclic read-only without key"
        0b010000 -> "Purse direct with key"
        0b010001 -> "Purse direct without key"
        0b010010 -> "Purse cashback/decrement with key"
        0b010011 -> "Purse cashback/decrement without key"
        0b010100 -> "Purse decrement with key"
        0b010101 -> "Purse decrement without key"
        0b010110 -> "Purse read-only with key"
        0b010111 -> "Purse read-only without key"
        else -> null
    }
}

private fun ByteArray.toHexUpper(): String {
    return joinToString(separator = "") { byte ->
        String.format(Locale.US, "%02X", uByte(byte))
    }
}

private fun hexU16(value: Int): String = String.format(Locale.US, "0x%04X", value and 0xFFFF)

private fun hexU8(value: Int): String = String.format(Locale.US, "0x%02X", value and 0xFF)

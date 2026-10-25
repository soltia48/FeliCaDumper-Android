package ws.nyaa.felicadumper

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpExportTest {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    private fun sampleDump(): DumpOutput {
        val group = ServiceGroupSummary(
            number = 0x24,
            numberHex = "0x0024",
            services = mutableListOf(
                ServiceSummary(
                    serviceCodeRaw = 0x090F,
                    attributesRaw = 0x0F,
                    codeHex = "0x090F",
                    number = 0x24,
                    attributesHex = "0x0F",
                    attributesDescription = "Cyclic read-only without key",
                    keyVersion = KeyVersionSummary(aesKeyVersionHex = null, desKeyVersionHex = "0x0001"),
                ),
            ),
            blocks = BlockData(
                serviceCodeRaw = 0x090F,
                authenticated = false,
                blocks = listOf("00112233445566778899AABBCCDDEEFF"),
            ),
        )
        val unknownAttributes = ServiceGroupSummary(
            number = 0x40,
            numberHex = "0x0040",
            services = mutableListOf(
                ServiceSummary(
                    serviceCodeRaw = 0x1000,
                    attributesRaw = 0x00,
                    codeHex = "0x1000",
                    number = 0x40,
                    attributesHex = "0x00",
                    attributesDescription = null,
                    keyVersion = null,
                ),
            ),
            blocks = null,
        )
        val root = AreaNode(
            areaCodeRaw = 0x0000,
            areaCodeHex = "0x0000",
            endServiceCodeHex = "0xFFFE",
            keyVersion = KeyVersionSummary(aesKeyVersionHex = "0x1000", desKeyVersionHex = "0x0000"),
            children = mutableListOf(
                AreaChild(kind = "service_group", serviceGroup = group),
                AreaChild(kind = "service_group", serviceGroup = unknownAttributes),
            ),
        )
        return DumpOutput(
            device = DeviceInfo(manufacturer = "Google", model = "Pixel 6a"),
            systems = listOf(
                SystemSummary(
                    idm = "0123456789ABCDEF",
                    pmm = "0010000000000000",
                    idi = null,
                    pmi = null,
                    systemCodeHex = "0x0003",
                    systemKey = KeyVersionSummary(aesKeyVersionHex = null, desKeyVersionHex = "0x0000"),
                    systemServices = emptyList(),
                    areas = listOf(root),
                    warnings = listOf(DumpWarning(node = 0x1008, message = "Authenticated Read failed")),
                ),
            ),
            warnings = emptyList(),
            generatedAtEpochMs = 1_761_379_200_000L,
        )
    }

    private fun export(): JsonObject =
        JsonParser.parseString(gson.toJson(sampleDump().toDumpFile())).asJsonObject

    @Test
    fun topLevelFields() {
        val json = export()
        assertEquals(1, json["format_version"].asInt)
        assertEquals("2025-10-25T08:00:00Z", json["generated_at"].asString)
        assertEquals("Pixel 6a", json["device"].asJsonObject["model"].asString)
        // Empty warning lists are omitted.
        assertFalse(json.has("warnings"))
    }

    @Test
    fun treeShape() {
        val system = export()["systems"].asJsonArray[0].asJsonObject
        assertEquals("0x0003", system["system_code"].asString)
        assertEquals("0x0000", system["key_version"].asJsonObject["des"].asString)

        val area = system["children"].asJsonArray[0].asJsonObject
        assertEquals("area", area["type"].asString)
        assertEquals("0x0000", area["code"].asString)
        assertEquals("0xFFFE", area["end"].asString)

        val group = area["children"].asJsonArray[0].asJsonObject
        assertEquals("service_group", group["type"].asString)
        assertEquals("0x0024", group["number"].asString)
        val service = group["services"].asJsonArray[0].asJsonObject
        assertEquals("0x090F", service["code"].asString)
        assertEquals("cyclic_read_only_without_key", service["attributes"].asString)
        val blocks = group["blocks"].asJsonObject
        assertEquals("0x090F", blocks["via"].asString)
        assertFalse(blocks["authenticated"].asBoolean)

        val unknown = area["children"].asJsonArray[1].asJsonObject
        assertFalse(unknown["services"].asJsonArray[0].asJsonObject.has("attributes"))
        assertFalse(unknown.has("blocks"))

        val warning = system["warnings"].asJsonArray[0].asJsonObject
        assertEquals("0x1008", warning["node"].asString)
    }

    @Test
    fun keysAreSnakeCaseAndNumbersAreHexStrings() {
        val violations = mutableListOf<String>()
        fun walk(path: String, element: JsonElement) {
            when (element) {
                is JsonObject -> element.entrySet().forEach { (key, value) ->
                    if (!key.matches(Regex("[a-z][a-z0-9_]*"))) violations += "key $path.$key"
                    walk("$path.$key", value)
                }
                is JsonArray -> element.forEachIndexed { i, value -> walk("$path[$i]", value) }
                else -> if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber &&
                    path != "$.format_version"
                ) {
                    violations += "number at $path"
                }
            }
        }
        walk("$", export())
        assertTrue(violations.joinToString(), violations.isEmpty())
    }
}

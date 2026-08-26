package io.schemat.displaykit.pack

import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PackBuilderTest {

    @Test
    fun identicalInputsProduceByteIdenticalPacks() {
        val builder = PackBuilder(PackConfig())
            .addText("assets/displaykit/z.txt", "last")
            .addText("assets/displaykit/a.txt", "first")

        val first = builder.build()
        val second = builder.build()

        assertContentEquals(first, second)
        assertEquals(setOf(0L), zipEntryTimes(first).values.toSet())
    }

    @Test
    fun rawAssetsAreSnapshottedWhenAdded() {
        val bytes = "before".toByteArray()
        val builder = PackBuilder(PackConfig()).addRaw("assets/displaykit/data.bin", bytes)
        bytes.fill(0)

        assertEquals("before", zipEntries(builder.build()).getValue("assets/displaykit/data.bin").decodeToString())
    }

    @Test
    fun unsafeArchivePathsAreRejected() {
        val builder = PackBuilder(PackConfig())
        for (path in listOf("", "/absolute", "../escape", "assets/../escape", "assets\\escape")) {
            assertFailsWith<IllegalArgumentException>(path) {
                builder.addText(path, "unsafe")
            }
        }
    }

    @Test
    fun packDescriptionIsValidEscapedJson() {
        val description = "DisplayKit \"public\"\npack"
        val pack = PackBuilder(PackConfig(packDescription = description)).build()
        val metadata = zipEntries(pack).getValue("pack.mcmeta").decodeToString()

        assertEquals(
            description,
            JsonParser.parseString(metadata).asJsonObject
                .getAsJsonObject("pack")
                .get("description")
                .asString
        )
    }

    private fun zipEntries(pack: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(pack)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }

    private fun zipEntryTimes(pack: ByteArray): Map<String, Long> = buildMap {
        ZipInputStream(ByteArrayInputStream(pack)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, entry.time)
            }
        }
    }
}

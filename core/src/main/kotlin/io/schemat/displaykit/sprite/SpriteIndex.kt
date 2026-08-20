package io.schemat.displaykit.sprite

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Lookup over every vanilla sprite's true pixel dimensions.
 *
 * Generated at build time from a Minecraft client jar by
 * `:libs:displaykit:pack:generateSpriteIndex` and committed as a resource, so
 * normal builds and CI need no jar.
 *
 * This class is deliberately free of Minecraft types — it is plain data, and
 * keeping it that way is what makes the geometry unit-testable.
 */
class SpriteIndex private constructor(
    val sourceVersion: String,
    private val entries: Map<SpriteId, SpriteEntry>
) {
    fun get(id: SpriteId): SpriteEntry? = entries[id]

    fun get(atlas: String, sprite: String): SpriteEntry? = get(SpriteId(atlas, sprite))

    fun all(): Collection<SpriteEntry> = entries.values

    /** Case-insensitive substring match against atlas name or sprite path. */
    fun find(query: String): List<SpriteEntry> {
        val q = query.lowercase()
        return entries.values.filter {
            it.id.atlas.lowercase().contains(q) || it.id.sprite.lowercase().contains(q)
        }
    }

    companion object {
        const val RESOURCE_PATH = "/displaykit/sprites.json"

        /** The generated index shipped inside the core jar. */
        val bundled: SpriteIndex by lazy {
            val stream = SpriteIndex::class.java.getResourceAsStream(RESOURCE_PATH)
                ?: return@lazy SpriteIndex("unknown", emptyMap())
            stream.bufferedReader().use { loadFrom(it.readText()) }
        }

        fun loadFrom(json: String): SpriteIndex {
            val root = JsonParser.parseString(json).asJsonObject
            val version = root.get("sourceVersion")?.asString ?: "unknown"
            val map = LinkedHashMap<SpriteId, SpriteEntry>()
            for (element in root.getAsJsonArray("sprites")) {
                val entry = parseEntry(element.asJsonObject)
                map[entry.id] = entry
            }
            return SpriteIndex(version, map)
        }

        private fun parseEntry(obj: JsonObject): SpriteEntry {
            val id = SpriteId(obj.get("atlas").asString, obj.get("sprite").asString)
            val sliceObj = obj.getAsJsonObject("nineSlice")
            return SpriteEntry(
                id = id,
                width = obj.get("width").asInt,
                height = obj.get("height").asInt,
                texture = obj.get("texture").asString,
                animated = obj.get("animated")?.asBoolean ?: false,
                greyscale = obj.get("greyscale")?.asBoolean ?: false,
                nineSlice = sliceObj?.let {
                    NineSlice(
                        left = it.get("left").asInt,
                        top = it.get("top").asInt,
                        right = it.get("right").asInt,
                        bottom = it.get("bottom").asInt,
                        stretchInner = it.get("stretchInner")?.asBoolean ?: false
                    )
                }
            )
        }
    }
}

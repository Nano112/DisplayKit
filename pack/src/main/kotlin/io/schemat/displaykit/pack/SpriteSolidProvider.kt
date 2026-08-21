package io.schemat.displaykit.pack

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.schemat.displaykit.sprite.SpriteEntry

/**
 * Item models that render a sprite as a physically thick slab.
 *
 * For a control that should be genuinely proud of its panel rather than
 * painted to look it. The model's faces reference the sprite's VANILLA texture
 * path, so nothing but a few hundred bytes of JSON ships.
 *
 * Rendered through VirtualItemDisplay with the returned CustomModelData.
 */
object SpriteSolidProvider : AssetProvider {

    private const val BASE_CMD = 7000

    private data class Key(val texture: String, val thickness: Int)

    private val assigned = LinkedHashMap<Key, Int>()
    private var next = BASE_CMD

    /** Texture path in model form — no `.png`, as models expect. */
    private fun textureRef(entry: SpriteEntry): String = entry.texture.removeSuffix(".png")

    /** Path formatting shared by [modelPathFor] and [pathFor] so they never diverge. */
    private fun pathFor(texture: String, thicknessPx: Int): String {
        val safe = texture.removePrefix("minecraft:").replace('/', '_')
        return "assets/displaykit/models/item/solid_${safe}_${thicknessPx}.json"
    }

    fun modelPathFor(entry: SpriteEntry, thicknessPx: Int): String =
        pathFor(textureRef(entry), thicknessPx)

    fun register(entry: SpriteEntry, thicknessPx: Int = 1): Int =
        assigned.getOrPut(Key(textureRef(entry), thicknessPx)) { next++ }

    fun customModelDataFor(entry: SpriteEntry, thicknessPx: Int = 1): Int? =
        assigned[Key(textureRef(entry), thicknessPx)]

    fun clear() { assigned.clear(); next = BASE_CMD }

    override fun contributeAssets(builder: PackBuilder) {
        if (assigned.isEmpty()) return
        for ((key, _) in assigned) {
            builder.addJson(pathFor(key.texture, key.thickness), slabModel(key).toString())
        }
    }

    private fun slabModel(key: Key): JsonObject {
        val t = key.thickness.toFloat()
        val faces = JsonObject().apply {
            for (face in listOf("north", "south", "east", "west", "up", "down")) {
                add(face, JsonObject().apply {
                    addProperty("texture", "#face")
                    addProperty("tintindex", 0)
                })
            }
        }
        return JsonObject().apply {
            addProperty("parent", "minecraft:block/block")
            add("textures", JsonObject().apply {
                addProperty("face", key.texture)
                addProperty("particle", key.texture)
            })
            add("elements", JsonArray().apply {
                add(JsonObject().apply {
                    add("from", JsonArray().apply { add(0f); add(0f); add(0f) })
                    add("to", JsonArray().apply { add(16f); add(16f); add(t) })
                    add("faces", faces)
                })
            })
        }
    }
}

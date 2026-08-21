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

    /**
     * The tintable base item these models hang off. Shared with `SpriteSolid`,
     * which must set the same item on the entity or the definition below never
     * applies.
     */
    const val BASE_ITEM = "minecraft:leather_horse_armor"

    /**
     * First CustomModelData value, chosen for headroom above the other
     * allocator on this same base item.
     *
     * `ModelManager.calculateNextCustomModelData` starts at 1000 and steps up
     * in blocks of 100 per loaded Blockbench model, so it climbs with model
     * count. The original base of 7000 sat only 60 models away from it: load
     * enough models and the two allocators hand out the same number for
     * different things, and one silently wins.
     *
     * 500_000 is ~5000 models of clearance. It is not a proof — hence [check]
     * in [register] and the bound below, which turn a future collision into a
     * loud failure naming both allocators rather than a wrong-looking item.
     */
    const val BASE_CMD = 500_000

    /** One past the last value this allocator may hand out. */
    const val MAX_CMD = 600_000

    private data class Key(val texture: String, val thickness: Int)

    private val assigned = LinkedHashMap<Key, Int>()
    private var next = BASE_CMD

    /** Texture path in model form — no `.png`, as models expect. */
    private fun textureRef(entry: SpriteEntry): String = entry.texture.removeSuffix(".png")

    /** Model name shared by [pathFor] and [modelRefFor] so they never diverge. */
    private fun modelName(texture: String, thicknessPx: Int): String {
        val safe = texture.removePrefix("minecraft:").replace('/', '_')
        return "solid_${safe}_$thicknessPx"
    }

    /** Path formatting shared by [modelPathFor] and [pathFor] so they never diverge. */
    private fun pathFor(texture: String, thicknessPx: Int): String =
        "assets/displaykit/models/item/${modelName(texture, thicknessPx)}.json"

    /** The same model as an in-pack reference, as an item definition names it. */
    private fun modelRefFor(texture: String, thicknessPx: Int): String =
        "displaykit:item/${modelName(texture, thicknessPx)}"

    fun modelPathFor(entry: SpriteEntry, thicknessPx: Int): String =
        pathFor(textureRef(entry), thicknessPx)

    /** The item definition every solid's case is merged into. */
    fun itemDefinitionPath(): String =
        "assets/minecraft/items/${BASE_ITEM.removePrefix("minecraft:")}.json"

    fun register(entry: SpriteEntry, thicknessPx: Int = 1): Int =
        assigned.getOrPut(Key(textureRef(entry), thicknessPx)) {
            check(next < MAX_CMD) {
                "Exhausted SpriteSolidProvider's CustomModelData range: allocating $next " +
                    "would run past MAX_CMD ($MAX_CMD) and risk colliding with " +
                    "ModelManager.calculateNextCustomModelData, which allocates on the " +
                    "same base item ($BASE_ITEM) and grows upward from 1000. At most " +
                    "${MAX_CMD - BASE_CMD} solid models are supported; raise BASE_CMD/MAX_CMD " +
                    "together, keeping clear of ModelManager's range."
            }
            next++
        }

    fun customModelDataFor(entry: SpriteEntry, thicknessPx: Int = 1): Int? =
        assigned[Key(textureRef(entry), thicknessPx)]

    fun clear() { assigned.clear(); next = BASE_CMD }

    /**
     * Drive the allocator to its bound so the guard can be exercised without
     * registering 100,000 models.
     */
    internal fun exhaustForTest() { next = MAX_CMD }

    override fun contributeAssets(builder: PackBuilder) {
        if (assigned.isEmpty()) return

        val cases = JsonArray()
        for ((key, cmd) in assigned) {
            builder.addJson(pathFor(key.texture, key.thickness), slabModel(key).toString())
            cases.add(JsonObject().apply {
                // The select property reads the custom_model_data STRINGS list,
                // so the case is the CMD rendered as a string; MetadataEncoder
                // writes the same value into that list.
                addProperty("when", cmd.toString())
                add("model", JsonObject().apply {
                    addProperty("type", "minecraft:model")
                    addProperty("model", modelRefFor(key.texture, key.thickness))
                })
            })
        }

        // A model on its own is unreachable: nothing maps CustomModelData to it
        // and the entity renders as a plain leather horse armor. The item
        // definition is the missing half. It MERGES — ItemModelAssetProvider
        // may be writing nucleation's cases into the same file.
        builder.addItemDefinition(
            itemDefinitionPath(),
            JsonObject().apply {
                add("model", JsonObject().apply {
                    addProperty("type", "minecraft:select")
                    addProperty("property", "minecraft:custom_model_data")
                    add("fallback", JsonObject().apply {
                        addProperty("type", "minecraft:model")
                        addProperty("model", "minecraft:item/${BASE_ITEM.removePrefix("minecraft:")}")
                    })
                    add("cases", cases)
                })
            }.toString()
        )
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

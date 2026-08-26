package io.schemat.displaykit.model

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Generates Minecraft resource pack assets from BlockbenchModel.
 *
 * Creates:
 * - Item model JSON files for each bone (using CustomModelData)
 * - Copies/extracts textures
 * - Generates the base item model with overrides
 */
object ModelPackGenerator {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Result of generating pack assets for a model.
     */
    data class GeneratedAssets(
        /** Map of path -> JSON content for item models */
        val itemModels: Map<String, String>,
        /** Map of path -> texture bytes */
        val textures: Map<String, ByteArray>,
        /** The base item model with CustomModelData overrides */
        val baseItemModel: String,
        /** Mapping of bone UUID to CustomModelData value */
        val boneCustomModelData: Map<String, Int>
    )

    /**
     * Generate resource pack assets for a Blockbench model.
     *
     * @param model The parsed Blockbench model
     * @param namespace Resource pack namespace (for example, "example_ui")
     * @param baseItem The base item to use (e.g., "leather_horse_armor")
     * @param startingCustomModelData Starting CustomModelData value
     * @return Generated assets ready to add to a resource pack
     */
    fun generate(
        model: BlockbenchModel,
        namespace: String,
        baseItem: String = "leather_horse_armor",
        startingCustomModelData: Int = 1000
    ): GeneratedAssets {
        val itemModels = mutableMapOf<String, String>()
        val textures = mutableMapOf<String, ByteArray>()
        val boneCustomModelData = mutableMapOf<String, Int>()
        val overrides = JsonArray()

        var customModelData = startingCustomModelData

        // Process each bone
        for (bone in model.bones.flatMap { it.flatten() }) {
            if (bone.elements.isEmpty()) continue

            val modelName = "${model.name}_${bone.name}".lowercase().replace(" ", "_")
            val modelPath = "models/item/$namespace/$modelName.json"

            // Generate the bone's item model
            val boneModel = generateBoneModel(bone, model.textures, namespace)
            itemModels[modelPath] = gson.toJson(boneModel)

            // Track CustomModelData mapping
            boneCustomModelData[bone.uuid] = customModelData

            // Add override entry
            val override = JsonObject().apply {
                add("predicate", JsonObject().apply {
                    addProperty("custom_model_data", customModelData)
                })
                addProperty("model", "$namespace:item/$namespace/$modelName")
            }
            overrides.add(override)

            customModelData++
        }

        // Extract textures
        for (texture in model.textures) {
            if (texture.source != null && texture.source.startsWith("data:image/png;base64,")) {
                val base64Data = texture.source.removePrefix("data:image/png;base64,")
                val bytes = java.util.Base64.getDecoder().decode(base64Data)
                val texturePath = "textures/$namespace/${model.name}_${texture.name}.png"
                textures[texturePath] = bytes
            }
        }

        // Generate base item model with overrides
        val baseItemModel = JsonObject().apply {
            addProperty("parent", "minecraft:item/generated")
            add("textures", JsonObject().apply {
                addProperty("layer0", "minecraft:item/$baseItem")
            })
            add("overrides", overrides)
        }

        return GeneratedAssets(
            itemModels = itemModels,
            textures = textures,
            baseItemModel = gson.toJson(baseItemModel),
            boneCustomModelData = boneCustomModelData
        )
    }

    /**
     * Generate an item model JSON for a single bone.
     */
    private fun generateBoneModel(
        bone: Bone,
        textures: List<ModelTexture>,
        namespace: String
    ): JsonObject {
        val model = JsonObject()

        // Use block/block as parent for 3D models
        model.addProperty("parent", "minecraft:block/block")

        // Textures
        val texturesObj = JsonObject()
        for (texture in textures) {
            texturesObj.addProperty(texture.id.toString(), "$namespace:${texture.name}")
            // Also add as "particle" for the default texture
            if (texture.id == 0) {
                texturesObj.addProperty("particle", "$namespace:${texture.name}")
            }
        }
        model.add("textures", texturesObj)

        // Elements (cubes)
        val elementsArray = JsonArray()
        for (element in bone.elements) {
            val elementObj = JsonObject()

            // From/To coordinates (Blockbench uses -16 to 32 range, centered at 8,8,8)
            // We need to offset to keep within valid range
            val offsetX = 8f - bone.origin.x / 16f
            val offsetY = 8f - bone.origin.y / 16f
            val offsetZ = 8f - bone.origin.z / 16f

            val fromArray = JsonArray()
            fromArray.add(element.from.x + offsetX)
            fromArray.add(element.from.y + offsetY)
            fromArray.add(element.from.z + offsetZ)
            elementObj.add("from", fromArray)

            val toArray = JsonArray()
            toArray.add(element.to.x + offsetX)
            toArray.add(element.to.y + offsetY)
            toArray.add(element.to.z + offsetZ)
            elementObj.add("to", toArray)

            // Rotation (if any)
            if (element.rotation.x != 0f || element.rotation.y != 0f || element.rotation.z != 0f) {
                val rotationObj = JsonObject()
                val originArray = JsonArray()
                originArray.add(element.origin.x + offsetX)
                originArray.add(element.origin.y + offsetY)
                originArray.add(element.origin.z + offsetZ)
                rotationObj.add("origin", originArray)

                // MC only supports one axis at a time, pick the dominant one
                val (axis, angle) = when {
                    Math.abs(element.rotation.x) >= Math.abs(element.rotation.y) &&
                    Math.abs(element.rotation.x) >= Math.abs(element.rotation.z) ->
                        "x" to element.rotation.x

                    Math.abs(element.rotation.y) >= Math.abs(element.rotation.z) ->
                        "y" to element.rotation.y

                    else -> "z" to element.rotation.z
                }
                rotationObj.addProperty("axis", axis)
                rotationObj.addProperty("angle", angle.coerceIn(-45f, 45f))

                elementObj.add("rotation", rotationObj)
            }

            // Faces
            val facesObj = JsonObject()
            for ((faceName, face) in element.faces) {
                val faceObj = JsonObject()
                val uvArray = JsonArray()
                // Convert normalized UV back to 0-16 range
                uvArray.add(face.uv[0] * 16f)
                uvArray.add(face.uv[1] * 16f)
                uvArray.add(face.uv[2] * 16f)
                uvArray.add(face.uv[3] * 16f)
                faceObj.add("uv", uvArray)
                faceObj.addProperty("texture", "#${face.textureId}")
                facesObj.add(faceName, faceObj)
            }
            elementObj.add("faces", facesObj)

            elementsArray.add(elementObj)
        }
        model.add("elements", elementsArray)

        // Display settings for item_display entities
        val displayObj = JsonObject()

        val headObj = JsonObject()
        headObj.add("translation", makeFloatArray(0f, 0f, 0f))
        headObj.add("rotation", makeFloatArray(0f, 0f, 0f))
        headObj.add("scale", makeFloatArray(1f, 1f, 1f))

        val fixedObj = JsonObject()
        fixedObj.add("translation", makeFloatArray(0f, 0f, 0f))
        fixedObj.add("rotation", makeFloatArray(0f, 0f, 0f))
        fixedObj.add("scale", makeFloatArray(1f, 1f, 1f))

        displayObj.add("head", headObj)
        displayObj.add("fixed", fixedObj)
        model.add("display", displayObj)

        return model
    }

    private fun makeFloatArray(a: Float, b: Float, c: Float): JsonArray {
        val arr = JsonArray()
        arr.add(a)
        arr.add(b)
        arr.add(c)
        return arr
    }

    /**
     * Generate the full directory structure for the assets.
     */
    fun generateDirectoryStructure(
        assets: GeneratedAssets,
        namespace: String,
        baseItem: String = "leather_horse_armor"
    ): Map<String, Any> {
        val files = mutableMapOf<String, Any>()

        // Item models
        for ((path, json) in assets.itemModels) {
            files["assets/$namespace/$path"] = json.toByteArray()
        }

        // Base item model override
        files["assets/minecraft/models/item/$baseItem.json"] = assets.baseItemModel.toByteArray()

        // Textures
        for ((path, bytes) in assets.textures) {
            files["assets/$namespace/$path"] = bytes
        }

        return files
    }
}

package io.schemat.displaykit.model

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3f
import io.schemat.displaykit.platform.PlatformProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Central manager for loading and spawning 3D models.
 *
 * Usage:
 * ```kotlin
 * // 1. Load a model from .bbmodel JSON
 * val model = ModelManager.loadModel("golem", bbmodelJsonString)
 *
 * // 2. Generate resource pack assets (do this once at startup)
 * val assets = ModelManager.generatePackAssets("hardwired")
 *
 * // 3. Spawn instances in the world
 * val instance = ModelManager.spawnModel(
 *     "golem",
 *     platform,
 *     position = Vec3d(100.0, 65.0, 100.0),
 *     viewers = listOf(playerUuid)
 * )
 *
 * // 4. Animate and control
 * instance.playAnimation("walk")
 * instance.setRotation(Vec3f(0f, 90f, 0f))
 * instance.setBoneTransform("head", BoneTransform(rotation = Vec3f(15f, 0f, 0f)))
 *
 * // 5. Cleanup
 * instance.destroy()
 * ```
 */
object ModelManager {

    private val loadedModels = ConcurrentHashMap<String, LoadedModel>()
    private val activeInstances = ConcurrentHashMap<Int, ModelInstance>()
    private var nextInstanceId = 1

    /**
     * A loaded model with its generated CustomModelData mappings.
     */
    data class LoadedModel(
        val name: String,
        val model: BlockbenchModel,
        val baseCustomModelData: Int,
        val boneCustomModelData: Map<String, Int>
    )

    /**
     * Load a Blockbench model from JSON.
     *
     * @param name Unique name for this model
     * @param bbmodelJson The raw .bbmodel file content
     * @param baseCustomModelData Starting CustomModelData (auto-assigned if null)
     * @return The loaded model
     */
    fun loadModel(
        name: String,
        bbmodelJson: String,
        baseCustomModelData: Int? = null
    ): LoadedModel {
        val model = BlockbenchModel.parse(bbmodelJson)
        val assignedBase = baseCustomModelData ?: calculateNextCustomModelData()

        val boneCount = model.bones.flatMap { it.flatten() }.count { it.elements.isNotEmpty() }
        val boneMapping = model.bones.flatMap { it.flatten() }
            .filter { it.elements.isNotEmpty() }
            .mapIndexed { index, bone -> bone.uuid to (assignedBase + index) }
            .toMap()

        val loaded = LoadedModel(
            name = name,
            model = model,
            baseCustomModelData = assignedBase,
            boneCustomModelData = boneMapping
        )

        loadedModels[name] = loaded
        return loaded
    }

    /**
     * Get a loaded model by name.
     */
    fun getModel(name: String): LoadedModel? = loadedModels[name]

    /**
     * Get all loaded models.
     */
    fun getAllModels(): Collection<LoadedModel> = loadedModels.values

    /**
     * Unload a model.
     */
    fun unloadModel(name: String) {
        loadedModels.remove(name)
    }

    /**
     * Generate resource pack assets for all loaded models.
     *
     * @param namespace The resource pack namespace (e.g., "hardwired")
     * @param baseItem The item to use for CustomModelData (e.g., "leather_horse_armor")
     * @return Combined generated assets
     */
    fun generatePackAssets(
        namespace: String,
        baseItem: String = "leather_horse_armor"
    ): ModelPackGenerator.GeneratedAssets {
        val allItemModels = mutableMapOf<String, String>()
        val allTextures = mutableMapOf<String, ByteArray>()
        val allBoneMappings = mutableMapOf<String, Int>()

        val combinedOverrides = mutableListOf<Pair<Int, String>>()

        for ((modelName, loaded) in loadedModels) {
            val assets = ModelPackGenerator.generate(
                model = loaded.model,
                namespace = namespace,
                baseItem = baseItem,
                startingCustomModelData = loaded.baseCustomModelData
            )

            allItemModels.putAll(assets.itemModels)
            allTextures.putAll(assets.textures)
            allBoneMappings.putAll(assets.boneCustomModelData)

            // Collect overrides for combined base model
            for ((boneUuid, cmd) in assets.boneCustomModelData) {
                val bone = loaded.model.bones.flatMap { it.flatten() }.find { it.uuid == boneUuid }
                if (bone != null) {
                    val modelPath = "${modelName}_${bone.name}".lowercase().replace(" ", "_")
                    combinedOverrides.add(cmd to "$namespace:item/$namespace/$modelPath")
                }
            }
        }

        // Generate combined base item model
        val baseItemModel = generateCombinedBaseModel(combinedOverrides, baseItem)

        return ModelPackGenerator.GeneratedAssets(
            itemModels = allItemModels,
            textures = allTextures,
            baseItemModel = baseItemModel,
            boneCustomModelData = allBoneMappings
        )
    }

    private fun generateCombinedBaseModel(
        overrides: List<Pair<Int, String>>,
        baseItem: String
    ): String {
        val sorted = overrides.sortedBy { it.first }
        val overridesJson = sorted.joinToString(",\n    ") { (cmd, model) ->
            """{"predicate": {"custom_model_data": $cmd}, "model": "$model"}"""
        }

        return """{
  "parent": "minecraft:item/generated",
  "textures": {
    "layer0": "minecraft:item/$baseItem"
  },
  "overrides": [
    $overridesJson
  ]
}"""
    }

    /**
     * Spawn a model instance in the world.
     *
     * @param modelName Name of a previously loaded model
     * @param platform Platform provider for entity management
     * @param position World position to spawn at
     * @param rotation Initial rotation
     * @param scale Initial scale
     * @param viewers UUIDs of players who should see this instance
     * @return The spawned model instance
     */
    fun spawnModel(
        modelName: String,
        platform: PlatformProvider,
        position: Vec3d,
        rotation: Vec3f = Vec3f(0f, 0f, 0f),
        scale: Float = 1f,
        viewers: Collection<UUID>
    ): ModelInstance? {
        val loaded = loadedModels[modelName] ?: return null

        val instance = ModelInstance(
            model = loaded.model,
            platform = platform,
            position = position,
            rotation = rotation,
            scale = scale,
            baseCustomModelData = loaded.baseCustomModelData
        )

        instance.spawn(viewers)

        val instanceId = nextInstanceId++
        activeInstances[instanceId] = instance

        return instance
    }

    /**
     * Get all active model instances.
     */
    fun getActiveInstances(): Collection<ModelInstance> = activeInstances.values

    /**
     * Destroy all active instances.
     */
    fun destroyAllInstances() {
        for (instance in activeInstances.values) {
            instance.destroy()
        }
        activeInstances.clear()
    }

    /**
     * Calculate the next available CustomModelData range.
     */
    private fun calculateNextCustomModelData(): Int {
        if (loadedModels.isEmpty()) return 1000

        var maxUsed = 1000
        for (loaded in loadedModels.values) {
            val boneCount = loaded.model.bones.flatMap { it.flatten() }.count { it.elements.isNotEmpty() }
            val endRange = loaded.baseCustomModelData + boneCount
            if (endRange > maxUsed) {
                maxUsed = endRange
            }
        }

        // Round up to next 100 for nice grouping
        return ((maxUsed / 100) + 1) * 100
    }
}

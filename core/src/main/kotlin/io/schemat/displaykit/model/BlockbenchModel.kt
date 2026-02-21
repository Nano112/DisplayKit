package io.schemat.displaykit.model

import io.schemat.displaykit.math.Vec3f
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray

/**
 * Parsed Blockbench model (.bbmodel) representation.
 *
 * Blockbench exports models as JSON with:
 * - elements: cube geometries with position, size, rotation, UV mapping
 * - outliner: bone hierarchy (groups containing elements or other groups)
 * - textures: embedded or referenced texture data
 * - animations: keyframe data for bone transformations
 */
data class BlockbenchModel(
    val name: String,
    val bones: List<Bone>,
    val textures: List<ModelTexture>,
    val animations: List<Animation>
) {
    companion object {
        private val gson = Gson()

        /**
         * Parse a .bbmodel JSON file.
         */
        fun parse(json: String): BlockbenchModel {
            val root = gson.fromJson(json, JsonObject::class.java)

            val name = root.get("name")?.asString ?: "unnamed"
            val resolution = root.getAsJsonObject("resolution")
            val textureWidth = resolution?.get("width")?.asInt ?: 16
            val textureHeight = resolution?.get("height")?.asInt ?: 16

            // Parse textures
            val textures = parseTextures(root.getAsJsonArray("textures") ?: JsonArray())

            // Parse elements (cubes)
            val elements = parseElements(root.getAsJsonArray("elements") ?: JsonArray(), textureWidth, textureHeight)

            // Parse outliner (bone hierarchy)
            val bones = parseOutliner(root.getAsJsonArray("outliner") ?: JsonArray(), elements)

            // Parse animations
            val animations = parseAnimations(root.getAsJsonArray("animations") ?: JsonArray())

            return BlockbenchModel(name, bones, textures, animations)
        }

        private fun parseTextures(texturesArray: JsonArray): List<ModelTexture> {
            return texturesArray.mapIndexed { index, element ->
                val obj = element.asJsonObject
                ModelTexture(
                    id = index,
                    name = obj.get("name")?.asString ?: "texture_$index",
                    source = obj.get("source")?.asString // Base64 data URI or null
                )
            }
        }

        private fun parseElements(
            elementsArray: JsonArray,
            textureWidth: Int,
            textureHeight: Int
        ): Map<String, Element> {
            return elementsArray.associate { element ->
                val obj = element.asJsonObject
                val uuid = obj.get("uuid").asString

                val from = obj.getAsJsonArray("from").map { it.asFloat }
                val to = obj.getAsJsonArray("to").map { it.asFloat }
                val origin = obj.getAsJsonArray("origin")?.map { it.asFloat } ?: listOf(0f, 0f, 0f)
                val rotation = obj.getAsJsonArray("rotation")?.map { it.asFloat } ?: listOf(0f, 0f, 0f)

                // Parse face UVs
                val faces = parseFaces(obj.getAsJsonObject("faces") ?: JsonObject(), textureWidth, textureHeight)

                uuid to Element(
                    uuid = uuid,
                    name = obj.get("name")?.asString ?: uuid,
                    from = Vec3f(from[0], from[1], from[2]),
                    to = Vec3f(to[0], to[1], to[2]),
                    origin = Vec3f(origin[0], origin[1], origin[2]),
                    rotation = Vec3f(rotation[0], rotation[1], rotation[2]),
                    faces = faces
                )
            }
        }

        private fun parseFaces(
            facesObj: JsonObject,
            textureWidth: Int,
            textureHeight: Int
        ): Map<String, ElementFace> {
            val faceNames = listOf("north", "south", "east", "west", "up", "down")
            return faceNames.mapNotNull { faceName ->
                val faceObj = facesObj.getAsJsonObject(faceName) ?: return@mapNotNull null
                val uv = faceObj.getAsJsonArray("uv")?.map { it.asFloat } ?: listOf(0f, 0f, 16f, 16f)
                val texture = faceObj.get("texture")?.asInt ?: 0

                faceName to ElementFace(
                    uv = listOf(
                        uv[0] / textureWidth,
                        uv[1] / textureHeight,
                        uv[2] / textureWidth,
                        uv[3] / textureHeight
                    ),
                    textureId = texture
                )
            }.toMap()
        }

        private fun parseOutliner(outlinerArray: JsonArray, elements: Map<String, Element>): List<Bone> {
            return outlinerArray.mapNotNull { item ->
                when {
                    item.isJsonObject -> {
                        val obj = item.asJsonObject
                        val uuid = obj.get("uuid").asString
                        val name = obj.get("name")?.asString ?: uuid
                        val origin = obj.getAsJsonArray("origin")?.map { it.asFloat } ?: listOf(0f, 0f, 0f)
                        val rotation = obj.getAsJsonArray("rotation")?.map { it.asFloat } ?: listOf(0f, 0f, 0f)

                        // Recursively parse children
                        val children = obj.getAsJsonArray("children") ?: JsonArray()
                        val childBones = parseOutliner(children, elements)
                        val childElements = children
                            .filter { it.isJsonPrimitive }
                            .mapNotNull { elements[it.asString] }

                        Bone(
                            uuid = uuid,
                            name = name,
                            origin = Vec3f(origin[0], origin[1], origin[2]),
                            rotation = Vec3f(rotation[0], rotation[1], rotation[2]),
                            elements = childElements,
                            children = childBones
                        )
                    }
                    else -> null // Skip raw element UUIDs at top level
                }
            }
        }

        private fun parseAnimations(animationsArray: JsonArray): List<Animation> {
            return animationsArray.map { animObj ->
                val obj = animObj.asJsonObject
                val name = obj.get("name")?.asString ?: "animation"
                val length = obj.get("length")?.asFloat ?: 1f
                val loop = obj.get("loop")?.asString ?: "once"

                val animators = mutableMapOf<String, BoneAnimator>()
                val animatorsObj = obj.getAsJsonObject("animators") ?: JsonObject()

                for ((boneUuid, animatorElement) in animatorsObj.entrySet()) {
                    val animatorObj = animatorElement.asJsonObject
                    val keyframes = parseKeyframes(animatorObj.getAsJsonArray("keyframes") ?: JsonArray())
                    animators[boneUuid] = BoneAnimator(boneUuid, keyframes)
                }

                Animation(
                    name = name,
                    length = length,
                    loop = when (loop) {
                        "loop" -> LoopMode.LOOP
                        "hold" -> LoopMode.HOLD
                        else -> LoopMode.ONCE
                    },
                    animators = animators
                )
            }
        }

        private fun parseKeyframes(keyframesArray: JsonArray): List<Keyframe> {
            return keyframesArray.map { kfElement ->
                val kfObj = kfElement.asJsonObject
                val time = kfObj.get("time")?.asFloat ?: 0f
                val channel = kfObj.get("channel")?.asString ?: "rotation"
                val dataPoints = kfObj.getAsJsonArray("data_points")?.firstOrNull()?.asJsonObject

                val x = dataPoints?.get("x")?.asFloat ?: 0f
                val y = dataPoints?.get("y")?.asFloat ?: 0f
                val z = dataPoints?.get("z")?.asFloat ?: 0f

                Keyframe(
                    time = time,
                    channel = when (channel) {
                        "position" -> AnimationChannel.POSITION
                        "scale" -> AnimationChannel.SCALE
                        else -> AnimationChannel.ROTATION
                    },
                    value = Vec3f(x, y, z),
                    interpolation = kfObj.get("interpolation")?.asString ?: "linear"
                )
            }
        }
    }
}

/**
 * A bone in the model hierarchy.
 * Contains elements (geometry) and child bones.
 */
data class Bone(
    val uuid: String,
    val name: String,
    val origin: Vec3f,
    val rotation: Vec3f,
    val elements: List<Element>,
    val children: List<Bone>
) {
    /**
     * Flatten the bone hierarchy for iteration.
     */
    fun flatten(): List<Bone> {
        return listOf(this) + children.flatMap { it.flatten() }
    }
}

/**
 * A cube element with geometry and UV mapping.
 */
data class Element(
    val uuid: String,
    val name: String,
    val from: Vec3f,
    val to: Vec3f,
    val origin: Vec3f,
    val rotation: Vec3f,
    val faces: Map<String, ElementFace>
) {
    val size: Vec3f get() = Vec3f(to.x - from.x, to.y - from.y, to.z - from.z)
    val center: Vec3f get() = Vec3f(
        (from.x + to.x) / 2f,
        (from.y + to.y) / 2f,
        (from.z + to.z) / 2f
    )
}

/**
 * UV mapping for a single face.
 */
data class ElementFace(
    val uv: List<Float>, // u1, v1, u2, v2 (normalized 0-1)
    val textureId: Int
)

/**
 * Texture reference or embedded data.
 */
data class ModelTexture(
    val id: Int,
    val name: String,
    val source: String? // Base64 data URI or null for external
)

/**
 * Animation definition.
 */
data class Animation(
    val name: String,
    val length: Float, // seconds
    val loop: LoopMode,
    val animators: Map<String, BoneAnimator>
)

enum class LoopMode { ONCE, LOOP, HOLD }

/**
 * Animation data for a single bone.
 */
data class BoneAnimator(
    val boneUuid: String,
    val keyframes: List<Keyframe>
)

/**
 * A single keyframe in an animation.
 */
data class Keyframe(
    val time: Float,
    val channel: AnimationChannel,
    val value: Vec3f,
    val interpolation: String = "linear"
)

enum class AnimationChannel { POSITION, ROTATION, SCALE }

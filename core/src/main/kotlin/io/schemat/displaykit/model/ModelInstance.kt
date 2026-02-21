package io.schemat.displaykit.model

import io.schemat.displaykit.math.Mat4f
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3f
import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.platform.TaskHandle
import io.schemat.displaykit.render.Billboard
import io.schemat.displaykit.render.Brightness
import io.schemat.displaykit.render.VirtualItemDisplay
import org.joml.Matrix4f
import java.util.UUID

/**
 * A spawned instance of a BlockbenchModel in the world.
 *
 * Each bone in the model becomes a separate item_display entity.
 * The instance manages all entities and synchronizes their transforms.
 *
 * @param model The parsed Blockbench model
 * @param platform Platform provider for entity management
 * @param position World position of the model origin
 * @param baseCustomModelData Starting CustomModelData value for this model's items
 */
class ModelInstance(
    val model: BlockbenchModel,
    private val platform: PlatformProvider,
    var position: Vec3d,
    var rotation: Vec3f = Vec3f(0f, 0f, 0f),
    var scale: Float = 1f,
    private val baseCustomModelData: Int
) {
    private val boneEntities = mutableMapOf<String, BoneEntity>()
    private val viewers = mutableSetOf<UUID>()
    private var isSpawned = false
    private var tickTask: TaskHandle? = null

    // Animation state
    private var currentAnimation: Animation? = null
    private var animationTime: Float = 0f
    private var isAnimating = false

    // Bone transform overrides (for procedural animation)
    private val boneOverrides = mutableMapOf<String, BoneTransform>()

    /**
     * Spawn the model for a set of viewers.
     */
    fun spawn(viewerUuids: Collection<UUID>) {
        if (isSpawned) return
        isSpawned = true
        viewers.addAll(viewerUuids)

        // Create entities for each bone
        var customModelOffset = 0
        for (bone in model.bones.flatMap { it.flatten() }) {
            if (bone.elements.isEmpty()) continue // Skip empty bones

            val entity = VirtualItemDisplay().apply {
                this.position = this@ModelInstance.position
                this.billboard = Billboard.FIXED
                this.brightness = Brightness.FULL
                this.viewRange = 1.0f
                // Item will be set by platform-specific code based on customModelData
            }

            val boneEntity = BoneEntity(
                bone = bone,
                entity = entity,
                customModelData = baseCustomModelData + customModelOffset
            )
            boneEntities[bone.uuid] = boneEntity
            customModelOffset++

            // Spawn the entity
            platform.packetSender.spawnEntity(entity, viewers)
        }

        // Initial transform update
        updateTransforms()

        // Start tick task for animations
        tickTask = platform.scheduler.scheduleRepeating(1L, 1L, Runnable {
            tick()
        })
    }

    /**
     * Add a viewer to see this model.
     */
    fun addViewer(uuid: UUID) {
        if (!isSpawned) return
        if (viewers.add(uuid)) {
            for (boneEntity in boneEntities.values) {
                platform.packetSender.spawnEntity(boneEntity.entity, listOf(uuid))
                platform.packetSender.updateMetadata(boneEntity.entity, listOf(uuid))
            }
        }
    }

    /**
     * Remove a viewer.
     */
    fun removeViewer(uuid: UUID) {
        if (viewers.remove(uuid) && viewers.isEmpty()) {
            // No more viewers
        }
    }

    /**
     * Destroy the model instance.
     */
    fun destroy() {
        if (!isSpawned) return
        isSpawned = false

        tickTask?.cancel()
        tickTask = null

        val entityIds = boneEntities.values.map { it.entity.entityId }
        if (entityIds.isNotEmpty()) {
            platform.packetSender.destroyEntities(entityIds, viewers)
        }

        boneEntities.clear()
        viewers.clear()
    }

    /**
     * Play an animation by name.
     */
    fun playAnimation(name: String) {
        currentAnimation = model.animations.find { it.name == name }
        animationTime = 0f
        isAnimating = currentAnimation != null
    }

    /**
     * Stop the current animation.
     */
    fun stopAnimation() {
        isAnimating = false
        animationTime = 0f
    }

    /**
     * Set a bone transform override (for procedural animation).
     */
    fun setBoneTransform(boneName: String, transform: BoneTransform) {
        val bone = model.bones.flatMap { it.flatten() }.find { it.name == boneName }
        if (bone != null) {
            boneOverrides[bone.uuid] = transform
        }
    }

    /**
     * Clear bone transform overrides.
     */
    fun clearBoneOverrides() {
        boneOverrides.clear()
    }

    /**
     * Update the model's world position.
     */
    fun updatePosition(newPosition: Vec3d) {
        position = newPosition
        updateTransforms()
    }

    /**
     * Update the model's rotation.
     */
    fun updateRotation(newRotation: Vec3f) {
        rotation = newRotation
        updateTransforms()
    }

    private fun tick() {
        if (!isSpawned) return

        // Update animation
        if (isAnimating && currentAnimation != null) {
            val anim = currentAnimation!!
            animationTime += 1f / 20f // 1 tick = 0.05 seconds

            when (anim.loop) {
                LoopMode.LOOP -> {
                    if (animationTime >= anim.length) {
                        animationTime = animationTime % anim.length
                    }
                }
                LoopMode.ONCE -> {
                    if (animationTime >= anim.length) {
                        isAnimating = false
                    }
                }
                LoopMode.HOLD -> {
                    animationTime = animationTime.coerceAtMost(anim.length)
                }
            }

            updateTransforms()
        }
    }

    private fun updateTransforms() {
        if (!isSpawned) return

        // Build base transform (position + rotation + scale)
        val baseMatrix = Matrix4f()
            .translation(position.x.toFloat(), position.y.toFloat(), position.z.toFloat())
            .rotateYXZ(
                Math.toRadians(rotation.y.toDouble()).toFloat(),
                Math.toRadians(rotation.x.toDouble()).toFloat(),
                Math.toRadians(rotation.z.toDouble()).toFloat()
            )
            .scale(scale)

        // Update each bone entity
        for ((boneUuid, boneEntity) in boneEntities) {
            val bone = boneEntity.bone
            val animTransform = getAnimatedTransform(boneUuid)
            val override = boneOverrides[boneUuid]

            // Combine bone origin, animation, and overrides
            val boneMatrix = Matrix4f()

            // Translate to bone origin (convert from Blockbench units to MC blocks)
            // Blockbench uses 1 unit = 1 pixel, MC models are 16 pixels = 1 block
            val originScale = 1f / 16f
            boneMatrix.translate(
                bone.origin.x * originScale,
                bone.origin.y * originScale,
                bone.origin.z * originScale
            )

            // Apply rotation
            val rotX = bone.rotation.x + (animTransform?.rotation?.x ?: 0f) + (override?.rotation?.x ?: 0f)
            val rotY = bone.rotation.y + (animTransform?.rotation?.y ?: 0f) + (override?.rotation?.y ?: 0f)
            val rotZ = bone.rotation.z + (animTransform?.rotation?.z ?: 0f) + (override?.rotation?.z ?: 0f)
            boneMatrix.rotateZYX(
                Math.toRadians(rotZ.toDouble()).toFloat(),
                Math.toRadians(rotY.toDouble()).toFloat(),
                Math.toRadians(rotX.toDouble()).toFloat()
            )

            // Apply position offset
            if (animTransform?.position != null || override?.position != null) {
                val posOffset = Vec3f(
                    (animTransform?.position?.x ?: 0f) + (override?.position?.x ?: 0f),
                    (animTransform?.position?.y ?: 0f) + (override?.position?.y ?: 0f),
                    (animTransform?.position?.z ?: 0f) + (override?.position?.z ?: 0f)
                )
                boneMatrix.translate(posOffset.x * originScale, posOffset.y * originScale, posOffset.z * originScale)
            }

            // Combine with base transform
            val finalMatrix = Matrix4f(baseMatrix).mul(boneMatrix)

            // Extract position for entity
            val entityPos = Vec3d(
                finalMatrix.m30().toDouble(),
                finalMatrix.m31().toDouble(),
                finalMatrix.m32().toDouble()
            )

            // Update entity
            boneEntity.entity.position = entityPos
            boneEntity.entity.transformation = Mat4f(finalMatrix)

            platform.packetSender.teleportEntity(boneEntity.entity, viewers)
            platform.packetSender.updateMetadata(boneEntity.entity, viewers)
        }
    }

    private fun getAnimatedTransform(boneUuid: String): BoneTransform? {
        val anim = currentAnimation ?: return null
        if (!isAnimating) return null

        val animator = anim.animators[boneUuid] ?: return null

        var position: Vec3f? = null
        var rotation: Vec3f? = null
        var scale: Vec3f? = null

        for (channel in AnimationChannel.entries) {
            val keyframes = animator.keyframes.filter { it.channel == channel }
            if (keyframes.isEmpty()) continue

            val value = interpolateKeyframes(keyframes, animationTime)
            when (channel) {
                AnimationChannel.POSITION -> position = value
                AnimationChannel.ROTATION -> rotation = value
                AnimationChannel.SCALE -> scale = value
            }
        }

        return if (position != null || rotation != null || scale != null) {
            BoneTransform(position, rotation, scale)
        } else null
    }

    private fun interpolateKeyframes(keyframes: List<Keyframe>, time: Float): Vec3f {
        if (keyframes.isEmpty()) return Vec3f(0f, 0f, 0f)
        if (keyframes.size == 1) return keyframes[0].value

        // Find surrounding keyframes
        val sorted = keyframes.sortedBy { it.time }
        val before = sorted.lastOrNull { it.time <= time } ?: sorted.first()
        val after = sorted.firstOrNull { it.time > time } ?: sorted.last()

        if (before == after) return before.value

        // Linear interpolation
        val t = (time - before.time) / (after.time - before.time)
        return Vec3f(
            before.value.x + (after.value.x - before.value.x) * t,
            before.value.y + (after.value.y - before.value.y) * t,
            before.value.z + (after.value.z - before.value.z) * t
        )
    }
}

/**
 * Internal tracking of a bone's entity.
 */
internal data class BoneEntity(
    val bone: Bone,
    val entity: VirtualItemDisplay,
    val customModelData: Int
)

/**
 * Transform override for a bone.
 */
data class BoneTransform(
    val position: Vec3f? = null,
    val rotation: Vec3f? = null,
    val scale: Vec3f? = null
)

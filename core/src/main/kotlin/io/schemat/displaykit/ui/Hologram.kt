package io.schemat.displaykit.ui

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.math.Vec3f
import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.render.Brightness
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.VirtualBlockDisplay
import java.util.UUID

/**
 * A group of ghost block-displays for placement previews: footprint cuboids
 * plus small markers (e.g. IO port dots), tintable by validity, movable as
 * one unit while the player aims.
 *
 * ### Visual treatment
 * Block displays cannot be alpha-faded, so "translucency" is achieved with
 * stained-glass ghosts: tint-less cuboids render as white/lime/red stained
 * glass per [tint] (NEUTRAL/VALID/INVALID) with a matching glow outline.
 * Cuboids created with an explicit [BlockStateRef] keep their block (a real
 * preview of what will be placed) and express validity through the glow
 * color only. All ghosts are inset [INSET] blocks per face so they never
 * z-fight with real world blocks.
 *
 * Geometry is spec-based and pure (unit-testable via [localBounds]);
 * entities exist only between [showTo] and [hide]/[destroy], sent packet-
 * only to the given viewers — nothing persists into the world.
 *
 * Usage:
 * ```
 * val holo = Hologram(platform)
 *     .cuboid(Vec3d.ZERO, Vec3f(9f, 5f, 9f))                  // footprint
 *     .marker(Vec3d(1.0, 1.0, 0.0), Hologram.SENSOR_MARKER)   // port dot
 * holo.showTo(setOf(player.uuid))
 * holo.tint = Hologram.Tint.INVALID                            // aim moved somewhere bad
 * holo.moveTo(newOrigin)                                       // follow the aim
 * holo.destroy()
 * ```
 */
class Hologram(private val platform: PlatformProvider) {

    enum class Tint { NEUTRAL, VALID, INVALID }

    private data class Spec(
        val local: Vec3d,
        val size: Vec3f,
        val explicitBlock: BlockStateRef?,
        val markerColor: DkColor?
    )

    private val specs = mutableListOf<Spec>()
    private val entities = mutableListOf<VirtualBlockDisplay>()
    private val viewers = mutableSetOf<UUID>()

    var origin: Vec3d = Vec3d.ZERO
        private set

    /** Live display-entity count (for budget/perf accounting). */
    val entityCount: Int get() = entities.size

    var tint: Tint = Tint.NEUTRAL
        set(value) {
            if (field == value) return
            field = value
            restyle()
        }

    // --- Building (chainable; call before or after showTo — additions while visible spawn immediately) ---

    /** Ghost box of [size] blocks with its min corner at [local] (relative to [origin]). */
    fun cuboid(local: Vec3d, size: Vec3f, block: BlockStateRef? = null): Hologram {
        val spec = Spec(local, size, block, markerColor = null)
        specs.add(spec)
        if (viewers.isNotEmpty()) spawnSpec(spec)
        return this
    }

    /** Small glowing cube centered on [local]+0.5 — for port dots and anchor points. */
    fun marker(local: Vec3d, color: DkColor, size: Float = 0.25f): Hologram {
        val spec = Spec(local, Vec3f(size, size, size), explicitBlock = null, markerColor = color)
        specs.add(spec)
        if (viewers.isNotEmpty()) spawnSpec(spec)
        return this
    }

    /**
     * Wireframe edges of a box: 12 thin tinted ghosts along the edges of the
     * volume with min corner [local] and size [size]. Gives a large volume
     * cuboid crisp definition without filling the view.
     */
    fun outline(local: Vec3d, size: Vec3f, thickness: Float = 0.1f): Hologram {
        val t = thickness
        val w = size.x
        val h = size.y
        val d = size.z
        // 4 verticals
        for ((dx, dz) in listOf(0f to 0f, w - t to 0f, 0f to d - t, w - t to d - t)) {
            cuboid(local + Vec3d(dx.toDouble(), 0.0, dz.toDouble()), Vec3f(t, h, t))
        }
        // 4 along X (bottom + top)
        for ((dy, dz) in listOf(0f to 0f, 0f to d - t, h - t to 0f, h - t to d - t)) {
            cuboid(local + Vec3d(0.0, dy.toDouble(), dz.toDouble()), Vec3f(w, t, t))
        }
        // 4 along Z (bottom + top)
        for ((dx, dy) in listOf(0f to 0f, w - t to 0f, 0f to h - t, w - t to h - t)) {
            cuboid(local + Vec3d(dx.toDouble(), dy.toDouble(), 0.0), Vec3f(t, t, d))
        }
        return this
    }

    /** Min/max corners of all specs in local space (markers use their visual extent). */
    fun localBounds(): Pair<Vec3d, Vec3d>? {
        if (specs.isEmpty()) return null
        var min = Vec3d(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        var max = Vec3d(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (s in specs) {
            val lo = s.local
            val hi = s.local + Vec3d(s.size.x.toDouble(), s.size.y.toDouble(), s.size.z.toDouble())
            min = Vec3d(minOf(min.x, lo.x), minOf(min.y, lo.y), minOf(min.z, lo.z))
            max = Vec3d(maxOf(max.x, hi.x), maxOf(max.y, hi.y), maxOf(max.z, hi.z))
        }
        return min to max
    }

    // --- Visibility / movement ---

    fun showTo(viewerIds: Collection<UUID>) {
        val added = viewerIds.toSet() - viewers
        if (entities.isEmpty() && specs.isNotEmpty()) {
            viewers.addAll(added)
            specs.forEach { spawnSpec(it) }
            perfCount?.invoke("hologram.spawned", specs.size)
            return
        }
        if (added.isEmpty()) return
        viewers.addAll(added)
        for (e in entities) {
            platform.packetSender.spawnEntity(e, added)
            platform.packetSender.updateMetadata(e, added)
        }
    }

    /** Re-anchor the whole hologram; all ghosts follow in one batch. */
    fun moveTo(newOrigin: Vec3d) {
        if (newOrigin.distanceSquared(origin) == 0.0) return
        origin = newOrigin
        if (entities.isEmpty() || !visible) return
        for ((i, e) in entities.withIndex()) {
            e.position = entityPosition(specs[i])
            platform.packetSender.teleportEntity(e, viewers)
        }
        perfCount?.invoke("hologram.moved", entities.size)
    }

    /**
     * Soft visibility: hiding teleports the entities far below the world
     * instead of despawning them, so a transient hide/show cycle (selection
     * gates, page rebuilds) costs one teleport batch each way — never a
     * spawn/despawn storm. Entities keep existing between toggles.
     */
    var visible: Boolean = true
        private set

    fun setVisible(v: Boolean) {
        if (visible == v) return
        visible = v
        if (entities.isEmpty()) return
        for ((i, e) in entities.withIndex()) {
            e.position = entityPosition(specs[i])
            platform.packetSender.teleportEntity(e, viewers)
        }
        perfCount?.invoke(if (v) "hologram.softShown" else "hologram.softHidden", entities.size)
    }

    fun hide() {
        if (entities.isNotEmpty() && viewers.isNotEmpty()) {
            platform.packetSender.destroyEntities(entities.map { it.entityId }, viewers)
            perfCount?.invoke("hologram.despawned", entities.size)
        }
        entities.clear()
        viewers.clear()
    }

    /** Hide and drop the geometry. The instance must not be reused. */
    fun destroy() {
        hide()
        specs.clear()
    }

    // --- Internals ---

    private fun spawnSpec(spec: Spec) {
        val display = VirtualBlockDisplay().apply {
            position = entityPosition(spec)
            scale = insetScale(spec)
            blockState = styleBlock(spec)
            glowing = true
            glowColorOverride = styleGlow(spec)
            brightness = Brightness.FULL
            // Glide teleports over 2 ticks so aim-tracking reads as motion,
            // not popping (display data id 10; see VirtualEntity.teleportDuration)
            teleportDuration = 2
        }
        entities.add(display)
        if (viewers.isNotEmpty()) {
            platform.packetSender.spawnEntity(display, viewers)
            platform.packetSender.updateMetadata(display, viewers)
        }
    }

    private fun restyle() {
        if (entities.isEmpty()) return
        for ((i, e) in entities.withIndex()) {
            e.blockState = styleBlock(specs[i])
            e.glowColorOverride = styleGlow(specs[i])
        }
        platform.packetSender.updateMetadataBatch(entities, viewers)
    }

    private fun entityPosition(spec: Spec): Vec3d =
        origin + spec.local + Vec3d(INSET, INSET, INSET) +
            (if (visible) Vec3d.ZERO else HIDDEN_OFFSET)

    private fun insetScale(spec: Spec): Vec3f = Vec3f(
        (spec.size.x - 2 * INSET.toFloat()).coerceAtLeast(0.05f),
        (spec.size.y - 2 * INSET.toFloat()).coerceAtLeast(0.05f),
        (spec.size.z - 2 * INSET.toFloat()).coerceAtLeast(0.05f)
    )

    private fun styleBlock(spec: Spec): BlockStateRef = when {
        spec.markerColor != null -> MARKER_BLOCK
        spec.explicitBlock != null -> spec.explicitBlock
        else -> when (tint) {
            Tint.NEUTRAL -> WHITE_GLASS
            Tint.VALID -> LIME_GLASS
            Tint.INVALID -> RED_GLASS
        }
    }

    private fun styleGlow(spec: Spec): DkColor = spec.markerColor ?: when (tint) {
        Tint.NEUTRAL -> GLOW_NEUTRAL
        Tint.VALID -> GLOW_VALID
        Tint.INVALID -> GLOW_INVALID
    }

    companion object {
        private const val INSET = 0.02
        private val HIDDEN_OFFSET = Vec3d(0.0, -4096.0, 0.0)

        private val WHITE_GLASS = BlockStateRef("minecraft:white_stained_glass")
        private val LIME_GLASS = BlockStateRef("minecraft:lime_stained_glass")
        private val RED_GLASS = BlockStateRef("minecraft:red_stained_glass")
        private val MARKER_BLOCK = BlockStateRef("minecraft:white_stained_glass")

        private val GLOW_NEUTRAL = DkColor(255, 230, 230, 230)
        private val GLOW_VALID = DkColor(255, 60, 220, 90)
        private val GLOW_INVALID = DkColor(255, 235, 60, 60)

        /** Suggested input/output marker colors; applications may supply any color. */
        val SENSOR_MARKER = DkColor(255, 120, 190, 255)   // light blue: input/read
        val ACTUATOR_MARKER = DkColor(255, 255, 160, 60)  // orange: output/write

        /** Optional application-owned performance counter sink (name, count). */
        @JvmStatic
        var perfCount: ((String, Int) -> Unit)? = null
    }
}

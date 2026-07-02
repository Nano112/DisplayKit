package io.schemat.displaykit.ui.hotbar

import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.platform.PlatformProvider
import io.schemat.displaykit.platform.PlayerRef
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.ui.FloatingUI
import io.schemat.displaykit.ui.InteractionRouter

/**
 * One slot of a [VirtualHotbar]. [onSelect] receives the hotbar so it can
 * push a submenu, mutate slots, or hide the strip.
 */
class HotbarSlot(
    val id: String,
    val label: String,
    val icon: BlockStateRef = BlockStateRef.STONE,
    val enabled: Boolean = true,
    val onSelect: (VirtualHotbar) -> Unit = {}
)

/**
 * A server-driven, hotbar-like menu strip pinned to the player's view: a row
 * of clickable icon buttons that follows the camera at [followDistance]
 * blocks ahead and [heightOffset] below eye height, yawing to face the
 * player via the auto-face mechanism.
 *
 * ### Selection mechanism (and why)
 * Selection is **look + right-click**: the slot nearest the crosshair
 * highlights (standard hover), right-click selects it. We deliberately do
 * NOT drive selection from the player's real held-item slot (scroll):
 * scrolling would change which item the player actually holds, which fights
 * the held-tool workflows this menu exists to serve (e.g. keep holding the
 * Place tool while browsing placeables), and capturing scroll server-side
 * would need another packet mixin. Back navigation is an explicit leftmost
 * `←` slot; paginated content gets `◀`/`▶` edge slots.
 *
 * ### Submenus
 * [push] a new slot list from a slot's onSelect; a back slot appears
 * automatically. [pop] returns; popping the root hides the strip.
 *
 * Lifecycle: [show], [hide], [destroy]; auto-hides after [idleTimeoutTicks]
 * without interaction, and dies with the owner (FloatingUI owner tracking).
 * One instance per player is the intended usage — call [destroy] before
 * replacing.
 */
class VirtualHotbar @JvmOverloads constructor(
    private val platform: PlatformProvider,
    private val owner: PlayerRef,
    rootSlots: List<HotbarSlot>,
    private val slotSize: Float = 0.24f,
    private val gap: Double = 0.05,
    private val followDistance: Double = 2.5,
    private val heightOffset: Double = -1.05,
    private val maxVisible: Int = 9,
    private val idleTimeoutTicks: Int = 20 * 60
) {

    private class Level(var slots: List<HotbarSlot>, var page: Int = 0)

    private val stack = ArrayDeque<Level>().apply { addLast(Level(rootSlots)) }
    private var ui: FloatingUI? = null
    private var idleTicks = 0

    // Camera-follow anchor with hysteresis so the strip doesn't jitter on
    // micro head movement; only re-anchors past the epsilon.
    private var anchor: Vec3d = computeDesiredAnchor()
    private val repositionEpsilon = 0.12

    val isVisible: Boolean get() = ui?.isDestroyed() == false

    // --- Lifecycle ---

    fun show() {
        if (isVisible) return
        idleTicks = 0
        anchor = computeDesiredAnchor()
        val created = FloatingUI(
            platform = platform,
            owner = owner,
            center = anchor,
            facing = anchor - owner.eyePosition(),
            maxDistance = 64.0,            // we follow the player; never distance-kill
            timeoutTicks = Int.MAX_VALUE,  // idle timeout is handled here, not by FloatingUI
            positionProvider = ::followAnchor,
            autoFace = true,
            autoFaceRange = 16.0
        )
        created.addViewer(owner)
        InteractionRouter.registerUI(owner.uuid, created)
        created.onTick = {
            idleTicks++
            if (idleTicks > idleTimeoutTicks) hide()
        }
        ui = created
        rebuild()
    }

    fun hide() {
        ui?.destroy()   // FloatingUI.destroy() unregisters from the router
        ui = null
    }

    /** Hide and drop menu state. The instance must not be shown again. */
    fun destroy() {
        hide()
        stack.clear()
    }

    // --- Navigation ---

    fun push(slots: List<HotbarSlot>) {
        stack.addLast(Level(slots))
        touch(); rebuild()
    }

    fun pop() {
        if (stack.size <= 1) { hide(); return }
        stack.removeLast()
        touch(); rebuild()
    }

    /** Replace the current level's slots in place (e.g. live relabel). */
    fun replaceCurrent(slots: List<HotbarSlot>) {
        val level = stack.lastOrNull() ?: return
        level.slots = slots
        level.page = level.page.coerceAtLeast(0)
        touch(); rebuild()
    }

    private fun page(delta: Int) {
        val level = stack.lastOrNull() ?: return
        level.page += delta
        touch(); rebuild()
    }

    private fun touch() { idleTicks = 0 }

    // --- Rendering ---

    private fun rebuild() {
        val target = ui ?: return
        target.getElements().toList().forEach { target.removeElement(it) }

        val level = stack.last()
        val window = HotbarLayout.window(
            totalContent = level.slots.size,
            page = level.page,
            hasBack = stack.size > 1,
            maxVisible = maxVisible
        )
        level.page = window.page

        val pitch = slotSize + gap
        val xs = HotbarLayout.offsets(window.visibleButtons, pitch)
        var i = 0

        if (window.hasBack) {
            addChrome(target, xs[i++], "←") { pop() }
        }
        if (window.paginated) {
            addChrome(target, xs[i++], "◀", enabled = window.hasPrev) { page(-1) }
        }
        for (c in 0 until window.count) {
            val slot = level.slots[window.startIndex + c]
            val x = xs[i++]
            if (slot.enabled) {
                target.addButton(
                    offsetRight = x, offsetUp = 0.0,
                    label = slot.label,
                    material = slot.icon,
                    hoverMaterial = BlockStateRef.GOLD_BLOCK,
                    size = slotSize
                ) { touch(); slot.onSelect(this) }
            } else {
                target.addButton(
                    offsetRight = x, offsetUp = 0.0,
                    label = slot.label,
                    material = BlockStateRef.GRAY_CONCRETE,
                    hoverMaterial = BlockStateRef.GRAY_CONCRETE,
                    size = slotSize
                ) { /* disabled */ }
            }
        }
        if (window.paginated) {
            addChrome(target, xs[i], "▶ ${window.page + 1}/${window.pageCount}", enabled = window.hasNext) { page(1) }
        }
    }

    private fun addChrome(target: FloatingUI, x: Double, label: String, enabled: Boolean = true, onClick: () -> Unit) {
        target.addButton(
            offsetRight = x, offsetUp = 0.0,
            label = label,
            material = if (enabled) BlockStateRef.LIGHT_GRAY_CONCRETE else BlockStateRef.GRAY_CONCRETE,
            hoverMaterial = if (enabled) BlockStateRef.GOLD_BLOCK else BlockStateRef.GRAY_CONCRETE,
            size = slotSize
        ) { if (enabled) { touch(); onClick() } }
    }

    // --- Camera follow ---

    private fun computeDesiredAnchor(): Vec3d {
        val eye = owner.eyePosition()
        val look = owner.lookDirection()
        val flat = Vec3d(look.x, 0.0, look.z).let {
            if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize()
        }
        return eye + flat * followDistance + Vec3d(0.0, heightOffset, 0.0)
    }

    private fun followAnchor(): Vec3d {
        val desired = computeDesiredAnchor()
        if (desired.distance(anchor) > repositionEpsilon) anchor = desired
        return anchor
    }
}

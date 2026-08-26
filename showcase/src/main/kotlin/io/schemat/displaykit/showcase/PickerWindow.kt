package io.schemat.displaykit.showcase

import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.fabric.pack.FabricPackIntegration
import io.schemat.displaykit.fabric.pack.PackSync
import io.schemat.displaykit.fabric.player.FabricPlayerRef
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.pack.SpriteSliceProvider
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteEntry
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteIndex
import io.schemat.displaykit.surface.RenderMode
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceAnchor
import io.schemat.displaykit.surface.SurfaceHost
import io.schemat.displaykit.surface.SurfaceFocus
import io.schemat.displaykit.surface.SurfaceLifecyclePolicy
import io.schemat.displaykit.surface.SurfacePicking
import io.schemat.displaykit.surface.WorldSurfaceSession
import io.schemat.displaykit.surface.widget.BlockTabStrip
import io.schemat.displaykit.surface.widget.SpriteGridNode
import io.schemat.displaykit.surface.widget.SurfaceWindow
import io.schemat.displaykit.surface.widget.VerticalScrollView
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sprite-picker application state and actions.
 *
 * Geometry, alignment, chrome, tabs, grid layout, scrolling, drag inversion,
 * and glyph preparation all belong to reusable core primitives. This object
 * chooses the data shown and reacts to selection; it does not reproduce any
 * renderer arithmetic.
 */
object PickerWindow {

    private val logger = org.slf4j.LoggerFactory.getLogger("DisplayKit/PickerWindow")

    private const val MIN_W = 400
    private const val MIN_H = 220
    private val WINDOW by lazy { SurfaceWindow.vanilla(MIN_W, MIN_H) }
    private val ATLASES = listOf("items", "blocks", "gui")

    private const val VIEW_DISTANCE_WIDTH_FACTOR = 1.6
    private const val MIN_VIEW_DISTANCE_BLOCKS = 3.0

    private class Session(
        val world: WorldSurfaceSession,
        val player: ServerPlayer,
        val entitiesMode: Boolean,
        val exclusivity: AutoCloseable
    ) {
        val host: SurfaceHost get() = world.host
        var atlas: String = ATLASES.first()
    }

    private val open = ConcurrentHashMap<UUID, Session>()

    private fun syncPackIfGlyphsGrew(glyphsBefore: Int, slicesBefore: Int) {
        val grew = SpriteGlyphs.requested().size > glyphsBefore ||
            SpriteSliceProvider.variantCount() > slicesBefore
        if (grew) FabricPackIntegration.rebuildAndResendToAll()
    }

    /** Rebuild application content and atomically sync any prepared assets. */
    private fun repaintAndSync(session: Session) {
        if (session.entitiesMode) {
            rebuild(session)
            session.host.repaint()
            return
        }
        PackSync.withPackSync("picker") {
            rebuild(session)
            session.host.repaint()
        }
    }

    /** Repaint the already-laid-out primitive tree, preserving scroll state. */
    private fun repaintTree(session: Session) {
        val glyphsBefore = SpriteGlyphs.requested().size
        val slicesBefore = SpriteSliceProvider.variantCount()
        session.host.surface.paintTree()
        session.host.repaint()
        syncPackIfGlyphsGrew(glyphsBefore, slicesBefore)
    }

    fun open(player: ServerPlayer, renderMode: RenderMode = RenderMode.AUTO) {
        closeFor(player.uuid)

        val entitiesMode = renderMode == RenderMode.ENTITIES
        val ref = FabricPlayerRef(player)
        val eye = ref.eyePosition()
        val look = ref.lookDirection()
        val yawDegrees = Surface.yawFacing(look)

        val surface = Surface(
            WINDOW.size.w,
            WINDOW.size.h,
            Vec3d.ZERO,
            targetWidthBlocks = 3f
        )
        surface.renderMode = renderMode
        surface.yawDegrees = yawDegrees
        WINDOW.configure(surface)

        val worldWidth =
            (surface.widthPx * surface.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()
        val distance = maxOf(
            worldWidth * VIEW_DISTANCE_WIDTH_FACTOR,
            MIN_VIEW_DISTANCE_BLOCKS
        )
        val center = Vec3d(
            eye.x + look.x * distance,
            eye.y + look.y * distance,
            eye.z + look.z * distance
        )

        val world = WorldSurfaceSession(
            platform = DisplayKit.platform,
            owner = ref,
            surface = surface,
            anchor = SurfaceAnchor.fixed(center, yawDegrees),
            lifecycle = SurfaceLifecyclePolicy.PERSISTENT
        )
        val exclusivity = ShowcaseWindowGroup.claim(player.uuid) { closeFor(player.uuid) }
        val session = Session(world, player, entitiesMode, exclusivity)
        open[player.uuid] = session

        if (entitiesMode) {
            rebuild(session)
            world.open()
        } else {
            repaintAndSync(session)
            FabricPackIntegration.whenPackApplied(player.uuid) {
                if (open[player.uuid] !== session) return@whenPackApplied
                world.open()
                PackSync.settled("picker")
            }
        }

        val note = if (entitiesMode) {
            " (nopack: no resource pack, one entity per sprite)"
        } else ""
        player.sendSystemMessage(
            Component.literal(
                "Picker open$note. Click a slot to copy its id; the cross closes it."
            )
        )
    }

    /**
     * Compose the picker solely from reusable surface primitives. Layout runs
     * once; every primitive prepares itself from its placed rect before the
     * surface paints it.
     */
    private fun rebuild(session: Session) {
        val active = spritesForAtlas(session.atlas)
        val allAtlases = ATLASES.associateWith(::spritesForAtlas)
        val player = session.player

        lateinit var grid: SpriteGridNode
        session.host.surface.layout { root ->
            val tabs = BlockTabStrip(
                id = "tab",
                values = ATLASES,
                selected = { session.atlas },
                label = { it },
                isHovered = { SurfaceFocus.state(player.uuid).hoveredId == it },
                onSelected = { atlas ->
                    session.atlas = atlas
                    logger.info("picker tab clicked: {}", atlas)
                    repaintAndSync(session)
                }
            )

            grid = SpriteGridNode(
                id = "grid",
                items = active,
                preloadItems = allAtlases.values.flatten(),
                possibleItemCounts = allAtlases.values.map { it.size },
                isHovered = { SurfaceFocus.state(player.uuid).hoveredId == it },
                onClick = { entry ->
                    player.sendSystemMessage(
                        Component.literal("${entry.id}  ${entry.width}x${entry.height}")
                    )
                }
            )

            val scrollView = VerticalScrollView(
                id = "sprite-scroll",
                pane = grid,
                possibleMaxScrolls = grid::possibleMaxScrolls,
                onScrollChanged = { repaintTree(session) }
            )

            WINDOW.build(
                root,
                title = "Sprites — ${session.atlas} (${active.size})",
                onClose = { closeFor(player.uuid) }
            ) { body ->
                body.addChild(tabs.node)
                body.addChild(scrollView.node)
            }
        }
        session.host.surface.paintTree()
    }

    fun describeBoxes(uuid: UUID): List<String> {
        val surface = open[uuid]?.host?.surface ?: return emptyList()
        return surface.hitRects().map { hit ->
            val rect = hit.rect
            val wanted = rect.y + (rect.h - TextMetrics.FONT_LINE_HEIGHT_PX) / 2
            val row = TextMetrics.rowAlignedY(wanted)
            "${hit.id} rect=(${rect.x},${rect.y} ${rect.w}x${rect.h}) " +
                "labelWants=$wanted labelGets=$row " +
                "offsetInBox=${row - rect.y} boxBottom=${rect.bottom}"
        }
    }

    data class AimResult(val yaw: Float, val pitch: Float, val landedOn: String?)

    fun faceRegion(uuid: UUID, regionId: String, distance: Double): AimResult? {
        val session = open[uuid] ?: return null
        val surface = session.host.surface
        val hit = surface.hitRects().firstOrNull { it.id == regionId } ?: return null
        val c = surface.worldPointOf(
            hit.rect.x + hit.rect.w / 2,
            hit.rect.y + hit.rect.h / 2
        )
        val px = surface.worldPointOf(
            hit.rect.x + hit.rect.w / 2 + 1,
            hit.rect.y + hit.rect.h / 2
        )
        val py = surface.worldPointOf(
            hit.rect.x + hit.rect.w / 2,
            hit.rect.y + hit.rect.h / 2 + 1
        )
        val ux = Vec3d(px.x - c.x, px.y - c.y, px.z - c.z)
        val uy = Vec3d(py.x - c.x, py.y - c.y, py.z - c.z)
        var nx = ux.y * uy.z - ux.z * uy.y
        var ny = ux.z * uy.x - ux.x * uy.z
        var nz = ux.x * uy.y - ux.y * uy.x
        val len = Math.sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-9) return null
        nx /= len
        ny /= len
        nz /= len

        val player = session.player
        val eyeHeight = player.eyeHeight.toDouble()
        val toViewer =
            (player.x - c.x) * nx +
                (player.y + eyeHeight - c.y) * ny +
                (player.z - c.z) * nz
        val sign = if (toViewer < 0) -1.0 else 1.0
        player.connection.teleport(
            c.x + nx * distance * sign,
            c.y + ny * distance * sign - eyeHeight,
            c.z + nz * distance * sign,
            player.yRot,
            player.xRot
        )
        return aimAt(uuid, regionId)
    }

    fun aimAt(uuid: UUID, regionId: String): AimResult? {
        val session = open[uuid] ?: return null
        val surface = session.host.surface
        val hit = surface.hitRects().firstOrNull { it.id == regionId } ?: return null
        val target = surface.worldPointOf(
            hit.rect.x + hit.rect.w / 2,
            hit.rect.y + hit.rect.h / 2
        )

        val ref = FabricPlayerRef(session.player)
        val eye = ref.eyePosition()
        val dx = target.x - eye.x
        val dy = target.y - eye.y
        val dz = target.z - eye.z
        val yaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
        val pitch =
            (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))).toFloat()
        val player = session.player
        player.connection.teleport(player.x, player.y, player.z, yaw, pitch)

        val pixel = SurfacePicking.localPixel(surface, ref.eyePosition(), ref.lookDirection())
        val landed = pixel?.let { (x, y) ->
            surface.hitRects().lastOrNull { it.rect.contains(x, y) }?.id
        }
        return AimResult(yaw, pitch, landed)
    }

    fun closeFor(uuid: UUID) {
        val session = open.remove(uuid) ?: return
        session.exclusivity.close()
        session.world.close()
        if (open.isEmpty()) PackSync.forget("picker")
    }

    private fun spritesForAtlas(atlas: String): List<SpriteEntry> =
        SpriteIndex.bundled.all()
            .filter { it.id.atlas == atlas && it.glyphEligible }
            .sortedBy { it.id.sprite }
}

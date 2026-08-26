package io.schemat.displaykit.showcase

import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.fabric.pack.FabricPackIntegration
import io.schemat.displaykit.fabric.pack.PackSync
import io.schemat.displaykit.fabric.player.FabricPlayerRef
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.pack.SpriteSliceProvider
import io.schemat.displaykit.render.DkColor
import io.schemat.displaykit.render.TextMetrics
import io.schemat.displaykit.sprite.SpriteGlyphs
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.surface.RenderMode
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceFocus
import io.schemat.displaykit.surface.SurfaceHost
import io.schemat.displaykit.surface.SurfacePicking
import io.schemat.displaykit.surface.SurfacePlacement
import io.schemat.displaykit.surface.layout.PxOffset
import io.schemat.displaykit.surface.layout.PxSize
import io.schemat.displaykit.surface.layout.SurfaceNode
import io.schemat.displaykit.surface.layout.VirtualCanvasNode
import io.schemat.displaykit.surface.widget.CartographyMapView
import io.schemat.displaykit.surface.widget.MapMarker
import io.schemat.displaykit.surface.widget.MapRoute
import io.schemat.displaykit.surface.widget.SkillDefinition
import io.schemat.displaykit.surface.widget.SkillTreeView
import io.schemat.displaykit.surface.widget.SurfaceWindow
import io.schemat.displaykit.surface.widget.TabbedPage
import io.schemat.displaykit.surface.widget.TabbedView
import io.schemat.displaykit.ui.InteractionRouter
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The two interactive, primitive-composed canvas showcases. */
object CanvasWindows {
    enum class Kind { MAP, SKILLS, TABS }
    private enum class WorkspaceTab { MAP, SKILLS }

    private val mapWindow by lazy { SurfaceWindow.vanilla(380, 320) }
    private val skillWindow by lazy { SurfaceWindow.vanilla(460, 310) }
    private val tabWindow by lazy { SurfaceWindow.vanilla(520, 330) }
    private const val PACK_KEY = "canvas-windows"

    private class Session(
        val kind: Kind,
        val host: SurfaceHost,
        val player: ServerPlayer,
        val exclusivity: AutoCloseable
    ) {
        var selectedMarker: String? = "spawn"
        val unlocked = linkedSetOf("basics")
        var selectedTab: WorkspaceTab = WorkspaceTab.MAP
    }

    data class AimResult(val yaw: Float, val pitch: Float, val landedOn: String?)

    private val open = ConcurrentHashMap<UUID, Session>()

    fun openMap(player: ServerPlayer) = open(player, Kind.MAP)
    fun openSkills(player: ServerPlayer) = open(player, Kind.SKILLS)
    fun openTabs(player: ServerPlayer) = open(player, Kind.TABS)

    private fun open(player: ServerPlayer, kind: Kind) {
        closeFor(player.uuid)

        val window = when (kind) {
            Kind.MAP -> mapWindow
            Kind.SKILLS -> skillWindow
            Kind.TABS -> tabWindow
        }
        val targetWidth = when (kind) {
            Kind.MAP -> 3.6f
            Kind.SKILLS -> 4.2f
            Kind.TABS -> 4.8f
        }
        val ref = FabricPlayerRef(player)
        val eye = ref.eyePosition()
        val look = ref.lookDirection()
        val yaw = Surface.yawFacing(look)
        val surface = Surface(window.size.w, window.size.h, Vec3d.ZERO, targetWidth)
        surface.renderMode = RenderMode.AUTO
        surface.yawDegrees = yaw
        window.configure(surface)

        val worldWidth =
            (surface.widthPx * surface.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()
        val worldHeight =
            (surface.heightPx * surface.pixelScale * TextMetrics.PIXEL_SIZE).toDouble()
        val distance = maxOf(worldWidth * 1.55, 3.0)
        surface.position = SurfacePlacement.inFrontOf(
            eye, look, distance, yaw, worldWidth, worldHeight
        )

        val exclusivity = ShowcaseWindowGroup.claim(player.uuid) { closeFor(player.uuid) }
        val session = Session(
            kind,
            SurfaceHost(DisplayKit.platform, ref, surface),
            player,
            exclusivity
        )
        open[player.uuid] = session
        PackSync.withPackSync(PACK_KEY) {
            compose(session)
            session.host.repaint()
        }
        FabricPackIntegration.whenPackApplied(player.uuid) {
            if (open[player.uuid] !== session) return@whenPackApplied
            session.host.open()
            InteractionRouter.registerSurface(player.uuid, session.host)
            PackSync.settled(PACK_KEY)
        }

        val help = when (kind) {
            Kind.MAP -> "Map open. Click a marker; click empty parchment, move, and click again to pan."
            Kind.SKILLS -> "Skill tree open. Click gold skills to unlock; drag empty canvas to explore."
            Kind.TABS -> "Tabbed workspace open. Map and skills share one state-driven tab composition."
        }
        player.sendSystemMessage(Component.literal(help))
    }

    private fun compose(session: Session) {
        when (session.kind) {
            Kind.MAP -> composeMap(session)
            Kind.SKILLS -> composeSkills(session)
            Kind.TABS -> composeTabs(session)
        }
        session.host.surface.paintTree()
    }

    private fun composeMap(session: Session) {
        val uuid = session.player.uuid
        session.host.surface.layout { root ->
            mapWindow.build(
                root,
                title = "Cartography — drag to explore",
                onClose = { closeFor(uuid) }
            ) { body -> body.addChild(mapView(session).node) }
        }
    }

    private fun composeSkills(session: Session) {
        val uuid = session.player.uuid
        session.host.surface.layout { root ->
            skillWindow.build(
                root,
                title = "Skills — drag canvas or use arrows",
                onClose = { closeFor(uuid) }
            ) { body -> body.addChild(skillView(session).node) }
        }
    }

    private fun composeTabs(session: Session) {
        val uuid = session.player.uuid
        session.host.surface.layout { root ->
            val tabs = TabbedView(
                id = "workspace",
                pages = listOf(
                    TabbedPage(WorkspaceTab.MAP, "Map", mapView(session, "tab-map").node),
                    TabbedPage(WorkspaceTab.SKILLS, "Skills", skillView(session, "tab-skills").node)
                ),
                selected = { session.selectedTab },
                isHovered = { SurfaceFocus.state(uuid).hoveredId == it },
                onSelected = { tab ->
                    session.selectedTab = tab
                    session.host.surface.paintTree()
                }
            )
            tabWindow.build(
                root,
                title = "Workspace — composed tabs",
                onClose = { closeFor(uuid) }
            ) { body -> body.addChild(tabs.node) }
        }
    }

    private fun mapView(session: Session, id: String = "map-canvas") = CartographyMapView(
        id = id,
        contentSize = PxSize(520, 380),
        markers = MAP_MARKERS,
        routes = MAP_ROUTES,
        selectedId = { session.selectedMarker },
        isHovered = { SurfaceFocus.state(session.player.uuid).hoveredId == it },
        onSelected = { marker ->
            session.selectedMarker = marker.id
            session.host.surface.paintTree()
            session.player.sendSystemMessage(Component.literal("Selected ${marker.label}"))
        },
        onViewportChanged = { repaintAndPush(session) }
    )

    private fun skillView(session: Session, id: String = "skill-canvas") = SkillTreeView(
        id = id,
        contentSize = PxSize(760, 480),
        skills = SKILLS,
        isUnlocked = session.unlocked::contains,
        isHovered = { SurfaceFocus.state(session.player.uuid).hoveredId == it },
        onUnlock = { skill ->
            if (session.unlocked.add(skill.id)) {
                session.host.surface.paintTree()
                session.player.sendSystemMessage(Component.literal("Unlocked ${skill.label}"))
            }
        },
        onViewportChanged = { repaintAndPush(session) }
    )

    /** Repaint after a drag or wheel event, guarding against missed prewarming. */
    private fun repaintAndPush(session: Session) {
        val glyphsBefore = SpriteGlyphs.requested().size
        val slicesBefore = SpriteSliceProvider.variantCount()
        session.host.surface.paintTree()
        session.host.repaint()
        if (SpriteGlyphs.requested().size > glyphsBefore ||
            SpriteSliceProvider.variantCount() > slicesBefore
        ) {
            FabricPackIntegration.rebuildAndResendToAll()
        }
    }

    fun aimAt(uuid: UUID, nodeId: String): AimResult? {
        val session = open[uuid] ?: return null
        val surface = session.host.surface
        val node = surface.root?.find(nodeId) ?: return null
        // A virtual canvas can letterbox its content viewport inside its layout
        // rect. Aim inside the actual interactive viewport, not its inert
        // margin, so automated and accessibility-driven input exercises the
        // same hit target that a player sees.
        val targetPixel = if (node is VirtualCanvasNode) {
            emptyCanvasPoint(surface, node)
        } else {
            val rect = node.rect()
            PxOffset(rect.x + rect.w / 2, rect.y + rect.h / 2)
        }
        val targetX = targetPixel.x
        val targetY = targetPixel.y
        val target = surface.worldPointOf(targetX, targetY)
        val ref = FabricPlayerRef(session.player)
        val eye = ref.eyePosition()
        val dx = target.x - eye.x
        val dy = target.y - eye.y
        val dz = target.z - eye.z
        val yaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
        val pitch =
            (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)))).toFloat()
        session.player.connection.teleport(
            session.player.x, session.player.y, session.player.z, yaw, pitch
        )
        val landed = SurfacePicking.localPixel(surface, ref.eyePosition(), ref.lookDirection())
            ?.let { (x, y) -> surface.nodeAt(x, y)?.id }
        return AimResult(yaw, pitch, landed)
    }

    /** Find a visible canvas pixel that is not occupied by content or controls. */
    private fun emptyCanvasPoint(surface: Surface, canvas: VirtualCanvasNode): PxOffset {
        val viewport = canvas.viewportRect()
        val inset = 8
        for (y in viewport.y + inset until viewport.bottom - inset step 8) {
            for (x in viewport.x + inset until viewport.right - inset step 8) {
                if (surface.nodeAt(x, y) === canvas) return PxOffset(x, y)
            }
        }
        return PxOffset(viewport.x + viewport.w / 2, viewport.y + viewport.h / 2)
    }

    fun kindFor(uuid: UUID): Kind? = open[uuid]?.kind

    fun closeFor(uuid: UUID) {
        val session = open.remove(uuid) ?: return
        session.exclusivity.close()
        InteractionRouter.unregisterSurface(uuid, session.host)
        session.host.close()
        if (open.isEmpty()) PackSync.forget(PACK_KEY)
    }

    private fun SurfaceNode.find(wanted: String): SurfaceNode? {
        if (id == wanted) return this
        return children.firstNotNullOfOrNull { it.find(wanted) }
    }

    private val MAP_MARKERS = listOf(
        MapMarker("spawn", "Spawn", 260, 190, SpriteId("items", "item/compass_00")),
        MapMarker("farm", "Verdant Farm", 175, 105, SpriteId("items", "item/wheat"), DkColor.fromRGB(172, 196, 107)),
        MapMarker("mine", "Ember Mine", 350, 95, SpriteId("items", "item/iron_pickaxe"), DkColor.fromRGB(192, 142, 91)),
        MapMarker("harbor", "Old Harbor", 175, 280, SpriteId("items", "item/oak_boat"), DkColor.fromRGB(106, 168, 188)),
        MapMarker("gate", "Ender Gate", 350, 285, SpriteId("items", "item/ender_eye"), DkColor.fromRGB(151, 113, 177))
    )
    private val MAP_ROUTES = listOf(
        MapRoute("spawn", "farm"), MapRoute("spawn", "mine"),
        MapRoute("spawn", "harbor"), MapRoute("mine", "gate"),
        MapRoute("harbor", "gate")
    )

    private val SKILLS = listOf(
        SkillDefinition("basics", "Basics", 160, 220, SpriteId("items", "item/book")),
        SkillDefinition("mining", "Mining", 300, 120, SpriteId("items", "item/iron_pickaxe"), listOf("basics")),
        SkillDefinition("farming", "Farming", 300, 220, SpriteId("items", "item/wheat"), listOf("basics")),
        SkillDefinition("explore", "Exploration", 300, 320, SpriteId("items", "item/compass_00"), listOf("basics")),
        SkillDefinition("smithing", "Smithing", 460, 80, SpriteId("items", "item/iron_ingot"), listOf("mining")),
        SkillDefinition("enchant", "Enchanting", 460, 160, SpriteId("items", "item/enchanted_book"), listOf("mining")),
        SkillDefinition("husbandry", "Husbandry", 460, 240, SpriteId("items", "item/golden_carrot"), listOf("farming")),
        SkillDefinition("mapping", "Cartography", 460, 340, SpriteId("items", "item/filled_map"), listOf("explore")),
        SkillDefinition("nether", "Nethercraft", 620, 120, SpriteId("items", "item/blaze_rod"), listOf("smithing", "enchant")),
        SkillDefinition("mastery", "Mastery", 680, 250, SpriteId("items", "item/nether_star"), listOf("husbandry", "mapping", "nether"))
    )
}

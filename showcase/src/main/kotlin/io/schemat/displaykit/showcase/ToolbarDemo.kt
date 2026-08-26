package io.schemat.displaykit.showcase

import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.action.ActionHandler
import io.schemat.displaykit.action.ActionIcon
import io.schemat.displaykit.action.ActionMenuSession
import io.schemat.displaykit.action.ActionMenuListener
import io.schemat.displaykit.action.ActionPage
import io.schemat.displaykit.action.ActionSpec
import io.schemat.displaykit.fabric.hotbar.InventoryToolbarRenderer
import io.schemat.displaykit.fabric.pack.FabricPackIntegration
import io.schemat.displaykit.fabric.pack.PackSync
import io.schemat.displaykit.fabric.player.FabricPlayerRef
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.sprite.SpriteId
import io.schemat.displaykit.surface.ItemRef
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceAnchor
import io.schemat.displaykit.surface.SurfaceFocus
import io.schemat.displaykit.surface.SurfaceLifecyclePolicy
import io.schemat.displaykit.surface.WorldSurfaceSession
import io.schemat.displaykit.surface.layout.PxPadding
import io.schemat.displaykit.surface.widget.ActionMenuView
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Live acceptance harness for the renderer-independent action menu API. */
object ToolbarDemo {
    private val open = mutableMapOf<UUID, ActionMenuSession>()
    private class SurfacePresentation(
        val actions: ActionMenuSession,
        val world: WorldSurfaceSession,
        val exclusivity: AutoCloseable,
        var subscription: AutoCloseable? = null
    )
    private val surfaceOpen = mutableMapOf<UUID, SurfacePresentation>()

    fun open(player: ServerPlayer) {
        closeFor(player.uuid)
        closeSurfaceFor(player.uuid)
        val session = ActionMenuSession(rootPage(player))
        open[player.uuid] = session
        InventoryToolbarRenderer.present(player, session) {
            open.remove(player.uuid, session)
            player.sendSystemMessage(Component.literal("Toolbar restored cleanly"))
        }
    }

    fun closeFor(playerId: UUID) {
        open.remove(playerId)?.close()
    }

    fun openSurface(player: ServerPlayer) {
        closeFor(player.uuid)
        closeSurfaceFor(player.uuid)

        val ref = FabricPlayerRef(player)
        val look = ref.lookDirection()
        val eye = ref.eyePosition()
        val center = eye + look * 3.0
        val actions = ActionMenuSession(rootPage(player))
        val surface = Surface(220, 250, Vec3d.ZERO, targetWidthBlocks = 2.4f).also {
            it.backingBlock = BlockStateRef.BLACK_CONCRETE
        }
        val view = ActionMenuView(
            id = "showcase-toolbar",
            actions = actions,
            isHovered = { SurfaceFocus.state(player.uuid).hoveredId == it },
            onStateChanged = {},
            actorId = player.uuid
        )
        surface.layout { root ->
            root.padding = PxPadding.all(10)
            root.addChild(view.node)
        }

        val world = WorldSurfaceSession(
            platform = DisplayKit.platform,
            owner = ref,
            surface = surface,
            anchor = SurfaceAnchor.facing(center, look),
            lifecycle = SurfaceLifecyclePolicy.PERSISTENT,
            onClosed = { closeSurfaceFor(player.uuid) }
        )
        val exclusivity = ShowcaseWindowGroup.claim(player.uuid) { closeSurfaceFor(player.uuid) }
        val presentation = SurfacePresentation(actions, world, exclusivity)
        surfaceOpen[player.uuid] = presentation
        presentation.subscription = actions.subscribe(ActionMenuListener { snapshot ->
            if (snapshot.closed) closeSurfaceFor(player.uuid)
            else world.repaint()
        })

        PackSync.withPackSync("toolbar-surface") { surface.paintTree() }
        FabricPackIntegration.whenPackApplied(player.uuid) {
            if (surfaceOpen[player.uuid] !== presentation) return@whenPackApplied
            world.open()
            PackSync.settled("toolbar-surface")
        }
        player.sendSystemMessage(Component.literal("Surface action toolbar open"))
    }

    fun closeSurfaceFor(playerId: UUID) {
        val presentation = surfaceOpen.remove(playerId) ?: return
        presentation.subscription?.close()
        presentation.subscription = null
        presentation.exclusivity.close()
        if (!presentation.actions.isClosed) presentation.actions.close()
        presentation.world.close()
        if (surfaceOpen.isEmpty()) PackSync.forget("toolbar-surface")
    }

    private fun rootPage(player: ServerPlayer): ActionPage = ActionPage(
        id = "showcase:toolbar/root",
        title = "Action toolbar",
        actions = listOf(
            ActionSpec.submenu(
                id = "craft",
                label = "Crafting",
                icon = ActionIcon.Block(BlockStateRef("minecraft:crafting_table"))
            ) { childPage(player) }.withFocus(player, "Open a native action submenu"),
            messageAction(player, "map", "Map", ActionIcon.Item(ItemRef("minecraft:filled_map"))),
            messageAction(player, "skills", "Skills", ActionIcon.Item(ItemRef("minecraft:experience_bottle"))),
            messageAction(
                player,
                "sprite",
                "Sprite fallback",
                ActionIcon.Sprite(
                    SpriteId("gui", "hud/hotbar"),
                    fallback = ActionIcon.Item(ItemRef("minecraft:painting"))
                )
            ),
            ActionSpec(
                id = "disabled",
                label = "Disabled",
                icon = ActionIcon.Block(BlockStateRef("minecraft:gray_concrete")),
                description = "Disabled actions remain visible but cannot run.",
                enabled = false
            ).withFocus(player, "Disabled actions cannot be invoked"),
            ActionSpec(
                id = "busy",
                label = "Loading",
                icon = ActionIcon.Item(ItemRef("minecraft:clock")),
                description = "Busy actions have a distinct non-invokable state.",
                busy = true
            ).withFocus(player, "Busy actions are visible but temporarily blocked"),
            messageAction(player, "inspect", "Inspect", ActionIcon.Item(ItemRef("minecraft:spyglass"))),
            messageAction(player, "settings", "Settings", ActionIcon.Item(ItemRef("minecraft:comparator"))),
            messageAction(player, "history", "History", ActionIcon.Item(ItemRef("minecraft:book"))),
            messageAction(player, "search", "Search", ActionIcon.Item(ItemRef("minecraft:compass"))),
            messageAction(player, "help", "Help", ActionIcon.Item(ItemRef("minecraft:knowledge_book")))
        )
    )

    private fun childPage(player: ServerPlayer): ActionPage = ActionPage(
        id = "showcase:toolbar/crafting",
        title = "Crafting",
        actions = listOf(
            messageAction(player, "recipe", "Recipe", ActionIcon.Item(ItemRef("minecraft:book"))),
            messageAction(player, "result", "Result", ActionIcon.Item(ItemRef("minecraft:diamond")))
        )
    )

    private fun messageAction(
        player: ServerPlayer,
        id: String,
        label: String,
        icon: ActionIcon
    ): ActionSpec = ActionSpec(
        id = id,
        label = label,
        icon = icon,
        onInvoke = ActionHandler {
            player.sendSystemMessage(Component.literal("Invoked $label ($id)"))
        }
    ).withFocus(player, "$label — right-click to invoke")

    private fun ActionSpec.withFocus(player: ServerPlayer, hint: String): ActionSpec = copy(
        onFocus = ActionHandler {
            player.displayClientMessage(Component.literal(hint), true)
        }
    )
}

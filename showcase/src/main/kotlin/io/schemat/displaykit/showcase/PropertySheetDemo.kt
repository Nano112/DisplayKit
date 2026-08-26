package io.schemat.displaykit.showcase

import io.schemat.displaykit.DisplayKit
import io.schemat.displaykit.fabric.pack.FabricPackIntegration
import io.schemat.displaykit.fabric.pack.PackSync
import io.schemat.displaykit.fabric.player.FabricPlayerRef
import io.schemat.displaykit.math.Vec3d
import io.schemat.displaykit.property.BooleanPropertyField
import io.schemat.displaykit.property.ChoicePropertyField
import io.schemat.displaykit.property.IntPropertyField
import io.schemat.displaykit.property.PropertySheetModel
import io.schemat.displaykit.render.BlockStateRef
import io.schemat.displaykit.surface.Surface
import io.schemat.displaykit.surface.SurfaceAnchor
import io.schemat.displaykit.surface.SurfaceFocus
import io.schemat.displaykit.surface.SurfaceLifecyclePolicy
import io.schemat.displaykit.surface.WorldSurfaceSession
import io.schemat.displaykit.surface.layout.CrossAxis
import io.schemat.displaykit.surface.widget.PropertySheetView
import io.schemat.displaykit.surface.widget.SurfaceWindow
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** Live acceptance scene for the reusable property model and surface view. */
object PropertySheetDemo {
    private data class Values(var signal: Int = 7, var enabled: Boolean = true, var mode: String = "Pulse")
    private class Session(val world: WorldSurfaceSession, val exclusivity: AutoCloseable)
    private val open = mutableMapOf<UUID, Session>()
    private val window by lazy { SurfaceWindow.vanilla(minWidth = 230, minHeight = 250) }

    fun open(player: ServerPlayer) {
        closeFor(player.uuid)
        val values = Values()
        val model = PropertySheetModel(
            listOf(
                IntPropertyField(
                    "signal", "Signal", 0, 15,
                    get = { values.signal }, set = { values.signal = it }
                ),
                BooleanPropertyField(
                    "enabled", "Enabled",
                    get = { values.enabled }, set = { values.enabled = it }
                ),
                ChoicePropertyField(
                    "mode", "Mode", listOf("Pulse", "Latch", "Clock"),
                    get = { values.mode }, set = { values.mode = it }
                )
            )
        )
        val ref = FabricPlayerRef(player)
        val look = ref.lookDirection()
        val center = ref.eyePosition() + look * 3.0
        val surface = Surface(window.size.w, window.size.h, Vec3d.ZERO, 2.7f).also {
            it.backingBlock = BlockStateRef.BLACK_CONCRETE
            window.configure(it)
        }

        lateinit var world: WorldSurfaceSession
        val view = PropertySheetView(
            id = "showcase-properties",
            model = model,
            isHovered = { SurfaceFocus.state(player.uuid).hoveredId == it },
            onChanged = { field ->
                world.repaint()
                player.displayClientMessage(
                    Component.literal("${field.label}: ${field.valueText}"),
                    true
                )
            }
        )
        surface.layout { root ->
            window.build(root, "Properties", onClose = { world.close() }) { body ->
                body.crossAxis = CrossAxis.CENTER
                body.addChild(view.node)
            }
        }
        world = WorldSurfaceSession(
            DisplayKit.platform,
            ref,
            surface,
            SurfaceAnchor.facing(center, look),
            SurfaceLifecyclePolicy.PERSISTENT,
            onClosed = { closeFor(player.uuid) }
        )
        val exclusivity = ShowcaseWindowGroup.claim(player.uuid) { closeFor(player.uuid) }
        val session = Session(world, exclusivity)
        open[player.uuid] = session

        PackSync.withPackSync("properties") { surface.paintTree() }
        FabricPackIntegration.whenPackApplied(player.uuid) {
            if (open[player.uuid] !== session) return@whenPackApplied
            world.open()
            PackSync.settled("properties")
        }
        player.sendSystemMessage(Component.literal("Property sheet open"))
    }

    fun closeFor(playerId: UUID) {
        val session = open.remove(playerId) ?: return
        session.exclusivity.close()
        session.world.close()
        if (open.isEmpty()) PackSync.forget("properties")
    }
}

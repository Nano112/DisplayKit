package io.schemat.displaykit.fabric.hud

import io.schemat.displaykit.fabric.player.FabricPlayerRef
import io.schemat.displaykit.hud.HudRenderer
import io.schemat.displaykit.hud.SidebarLine
import io.schemat.displaykit.hud.SidebarModel
import net.minecraft.network.chat.numbers.BlankFormat
import net.minecraft.network.protocol.game.ClientboundResetScorePacket
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.criteria.ObjectiveCriteria
import java.util.Optional

/**
 * Packet-only, per-player sidebar renderer.
 *
 * It never mutates the server's world scoreboard. Rows are reconciled by
 * [SidebarLine.key], so changing one value produces one score packet instead
 * of tearing down and recreating the whole HUD.
 */
class ScoreboardSidebarRenderer(
    private val player: ServerPlayer,
) : HudRenderer<SidebarModel> {
    private val scoreboard = Scoreboard()
    private var objective: Objective? = null
    private var activeId: String? = null
    private var activeTitle = io.schemat.displaykit.render.TextComponent.EMPTY
    private var previousLines = emptyList<SidebarLine>()
    private val owners = mutableMapOf<String, String>()
    private var nextOwner = 0

    override fun render(model: SidebarModel) {
        if (!model.visible) {
            close()
            return
        }

        val current = ensureObjective(model)
        val oldByKey = previousLines.withIndex().associate { it.value.key to it }
        val newKeys = model.lines.asSequence().map { it.key }.toSet()

        for (line in previousLines) {
            if (line.key !in newKeys) {
                player.connection.send(ClientboundResetScorePacket(ownerFor(line.key), current.name))
                owners.remove(line.key)
            }
        }

        model.lines.forEachIndexed { index, line ->
            val old = oldByKey[line.key]
            if (old == null || old.index != index || old.value.content != line.content) {
                player.connection.send(
                    ClientboundSetScorePacket(
                        ownerFor(line.key),
                        current.name,
                        model.lines.size - index,
                        Optional.of(FabricPlayerRef.toMinecraftText(line.content)),
                        Optional.of(BlankFormat.INSTANCE),
                    )
                )
            }
        }
        previousLines = model.lines
    }

    override fun close() {
        val current = objective ?: return
        player.connection.send(
            ClientboundSetObjectivePacket(current, ClientboundSetObjectivePacket.METHOD_REMOVE)
        )
        objective = null
        activeId = null
        activeTitle = io.schemat.displaykit.render.TextComponent.EMPTY
        previousLines = emptyList()
        owners.clear()
        nextOwner = 0
    }

    private fun ensureObjective(model: SidebarModel): Objective {
        if (activeId != model.id) {
            close()
            val nativeTitle = FabricPlayerRef.toMinecraftText(model.title)
            val created = scoreboard.addObjective(
                objectiveName(model.id),
                ObjectiveCriteria.DUMMY,
                nativeTitle,
                ObjectiveCriteria.RenderType.INTEGER,
                false,
                BlankFormat.INSTANCE,
            )
            objective = created
            activeId = model.id
            activeTitle = model.title
            player.connection.send(
                ClientboundSetObjectivePacket(created, ClientboundSetObjectivePacket.METHOD_ADD)
            )
            player.connection.send(ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, created))
            return created
        }

        val current = requireNotNull(objective)
        if (activeTitle != model.title) {
            current.displayName = FabricPlayerRef.toMinecraftText(model.title)
            activeTitle = model.title
            player.connection.send(
                ClientboundSetObjectivePacket(current, ClientboundSetObjectivePacket.METHOD_CHANGE)
            )
        }
        return current
    }

    private fun ownerFor(key: String): String = owners.getOrPut(key) {
        "dk_row_${(nextOwner++).toString(36)}"
    }

    private fun objectiveName(id: String): String = "dk_${id.hashCode().toUInt().toString(16)}".take(16)
}

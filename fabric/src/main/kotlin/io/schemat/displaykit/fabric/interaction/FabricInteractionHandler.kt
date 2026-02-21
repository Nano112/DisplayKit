package io.schemat.displaykit.fabric.interaction

import io.schemat.displaykit.ui.InteractionRouter
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

class FabricInteractionHandler(
    private val server: MinecraftServer
) {
    fun register() {
        // Safety net: prevent block breaking when targeting interactive surfaces.
        // Catches creative insta-break and any edge cases the mixin layer misses.
        PlayerBlockBreakEvents.BEFORE.register(PlayerBlockBreakEvents.Before { world, player, pos, state, entity ->
            if (player is ServerPlayer &&
                (InteractionRouter.isTargetingInteractive(player.uuid) ||
                 InteractionRouter.wasLeftClickConsumed(player.uuid))) {
                player.connection.send(ClientboundBlockUpdatePacket(world, pos))
                false
            } else {
                true
            }
        })
    }
}

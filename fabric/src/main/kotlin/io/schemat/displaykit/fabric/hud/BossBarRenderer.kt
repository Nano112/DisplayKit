package io.schemat.displaykit.fabric.hud

import io.schemat.displaykit.fabric.player.FabricPlayerRef
import io.schemat.displaykit.hud.HudRenderer
import io.schemat.displaykit.hud.ProgressBarColor
import io.schemat.displaykit.hud.ProgressBarModel
import io.schemat.displaykit.hud.ProgressBarOverlay
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import java.util.UUID

/** Retained renderer for a vanilla boss/progress bar and its viewer set. */
class BossBarRenderer : HudRenderer<ProgressBarModel> {
    private var event: ServerBossEvent? = null
    private var model: ProgressBarModel? = null
    private var desiredViewers = emptyMap<UUID, ServerPlayer>()

    override fun render(model: ProgressBarModel) {
        val current = event ?: ServerBossEvent(
            FabricPlayerRef.toMinecraftText(model.title),
            model.color.native(),
            model.overlay.native(),
        ).also { created ->
            event = created
            desiredViewers.values.forEach(created::addPlayer)
        }

        current.name = FabricPlayerRef.toMinecraftText(model.title)
        current.progress = model.progress
        current.color = model.color.native()
        current.overlay = model.overlay.native()
        current.isVisible = model.visible
        this.model = model
    }

    /** Reconcile who can see this bar without recreating it. */
    fun setViewers(players: Iterable<ServerPlayer>) {
        val next = players.associateBy { it.uuid }
        val current = event
        if (current != null) {
            desiredViewers.keys.asSequence()
                .filter { it !in next }
                .mapNotNull(desiredViewers::get)
                .forEach(current::removePlayer)
            next.keys.asSequence()
                .filter { it !in desiredViewers }
                .mapNotNull(next::get)
                .forEach(current::addPlayer)
        }
        desiredViewers = next
    }

    fun render(model: ProgressBarModel, viewers: Iterable<ServerPlayer>) {
        setViewers(viewers)
        render(model)
    }

    override fun close() {
        event?.removeAllPlayers()
        event = null
        model = null
        desiredViewers = emptyMap()
    }

    private fun ProgressBarColor.native(): BossEvent.BossBarColor = when (this) {
        ProgressBarColor.PINK -> BossEvent.BossBarColor.PINK
        ProgressBarColor.BLUE -> BossEvent.BossBarColor.BLUE
        ProgressBarColor.RED -> BossEvent.BossBarColor.RED
        ProgressBarColor.GREEN -> BossEvent.BossBarColor.GREEN
        ProgressBarColor.YELLOW -> BossEvent.BossBarColor.YELLOW
        ProgressBarColor.PURPLE -> BossEvent.BossBarColor.PURPLE
        ProgressBarColor.WHITE -> BossEvent.BossBarColor.WHITE
    }

    private fun ProgressBarOverlay.native(): BossEvent.BossBarOverlay = when (this) {
        ProgressBarOverlay.PROGRESS -> BossEvent.BossBarOverlay.PROGRESS
        ProgressBarOverlay.NOTCHED_6 -> BossEvent.BossBarOverlay.NOTCHED_6
        ProgressBarOverlay.NOTCHED_10 -> BossEvent.BossBarOverlay.NOTCHED_10
        ProgressBarOverlay.NOTCHED_12 -> BossEvent.BossBarOverlay.NOTCHED_12
        ProgressBarOverlay.NOTCHED_20 -> BossEvent.BossBarOverlay.NOTCHED_20
    }
}

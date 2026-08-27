package io.schemat.displaykit.velocity.input

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.UserDisconnectEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.player.DiggingAction
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook
import io.schemat.displaykit.surface.SurfaceFocus
import io.schemat.displaykit.ui.InteractionRouter
import java.util.UUID

/**
 * The single PacketEvents listener feeding DisplayKit's input contract.
 *
 * Serverbound movement fills the position cache, entity action tracks sneak,
 * clicks route into the InteractionRouter, held-item changes become surface
 * scroll, and the clientbound side keeps the cache honest across backend
 * teleports and server switches.
 *
 * Threading: this runs on netty. Cancel decisions are made synchronously
 * from volatile state ([SurfaceFocus], [InteractionRouter.isTargetingInteractive]);
 * everything that walks a surface tree is hopped onto the UI owner thread.
 * That is a deliberate semantic shift from Fabric's mixins, which decide
 * cancel-if-consumed on the server thread: a proxy cannot hold the packet
 * while another thread decides, so it cancels when the player is *targeting*
 * an interactive region and lets the dispatch settle afterwards.
 *
 * Digging is swallowed while targeting, and unlike Fabric there is no ghost
 * block resync: the proxy has no world state to resync from. Surfaces float
 * in front of the player, so the window where a real block sits behind one
 * is small; a misprediction heals on the next server-driven block update.
 */
class InputPacketListener(
    private val positions: PlayerPositionCache,
    private val scroll: HotbarScrollRestore,
    private val dispatchToUiThread: (Runnable) -> Unit,
    private val onPlayerRemoved: (UUID) -> Unit,
) : PacketListenerAbstract(PacketListenerPriority.NORMAL) {

    override fun onPacketReceive(event: PacketReceiveEvent) {
        val playerId = event.user.uuid ?: return

        when (event.packetType) {
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
            PacketType.Play.Client.PLAYER_ROTATION,
            PacketType.Play.Client.PLAYER_FLYING,
            -> {
                // The whole movement family shares the flying wrapper
                val wrapper = WrapperPlayClientPlayerFlying(event)
                val location = wrapper.location
                if (wrapper.hasPositionChanged()) {
                    positions.updatePosition(playerId, location.x, location.y, location.z)
                }
                if (wrapper.hasRotationChanged()) {
                    positions.updateRotation(playerId, location.yaw, location.pitch)
                }
                event.markForReEncode(false)
            }

            PacketType.Play.Client.ENTITY_ACTION -> {
                val wrapper = WrapperPlayClientEntityAction(event)
                when (wrapper.action) {
                    WrapperPlayClientEntityAction.Action.START_SNEAKING ->
                        positions.setSneaking(playerId, true)
                    WrapperPlayClientEntityAction.Action.STOP_SNEAKING ->
                        positions.setSneaking(playerId, false)
                    else -> {}
                }
                event.markForReEncode(false)
            }

            PacketType.Play.Client.ANIMATION -> {
                // A swing is never cancelled, matching Fabric: the arm wave is
                // harmless and the backend needs it for its own animation
                dispatchToUiThread(Runnable { InteractionRouter.onLeftClick(playerId) })
                event.markForReEncode(false)
            }

            PacketType.Play.Client.USE_ITEM,
            PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT,
            -> {
                if (InteractionRouter.isTargetingInteractive(playerId)) {
                    event.isCancelled = true
                    dispatchToUiThread(Runnable { InteractionRouter.onRightClick(playerId) })
                } else {
                    event.markForReEncode(false)
                }
            }

            PacketType.Play.Client.INTERACT_ENTITY -> {
                val wrapper = WrapperPlayClientInteractEntity(event)
                // Negative ids are this module's own virtual entities: the
                // backend does not know them, so their interactions must
                // never leak upstream regardless of targeting state
                val virtual = wrapper.entityId < 0
                if (virtual || InteractionRouter.isTargetingInteractive(playerId)) {
                    event.isCancelled = true
                    val rightClick = wrapper.action != WrapperPlayClientInteractEntity.InteractAction.ATTACK
                    dispatchToUiThread(Runnable {
                        if (rightClick) {
                            InteractionRouter.onRightClick(playerId)
                        } else {
                            InteractionRouter.onLeftClick(playerId)
                        }
                    })
                } else {
                    event.markForReEncode(false)
                }
            }

            PacketType.Play.Client.PLAYER_DIGGING -> {
                val wrapper = WrapperPlayClientPlayerDigging(event)
                val digging = wrapper.action == DiggingAction.START_DIGGING ||
                    wrapper.action == DiggingAction.CANCELLED_DIGGING ||
                    wrapper.action == DiggingAction.FINISHED_DIGGING
                if (digging && (InteractionRouter.isTargetingInteractive(playerId) ||
                        InteractionRouter.wasLeftClickConsumed(playerId))
                ) {
                    event.isCancelled = true
                } else {
                    event.markForReEncode(false)
                }
            }

            PacketType.Play.Client.HELD_ITEM_CHANGE -> {
                val wrapper = WrapperPlayClientHeldItemChange(event)
                if (scroll.onSlotChange(playerId, wrapper.slot)) {
                    event.isCancelled = true
                } else {
                    event.markForReEncode(false)
                }
            }
        }
    }

    override fun onPacketSend(event: PacketSendEvent) {
        val playerId = event.user.uuid ?: return

        when (event.packetType) {
            PacketType.Play.Server.JOIN_GAME, PacketType.Play.Server.RESPAWN -> {
                // Server switch: every coordinate and entity id belongs to the
                // old backend now
                positions.reset(playerId)
                scroll.forget(playerId)
                dispatchToUiThread(Runnable { InteractionRouter.cleanupPlayer(playerId) })
                event.markForReEncode(false)
            }

            PacketType.Play.Server.PLAYER_POSITION_AND_LOOK -> {
                // A backend teleport moves the player without the client
                // volunteering a movement packet first
                val wrapper = WrapperPlayServerPlayerPositionAndLook(event)
                val current = positions.snapshot(playerId)
                val base = current?.position
                val x = wrapper.x + if (wrapper.isRelativeFlag(RelativeFlag.X)) (base?.x ?: 0.0) else 0.0
                val y = wrapper.y + if (wrapper.isRelativeFlag(RelativeFlag.Y)) (base?.y ?: 0.0) else 0.0
                val z = wrapper.z + if (wrapper.isRelativeFlag(RelativeFlag.Z)) (base?.z ?: 0.0) else 0.0
                positions.updatePosition(playerId, x, y, z)

                val yaw = wrapper.yaw + if (wrapper.isRelativeFlag(RelativeFlag.YAW)) (current?.yaw ?: 0f) else 0f
                val pitch = wrapper.pitch +
                    if (wrapper.isRelativeFlag(RelativeFlag.PITCH)) (current?.pitch ?: 0f) else 0f
                positions.updateRotation(playerId, yaw, pitch)
                event.markForReEncode(false)
            }

            PacketType.Play.Server.HELD_ITEM_CHANGE -> {
                // The backend set the slot; without tracking it the scroll
                // anchor would restore a stale selection
                val wrapper = WrapperPlayServerHeldItemChange(event)
                scroll.trackSlot(playerId, wrapper.slot)
                event.markForReEncode(false)
            }
        }
    }

    override fun onUserDisconnect(event: UserDisconnectEvent) {
        val playerId = event.user.profile.uuid ?: return
        positions.remove(playerId)
        scroll.forget(playerId)
        dispatchToUiThread(Runnable { InteractionRouter.cleanupPlayer(playerId) })
        onPlayerRemoved(playerId)
    }
}

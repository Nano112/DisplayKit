package io.schemat.displaykit.fabric.mixin;

import io.schemat.displaykit.ui.InteractionRouter;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class InteractPacketMixin {

    @Shadow
    public ServerPlayer player;

    /**
     * Left-click entry point. Fires on every swing (including air).
     * Dispatch click via InteractionRouter. Never cancel — swing is cosmetic.
     */
    @Inject(method = "handleAnimate", at = @At("HEAD"))
    private void displaykit$onSwing(ServerboundSwingPacket packet, CallbackInfo ci) {
        if (packet.getHand() != InteractionHand.MAIN_HAND) return;
        if (InteractionRouter.INSTANCE.onLeftClick(player.getUUID())) {
            // Resync the targeted block to prevent ghost blocks from client-side prediction.
            // Must happen here (before handlePlayerAction) because the click dispatch may
            // change overlay state, causing isTargetingInteractive to miss on the next packet.
            HitResult hit = player.pick(5.0, 0.0f, false);
            if (hit instanceof BlockHitResult blockHit) {
                player.connection.send(new ClientboundBlockUpdatePacket(player.level(), blockHit.getBlockPos()));
            }
        }
        // Never cancel — arm swing animation is cosmetic
    }

    /**
     * Block-breaking actions. Do NOT cancel — vanilla must process the packet to send the
     * block prediction ack (since 1.19+). Without the ack, the client keeps its predicted
     * state (block broken) and ignores our resync packets.
     * The actual block break is prevented by PlayerBlockBreakEvents.BEFORE in FabricInteractionHandler.
     */

    /**
     * Right-click on block. Dispatch click, cancel + resync ghost block if consumed.
     */
    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void displaykit$onUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (packet.getHand() != InteractionHand.MAIN_HAND) return;

        if (InteractionRouter.INSTANCE.onRightClick(player.getUUID())) {
            // Resync block at the placement target to undo client-side ghost blocks
            BlockPos pos = packet.getHitResult().getBlockPos().relative(packet.getHitResult().getDirection());
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), pos));
            ci.cancel();
        }
    }

    /**
     * Right-click air (with item). Dispatch click, cancel if consumed.
     */
    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void displaykit$onUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (packet.getHand() != InteractionHand.MAIN_HAND) return;

        if (InteractionRouter.INSTANCE.onRightClick(player.getUUID())) {
            ci.cancel();
        }
    }

    /**
     * Entity interaction. Cancel if targeting interactive surface (prevents hitting
     * display entities that are part of UIs/overlays).
     */
    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void displaykit$onInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (InteractionRouter.INSTANCE.isTargetingInteractive(player.getUUID())) {
            // Entity-aimed clicks arrive as INTERACT packets, not use/swing —
            // dispatch them so left-click (ATTACK) works on panels too. The
            // per-side debounce dedupes against the swing-packet path.
            packet.dispatch(new ServerboundInteractPacket.Handler() {
                @Override
                public void onInteraction(net.minecraft.world.InteractionHand hand) {
                    if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                        InteractionRouter.INSTANCE.onRightClick(player.getUUID());
                    }
                }

                @Override
                public void onInteraction(net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.Vec3 pos) {
                    if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) {
                        InteractionRouter.INSTANCE.onRightClick(player.getUUID());
                    }
                }

                @Override
                public void onAttack() {
                    InteractionRouter.INSTANCE.onLeftClick(player.getUUID());
                }
            });
            ci.cancel();
        }
    }
}

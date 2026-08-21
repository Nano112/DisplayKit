package io.schemat.displaykit.fabric.mixin;

import io.schemat.displaykit.fabric.input.HotbarScrollCapture;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turns hotbar scrolling into surface scrolling while a scrollable is hovered.
 *
 * Vanilla's handleSetCarriedItem starts with
 * PacketUtils.ensureRunningOnSameThread(...), which normally throws to
 * reschedule the call onto the server thread -- so a HEAD injector fires
 * once on the netty thread and again on the server thread. Cancelling here
 * on the first (netty-thread) pass pre-empts that reschedule, so this
 * handler runs exactly once, on the netty thread. HotbarScrollCapture is
 * written with that in mind: it only reads volatile state on this thread
 * and hops to the server thread before touching any surface.
 *
 * Cancelling here is what stops the player's held item actually changing; the
 * capture then resends the original slot so the client snaps back.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class SetCarriedItemMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleSetCarriedItem", at = @At("HEAD"), cancellable = true)
    private void displaykit$onSlotChange(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        int slot = ((ServerboundSetCarriedItemPacketAccessor) packet).displaykit$getSlot();
        if (HotbarScrollCapture.INSTANCE.onSlotChange(player, slot)) {
            ci.cancel();
        }
    }
}

package io.schemat.displaykit.fabric.mixin;

import io.schemat.displaykit.fabric.pack.FabricPackIntegration;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes the client's answer to a resource pack push.
 *
 * Nothing used to watch this, so the server had no idea when a client had
 * actually applied a pack — it simply spawned surfaces the moment it resent
 * one. A glyph the client's font does not have yet renders as a missing-glyph
 * box with the DEFAULT advance instead of the sprite's, so every row measures
 * differently, the text block comes out the wrong width, and each layer
 * centres somewhere else. That is why the first open of a window was scattered
 * and the second was perfect: by then the pack had arrived.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public class ResourcePackResponseMixin {

    @Inject(method = "handleResourcePackResponse", at = @At("TAIL"))
    private void displaykit$onPackResponse(ServerboundResourcePackPacket packet, CallbackInfo ci) {
        // Only the play phase has a player to attribute the answer to; the
        // configuration-phase listener is a different subclass.
        if (!(((Object) this) instanceof ServerGamePacketListenerImpl listener)) return;
        ServerPlayer player = listener.player;
        if (player == null) return;
        FabricPackIntegration.INSTANCE.onPackResponse(player.getUUID(), packet.action().name());
    }
}

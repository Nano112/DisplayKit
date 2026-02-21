package io.schemat.displaykit.fabric.mixin;

import io.schemat.displaykit.fabric.FabricDisplayKit;
import io.schemat.displaykit.fabric.input.FabricTextInput;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class ChatPacketMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void displaykit$onChat(ServerboundChatPacket packet, CallbackInfo ci) {
        FabricTextInput textInput = FabricDisplayKit.Companion.getInstance().getTextInput();
        if (textInput == null) return;

        if (textInput.handleChatMessage(player.getUUID(), packet.message())) {
            ci.cancel();
        }
    }
}

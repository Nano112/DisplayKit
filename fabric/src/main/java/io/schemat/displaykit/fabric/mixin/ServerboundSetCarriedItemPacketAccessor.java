package io.schemat.displaykit.fabric.mixin;

import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** `slot` is a private field with no getter. */
@Mixin(ServerboundSetCarriedItemPacket.class)
public interface ServerboundSetCarriedItemPacketAccessor {
    @Accessor("slot")
    int displaykit$getSlot();
}

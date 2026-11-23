package xnmc.lootnotes.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xnmc.lootnotes.LootNotes;

@Mixin(ClientPacketListener.class)
public abstract class ChestPacketMixin {
    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void xnmc$observeChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        LootNotes.chunkPacket(packet);
    }
    @Inject(method = "handleBlockEntityData", at = @At("TAIL"))
    private void xnmc$observeUpdate(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        LootNotes.blockEntityPacket(packet.getPos(), packet.getType(), packet.getTag());
    }
}

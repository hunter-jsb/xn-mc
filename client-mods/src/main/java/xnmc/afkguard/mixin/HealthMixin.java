package xnmc.afkguard.mixin;
import xnmc.afkguard.AfkGuard;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientPacketListener.class)
public abstract class HealthMixin {
    // TAIL runs after packet thread scheduling and applying health/food, including between ticks.
    @Inject(method = "handleSetHealth", at = @At("TAIL"))
    private void xnmc$healthUpdate(ClientboundSetHealthPacket packet, CallbackInfo ci) { AfkGuard.healthPacket(); }
}

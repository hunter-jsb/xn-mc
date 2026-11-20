package dev.fieldnotes.guard.mixin;
import dev.fieldnotes.guard.GuardClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ConnectScreen.class)
public abstract class ConnectMixin {
    @Shadow private volatile boolean aborted;
    @Shadow private volatile net.minecraft.network.Connection connection;
    @Shadow private io.netty.channel.ChannelFuture channelFuture;
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void fieldnotes$blockWhileLocked(Screen parent, Minecraft mc, ServerAddress address, ServerData data,
            boolean quickPlay, TransferState transfer, CallbackInfo ci) {
        if (GuardClient.guard != null && !GuardClient.guard.permitConnection(GuardClient.approvedConnect())) {
            ci.cancel();
            GuardClient.open(new TitleScreen());
        }
    }
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void fieldnotes$abortPendingOnLock(CallbackInfo ci) {
        if (GuardClient.guard != null && GuardClient.guard.locked()) {
            aborted = true;
            if (channelFuture != null) channelFuture.cancel(true);
            if (connection != null) connection.disconnect(net.minecraft.network.chat.Component.literal("AFK Guard safety lock"));
            ci.cancel();
            GuardClient.open(new TitleScreen());
        }
    }
}

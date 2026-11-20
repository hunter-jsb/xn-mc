package dev.fieldnotes.guard;

import dev.fieldnotes.guard.mixin.DisconnectedAccessor;
import java.io.IOException;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class GuardClient implements ClientModInitializer {
    public static Guard guard;
    private static ServerData server;
    private static Screen processedDisconnect;
    private static boolean online;
    private static boolean wasHurt;
    private static boolean approvedConnect;
    private static String lastNotice = "";
    public static long now() { return System.nanoTime() / 1_000_000; }
    public static boolean approvedConnect() { return approvedConnect; }

    public void onInitializeClient() {
        guard = new StateFile(FabricLoader.getInstance().getConfigDir().resolve("fieldnotes-afk-guard-state.properties")).load();
        ClientTickEvents.START_CLIENT_TICK.register(GuardClient::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(literal("afkguard")
                .executes(c -> { Minecraft.getInstance().schedule(() -> open(Minecraft.getInstance().gui.screen())); return 1; })
                .then(literal("arm").executes(c -> { arm(); return 1; }))
                .then(literal("off").executes(c -> { disable(); return 1; }))
                .then(literal("status").executes(c -> { notice(status()); return 1; }))
                .then(literal("test-restart").executes(c -> { testRestart(); return 1; }))
                .then(literal("test").executes(c -> { test(); return 1; }))));
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
            if (screen instanceof TitleScreen || screen instanceof PauseScreen || screen instanceof DisconnectedScreen)
                Screens.getWidgets(screen).add(Button.builder(Component.literal(guard.locked() ? "AFK Guard: LOCKED" : "AFK Guard"),
                        b -> open(screen)).bounds(Math.max(4, width - 150), 5, 145, 20).build());
            if (screen instanceof DisconnectedScreen) ScreenEvents.afterExtract(screen).register((s, graphics, mx, my, delta) ->
                    graphics.textWithWordWrap(mc.font, Component.literal("AFK Guard: " + status()), 12, Math.max(30, s.height - 42),
                            Math.max(100, s.width - 24), guard.locked() ? 0xFFFF7777 : 0xFFFFCC66));
        });
        System.getLogger("Fieldnotes AFK Guard").log(System.Logger.Level.INFO, "Loaded: " + status());
    }
    public static String status() {
        String s = guard.mode() + ": " + guard.reason();
        if (guard.nextRetry() != 0) s += " | retry in " + Math.max(0, (guard.nextRetry() - now() + 999) / 1000) + "s";
        return s;
    }
    public static void open(Screen parent) { Minecraft.getInstance().gui.setScreen(new GuardScreen(parent)); }
    public static void notice(String text) {
        lastNotice = text;
        var player = Minecraft.getInstance().player;
        if (player != null) Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("[AFK Guard] " + text));
        System.getLogger("Fieldnotes AFK Guard").log(System.Logger.Level.INFO, text);
    }
    public static String lastNotice() { return lastNotice; }
    public static void arm() {
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        if (p == null || mc.getCurrentServer() == null) { notice("Join your server before arming"); return; }
        if (p.hurtTime > 0 || !p.isAlive()) { notice("Wait until healed and no longer taking damage"); return; }
        var address = ServerAddress.parseString(mc.getCurrentServer().ip);
        if (!address.getHost().equalsIgnoreCase("justanotherhardcore.net") || address.getPort() != 25565) {
            notice("This build is scoped to justanotherhardcore.net:25565"); return;
        }
        try {
            guard.arm(p.getHealth(), p.getMaxHealth(), p.getAbsorptionAmount(), p.getFoodData().getFoodLevel(), now(), 12 * 60 * 60_000L);
            server = mc.getCurrentServer();
            online = true;
            wasHurt = false;
            processedDisconnect = null;
            notice("ARMED for 12 hours: first damage locks you offline; recognized restart/idle kicks may reconnect");
        } catch (IOException | RuntimeException e) { notice("Could not arm: " + e.getMessage()); }
    }
    public static void disable() {
        try { guard.off(); notice(status()); }
        catch (IOException e) { notice("State write failed; safety lock retained"); }
    }
    public static void acknowledge() {
        // Must be offline; cannot silently clear a trip and continue the AFK session.
        if (Minecraft.getInstance().player != null) { notice("Disconnect before acknowledging a safety lock"); return; }
        try { guard.acknowledge(); notice("Lock cleared. Monitoring is OFF; return manually and inspect before rearming"); }
        catch (IOException e) { notice("Cannot write state; safety lock retained"); }
    }
    public static void test() {
        if (!guard.armed()) { notice("Arm AFK Guard first, then run /afkguard test"); return; }
        guard.lock("Manual safety test; no damage was needed");
        disconnectSafety();
    }
    public static void testRestart() {
        Minecraft mc = Minecraft.getInstance();
        if (!guard.armed() || mc.getConnection() == null) { notice("Arm AFK Guard before testing a restart disconnect"); return; }
        notice("Manual benign-kick test: disconnecting now; first retry in thirty seconds");
        mc.getConnection().getConnection().disconnect(Component.literal("Server closed"));
    }
    public static void healthPacket() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && guard != null && guard.armed()) {
            var p = mc.player;
            if (guard.sample(p.getHealth(), p.getAbsorptionAmount(), p.getFoodData().getFoodLevel(), false, now())) disconnectSafety();
        }
    }
    private static void disconnectSafety() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            notice("SAFETY LOCK: " + guard.reason() + "; automatic reconnect blocked");
            // Close normally. No deliberately invalid packets and no restart-message heuristics.
            mc.getConnection().getConnection().disconnect(Component.literal("AFK Guard safety lock: " + guard.reason()));
        }
    }
    private static void tick(Minecraft mc) {
        if (guard.locked()) { if (mc.player != null) disconnectSafety(); return; }
        if (!guard.armed()) return;
        if (guard.expire(now())) { disconnectSafety(); return; }
        if (mc.player != null && mc.level != null) {
            if (!online) {
                if (server == null || mc.getCurrentServer() == null || !server.ip.equalsIgnoreCase(mc.getCurrentServer().ip)) {
                    guard.lock("Unexpected server change during AFK session"); disconnectSafety(); return;
                }
                guard.joined();
                notice("Rejoined; AFK monitoring remains armed");
            }
            online = true;
            processedDisconnect = null;
            var p = mc.player;
            boolean hurt = p.hurtTime > 0;
            if (guard.sample(p.getHealth(), p.getAbsorptionAmount(), p.getFoodData().getFoodLevel(), hurt && !wasHurt, now())) disconnectSafety();
            wasHurt = hurt;
            return;
        }
        online = false;
        if (mc.gui.screen() instanceof DisconnectedScreen && processedDisconnect != mc.gui.screen()) {
            processedDisconnect = mc.gui.screen();
            String message = ((DisconnectedAccessor) mc.gui.screen()).fieldnotes$getDetails().reason().getString();
            guard.disconnected(message, now());
            notice(status());
        } else if (mc.gui.screen() instanceof TitleScreen || mc.gui.screen() instanceof net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen) {
            // Manual quit, or leaving the retry screen, cancels the AFK session.
            disable();
            return;
        }
        if (mc.gui.screen() instanceof DisconnectedScreen && server != null && guard.takeRetry(now())) {
            notice("Reconnect attempt " + guard.attempts());
            approvedConnect = true;
            try { ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(server.ip), server, false, null); }
            finally { approvedConnect = false; }
        }
    }
}

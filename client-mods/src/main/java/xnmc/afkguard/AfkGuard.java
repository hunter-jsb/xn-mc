package xnmc.afkguard;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import xnmc.Feature;
import xnmc.FeatureScreen;
import xnmc.Xnmc;
import xnmc.afkguard.mixin.DisconnectedAccessor;

public final class AfkGuard {
    private static final System.Logger LOG = System.getLogger("xnmc/afkguard");
    private static final Guard guard = new Guard();
    private static ServerData server;
    private static Screen handledDisconnect;
    private static boolean online, wasHurt;

    public static long now() { return System.nanoTime() / 1_000_000; }

    public static boolean armed() { return guard.armed(); }
    public static void off() { guard.off("off"); say("off."); }

    public static Feature feature() {
        return new Feature("AFK Guard", AfkGuard::status, s ->
                s.row(FeatureScreen.act("Arm for 12 hours", AfkGuard::arm), FeatureScreen.act("Off", AfkGuard::off)));
    }

    public static void init() {
        ClientTickEvents.START_CLIENT_TICK.register(AfkGuard::tick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(literal("afkguard")
                .executes(c -> { say(status()); return 1; })
                .then(literal("arm").executes(c -> { arm(); return 1; }))
                .then(literal("off").executes(c -> { off(); return 1; }))
                .then(literal("test").executes(c -> { test(); return 1; }))
                .then(literal("test-restart").executes(c -> { testRestart(); return 1; }))));
        // The disconnect screen shows the reconnect countdown; otherwise the guard stays out of sight.
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
            if (screen instanceof DisconnectedScreen) ScreenEvents.afterExtract(screen).register((s, graphics, mx, my, delta) -> {
                if (guard.armed()) graphics.textWithWordWrap(mc.font, Component.literal("AFK Guard: " + status()),
                        12, Math.max(30, s.height - 42), Math.max(100, s.width - 24), 0xFFFFCC66);
            });
        });
    }

    public static String status() {
        if (!guard.armed()) return guard.reason();
        if (guard.nextRetry() != 0)
            return "reconnecting in " + Math.max(0, (guard.nextRetry() - now() + 999) / 1000) + "s (attempt " + (guard.attempts() + 1) + ")";
        long left = Math.max(0, guard.deadline() - now()) / 60_000;
        return "armed, " + left / 60 + "h " + left % 60 + "m left";
    }

    private static void say(String text) {
        LOG.log(System.Logger.Level.INFO, text);
        Xnmc.say("AFK Guard", ChatFormatting.GOLD, text);
    }

    public static void arm() {
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        if (p == null || mc.getCurrentServer() == null) { say("join the server first."); return; }
        if (p.hurtTime > 0 || !p.isAlive()) { say("wait until you've stopped taking damage."); return; }
        var address = ServerAddress.parseString(mc.getCurrentServer().ip);
        if (!address.getHost().equalsIgnoreCase("justanotherhardcore.net") || address.getPort() != 25565) {
            say("only arms on justanotherhardcore.net."); return;
        }
        try {
            guard.arm(p.getHealth(), p.getMaxHealth(), p.getAbsorptionAmount(), p.getFoodData().getFoodLevel(), now(), 12 * 60 * 60_000L);
        } catch (IllegalStateException e) { say("can't arm: " + e.getMessage() + "."); return; }
        server = mc.getCurrentServer();
        online = true;
        wasHurt = false;
        handledDisconnect = null;
        say("armed for 12 hours. Any damage logs you out.");
    }

    private static void test() {
        if (!guard.armed()) { say("arm first."); return; }
        guard.off("test logout");
        logout();
    }

    private static void testRestart() {
        Minecraft mc = Minecraft.getInstance();
        if (!guard.armed() || mc.getConnection() == null) { say("arm first."); return; }
        mc.getConnection().getConnection().disconnect(Component.literal("Server closed"));
    }

    public static void healthPacket() {
        var p = Minecraft.getInstance().player;
        if (p != null && guard.armed() && guard.sample(p.getHealth(), p.getAbsorptionAmount(), p.getFoodData().getFoodLevel(), false, now()))
            logout();
    }

    // The guard is already off, so nothing reconnects; the disconnect screen carries the reason.
    private static void logout() {
        Minecraft mc = Minecraft.getInstance();
        LOG.log(System.Logger.Level.INFO, "logging out: " + guard.reason());
        if (mc.getConnection() != null) mc.getConnection().getConnection().disconnect(Component.literal("AFK Guard: " + guard.reason()));
    }

    private static void tick(Minecraft mc) {
        if (!guard.armed()) return;
        if (mc.player != null && mc.level != null) {
            if (!online) {
                guard.joined();
                say("rejoined, still armed.");
            }
            online = true;
            handledDisconnect = null;
            var p = mc.player;
            boolean hurt = p.hurtTime > 0;
            if (guard.sample(p.getHealth(), p.getAbsorptionAmount(), p.getFoodData().getFoodLevel(), hurt && !wasHurt, now())) logout();
            wasHurt = hurt;
            return;
        }
        online = false;
        Screen screen = mc.gui.screen();
        if (screen instanceof DisconnectedScreen && screen != handledDisconnect) {
            handledDisconnect = screen;
            guard.disconnected(((DisconnectedAccessor) screen).xnmc$getDetails().reason().getString(), now());
            LOG.log(System.Logger.Level.INFO, status());
        } else if (screen instanceof TitleScreen || screen instanceof JoinMultiplayerScreen) {
            // Leaving the disconnect screen yourself ends the AFK session.
            guard.off("off");
            return;
        }
        if (screen instanceof DisconnectedScreen && server != null && guard.takeRetry(now())) {
            LOG.log(System.Logger.Level.INFO, "reconnect attempt " + guard.attempts());
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(server.ip), server, false, null);
        }
    }
}

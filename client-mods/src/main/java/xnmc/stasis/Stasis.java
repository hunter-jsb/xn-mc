package xnmc.stasis;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import xnmc.Feature;
import xnmc.FeatureScreen;
import xnmc.Xnmc;
import xnmc.afkguard.AfkGuard;
import xnmc.afkguard.mixin.DisconnectedAccessor;

public final class Stasis {
    private static final System.Logger LOG = System.getLogger("xnmc/stasis");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson AUDIT_JSON = new Gson();
    private static final RecallTimer timer = new RecallTimer();
    private static final String LOGOUT_REASON = "XNMC Stasis: intentional timed recall";
    private static List<Destination> destinations = new ArrayList<>();
    private static String selected = "", label = "", activeServer = "";
    private static ServerData reconnectServer;
    private static ClientPacketListener departing;
    private static boolean disconnectedScreenSeen;
    private static long connectStarted;
    private static String trialId = "";
    private static long requestedWait;

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("xnmc-stasis.json"); }
    private static long now() { return System.nanoTime() / 1_000_000; }
    private static String seconds(long ms) { return String.format(Locale.ROOT, "%.2f", ms / 1000.0); }
    private static void say(String text) { LOG.log(System.Logger.Level.INFO, text); Xnmc.say("Stasis", ChatFormatting.AQUA, text); }
    private static String serverKey(ServerData server) {
        var address = ServerAddress.parseString(server.ip);
        return address.getHost().toLowerCase(Locale.ROOT) + ":" + address.getPort();
    }
    private static String currentServer() {
        var server = Minecraft.getInstance().getCurrentServer();
        if (server == null) throw new IllegalStateException("Join the server first.");
        return serverKey(server);
    }
    private static List<Destination> here() {
        var server = Minecraft.getInstance().getCurrentServer();
        return server == null ? List.of() : destinations.stream().filter(d -> d.server().equals(serverKey(server))).toList();
    }
    private static Destination find(String name) {
        return here().stream().filter(d -> d.name().equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No destination named " + name + " on this server."));
    }
    private static Destination selected() {
        var list = here();
        return list.stream().filter(d -> d.name().equals(selected)).findFirst().orElse(list.isEmpty() ? null : list.getFirst());
    }
    private static String selectionLabel() {
        var d = selected();
        return d == null ? "No destinations on this server" : d.name() + (d.configured()
                ? " · " + seconds(d.minWaitMs()) + "–" + seconds(d.maxWaitMs()) + "s" : " · timing not measured");
    }
    private static void cycle() {
        var list = here();
        if (list.isEmpty()) return;
        int index = list.indexOf(selected());
        selected = list.get((index + 1) % list.size()).name();
    }
    public static String status() {
        if (timer.state() == RecallTimer.State.WAITING)
            return label + ": reconnect attempt in " + seconds(timer.remaining(now())) + "s";
        return (timer.active() ? label + ": " : "") + timer.message();
    }
    private static void action(Runnable run) {
        try { run.run(); } catch (IllegalArgumentException | IllegalStateException e) { say(e.getMessage()); }
    }

    public static Feature feature() {
        return new Feature("Stasis", Stasis::status, s -> {
            s.choice(Stasis::selectionLabel, Stasis::cycle);
            var name = s.field("Destination name (or selected)");
            var window = s.field("Measured wait: min max seconds (blank = draft)");
            s.row(FeatureScreen.act("Save destination", () -> {
                try {
                    String n = name.getValue().strip();
                    if (n.isEmpty() && selected() != null) n = selected().name();
                    String text = window.getValue().strip();
                    String[] values = text.split("\\s+");
                    if (text.isEmpty()) save(new Destination(currentServer(), n, 0, 0));
                    else {
                        if (values.length != 2) throw new IllegalArgumentException("Enter min max in seconds.");
                        save(new Destination(currentServer(), n, millis(values[0]), millis(values[1])));
                    }
                } catch (IllegalArgumentException | IllegalStateException e) { s.error(e.getMessage()); }
            }), FeatureScreen.act("Remove selected", () -> {
                try { var d = selected(); if (d == null) throw new IllegalStateException("Select a destination first."); remove(d.name()); }
                catch (IllegalStateException | IllegalArgumentException e) { s.error(e.getMessage()); }
            }));
            s.row(FeatureScreen.act("Recall selected", () -> {
                try { var d = selected(); if (d == null) throw new IllegalStateException("Add a destination first."); recall(d); }
                catch (IllegalStateException | IllegalArgumentException e) { s.error(e.getMessage()); }
            }), FeatureScreen.act("Cancel timer", Stasis::cancel));
        });
    }

    public static void init() {
        load();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(literal("stasis")
                .executes(c -> { say(status()); say("/stasis list | add <name> | window <name> <min> <max> | recall <name> | test <seconds> | cancel"); return 1; })
                .then(literal("list").executes(c -> { for (var d : here()) say(d.name() + (d.configured() ? ": " + seconds(d.minWaitMs()) + "–" + seconds(d.maxWaitMs()) + "s; target " + seconds(d.targetMs()) + "s" : ": draft, no timing window")); return 1; }))
                .then(literal("add").then(argument("name", StringArgumentType.word()).executes(c -> {
                    action(() -> { var n = StringArgumentType.getString(c, "name");
                        if (here().stream().anyMatch(d -> d.name().equalsIgnoreCase(n))) throw new IllegalArgumentException("Destination already exists; use /stasis window.");
                        save(new Destination(currentServer(), n, 0, 0)); }); return 1; })))
                .then(literal("window").then(argument("name", StringArgumentType.word())
                        .then(argument("min", DoubleArgumentType.doubleArg(0.25, 600))
                        .then(argument("max", DoubleArgumentType.doubleArg(0.25, 600)).executes(c -> {
                            action(() -> { var d = find(StringArgumentType.getString(c, "name"));
                                save(new Destination(d.server(), d.name(), Math.round(DoubleArgumentType.getDouble(c, "min") * 1000), Math.round(DoubleArgumentType.getDouble(c, "max") * 1000))); }); return 1; })))))
                .then(literal("remove").then(argument("name", StringArgumentType.word()).executes(c -> { action(() -> remove(StringArgumentType.getString(c, "name"))); return 1; })))
                .then(literal("recall").then(argument("name", StringArgumentType.word()).executes(c -> { action(() -> recall(find(StringArgumentType.getString(c, "name")))); return 1; })))
                .then(literal("test").then(argument("seconds", DoubleArgumentType.doubleArg(0.25, 599.75)).executes(c -> {
                    action(() -> { long wait = Math.round(DoubleArgumentType.getDouble(c, "seconds") * 1000); start("TEST " + seconds(wait) + "s", wait, wait + 250); }); return 1; })))
                .then(literal("result").then(argument("outcome", StringArgumentType.word()).executes(c -> {
                    action(() -> {
                        String outcome = StringArgumentType.getString(c, "outcome");
                        if (!List.of("recalled", "missed", "wrong-destination").contains(outcome))
                            throw new IllegalArgumentException("Use recalled, missed, or wrong-destination.");
                        if (timer.active() || trialId.isEmpty() || timer.attemptAt() == 0 || !currentServer().equals(activeServer))
                            throw new IllegalStateException("Complete a timed recall attempt on this server first.");
                        report("observed by player: " + outcome);
                        say("Recorded " + outcome + " for " + label + ".");
                    }); return 1;
                })))
                .then(literal("cancel").executes(c -> { cancel(); return 1; }))));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
            if (timer.state() == RecallTimer.State.DISCONNECTING && handler == departing) timer.disconnected(now());
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            if (!timer.active()) return;
            boolean matching = mc.getCurrentServer() != null && activeServer.equals(serverKey(mc.getCurrentServer()));
            if (matching) timer.joined(); else timer.cancel("Joined another server; timer cancelled");
            report("joined");
            say(timer.message());
        });
        ClientTickEvents.START_CLIENT_TICK.register(Stasis::tick);
        ScreenEvents.AFTER_INIT.register((mc, screen, width, height) -> {
            if (screen instanceof DisconnectedScreen && timer.active()) {
                Screens.getWidgets(screen).add(Button.builder(Component.literal("Cancel stasis reconnect"), b -> cancel())
                        .bounds(Math.max(4, width / 2 - 110), 8, 220, 20).build());
                ScreenEvents.afterExtract(screen).register((s, g, mx, my, dt) ->
                        g.textWithWordWrap(mc.font, Component.literal("Stasis: " + status()), 12,
                                Math.max(35, s.height - 44), Math.max(100, s.width - 24), 0xFF88DDFF));
            }
        });
    }

    private static long millis(String seconds) {
        double value = Double.parseDouble(seconds);
        if (!Double.isFinite(value) || value < 0.25 || value > 600) throw new IllegalArgumentException("Use seconds within 0.25–600.");
        return Math.round(value * 1000);
    }
    private static void save(Destination d) {
        if (timer.active()) throw new IllegalStateException("Cancel the recall before changing destinations.");
        d.checkOverlap(destinations);
        var next = new ArrayList<>(destinations);
        next.removeIf(old -> old.server().equals(d.server()) && old.name().equals(d.name()));
        next.add(d);
        persist(next);
        destinations = next;
        selected = d.name();
        say("Saved " + d.name() + (d.configured() ? "; target wait " + seconds(d.targetMs()) + "s." : " as a draft; measure its timing window first."));
    }
    private static void remove(String name) {
        if (timer.active()) throw new IllegalStateException("Cancel the recall first.");
        var d = find(name);
        var next = new ArrayList<>(destinations);
        next.remove(d);
        persist(next);
        destinations = next;
        say("Removed " + d.name() + ".");
    }
    private static void load() {
        if (!Files.exists(file())) return;
        try {
            var loaded = JSON.fromJson(Files.readString(file()), Destination[].class);
            if (loaded == null || loaded.length > 256) throw new IllegalArgumentException("Invalid destination list");
            var checked = new ArrayList<Destination>();
            for (var d : loaded) {
                if (d == null) throw new IllegalArgumentException("Null destination");
                // Constructor validation also covers records deserialized by older Gson versions.
                var validated = new Destination(d.server(), d.name(), d.minWaitMs(), d.maxWaitMs());
                validated.checkOverlap(checked);
                if (checked.stream().anyMatch(old -> old.server().equals(validated.server()) && old.name().equals(validated.name())))
                    throw new IllegalArgumentException("Duplicate destination");
                checked.add(validated);
            }
            destinations = checked;
        } catch (Exception e) { LOG.log(System.Logger.Level.ERROR, "Could not read stasis destinations; file left intact", e); configError = true; }
    }
    private static boolean configError;
    private static void persist(List<Destination> list) {
        if (configError) throw new IllegalStateException("Stasis config could not be read; repair xnmc-stasis.json first (it has been preserved).");
        if (list.size() > 256) throw new IllegalArgumentException("Maximum 256 destinations.");
        try {
            Files.createDirectories(file().getParent());
            var temp = Files.createTempFile(file().getParent(), "xnmc-stasis-", ".tmp");
            try {
                Files.writeString(temp, JSON.toJson(list));
                Files.move(temp, file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        } catch (Exception e) { throw new IllegalStateException("Could not save destinations: " + e.getMessage(), e); }
    }

    private static void recall(Destination d) {
        if (!d.configured()) throw new IllegalStateException("No measured timing window for " + d.name() + "; use /stasis test <seconds> to calibrate.");
        start(d.name(), d.targetMs(), d.maxWaitMs());
    }
    private static void start(String destination, long wait, long latest) {
        var mc = Minecraft.getInstance();
        if (timer.active()) throw new IllegalStateException("A recall is already scheduled.");
        if (mc.player == null || mc.level == null || mc.getConnection() == null || mc.getCurrentServer() == null || !mc.player.isAlive())
            throw new IllegalStateException("Join a multiplayer server while alive first.");
        if (!mc.getConnection().getConnection().isConnected()) throw new IllegalStateException("Already disconnecting; recall cancelled.");
        if (MeteorReconnect.disable()) say("Meteor AutoReconnect turned off; left off to prevent competing reconnects.");
        AfkGuard.suspendForStasis();
        label = destination;
        trialId = UUID.randomUUID().toString();
        requestedWait = wait;
        reconnectServer = mc.getCurrentServer();
        activeServer = currentServer();
        departing = mc.getConnection();
        disconnectedScreenSeen = false;
        timer.start(wait, latest, now());
        say(destination + ": disconnecting; reconnect attempt after " + seconds(wait) + "s. Chamber must be armed and independently loaded.");
        mc.getConnection().getConnection().disconnect(Component.literal(LOGOUT_REASON));
    }
    public static void cancel() {
        if (timer.state() == RecallTimer.State.CONNECTING) {
            // Once sent, a login cannot be made to have never happened. Only cancel XNMC's pending action.
            say("Reconnect already started; use the connection screen's Cancel button to abort it.");
            return;
        }
        if (timer.active()) { timer.cancel("Cancelled; staying disconnected"); report("cancelled"); }
        say(timer.message());
    }
    private static void tick(Minecraft mc) {
        if (!timer.active()) return;
        var screen = mc.gui.screen();
        long time = now();
        timer.expire(time);
        if (!timer.active()) { report("timing expired"); return; }
        if (timer.state() == RecallTimer.State.CONNECTING) {
            if (screen instanceof DisconnectedScreen) timer.disconnected(time);
            else if (mc.player == null && !(screen instanceof ConnectScreen)
                    && time - connectStarted > 1000) timer.cancel("Connection left or cancelled; no retry");
            if (!timer.active()) report("connection ended");
            return;
        }
        if (screen instanceof DisconnectedScreen) {
            String reason = ((DisconnectedAccessor) screen).xnmc$getDetails().reason().getString();
            if (!reason.equals(LOGOUT_REASON)) { timer.cancel("Unexpected disconnect; no timed reconnect"); report("unexpected disconnect"); return; }
            disconnectedScreenSeen = true;
        } else if (disconnectedScreenSeen || (timer.state() == RecallTimer.State.WAITING && mc.getConnection() != null)) {
            timer.cancel("Left the disconnect screen; timer cancelled"); report("cancelled"); return;
        }
        // Don't consume the one attempt until the expected disconnect screen exists.
        if (timer.state() == RecallTimer.State.WAITING && !(screen instanceof DisconnectedScreen)) {
            return;
        }
        boolean wasActive = timer.active();
        if (timer.takeAttempt(time)) {
            try {
                MeteorReconnect.disable();
                connectStarted = time;
                report("attempt");
                ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(reconnectServer.ip), reconnectServer, false, null);
            } catch (RuntimeException e) { timer.cancel("Could not reconnect; no retry: " + e.getMessage()); report("connection error"); }
        } else if (wasActive && !timer.active()) report("missed window");
    }
    private static void report(String event) {
        // Local log only; rejoining is not evidence that the pearl actually teleported the player.
        LOG.log(System.Logger.Level.INFO, "{0}: {1} on {2}; attempt wait={3}ms; {4}", event, label, activeServer,
                timer.attemptAt() == 0 ? "none" : timer.attemptAt() - timer.disconnectedAt(), timer.message());
        try {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("trial", trialId);
            entry.put("time", Instant.now().toString());
            entry.put("event", event);
            entry.put("destination", label);
            entry.put("server", activeServer);
            entry.put("requestedWaitMs", requestedWait);
            if (timer.attemptAt() != 0) entry.put("actualWaitMs", timer.attemptAt() - timer.disconnectedAt());
            if (event.equals("joined")) {
                entry.put("disconnectToJoinMs", now() - timer.disconnectedAt());
                var mc = Minecraft.getInstance();
                if (mc.level != null && mc.player != null) {
                    entry.put("dimensionAtJoin", mc.level.dimension().identifier().toString());
                    entry.put("positionAtJoin", List.of(mc.player.getX(), mc.player.getY(), mc.player.getZ()));
                }
            }
            entry.put("status", timer.message());
            Path output = FabricLoader.getInstance().getGameDir().resolve("xnmc/stasis-trials.jsonl");
            Files.createDirectories(output.getParent());
            Files.writeString(output, AUDIT_JSON.toJson(entry) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { LOG.log(System.Logger.Level.WARNING, "Could not save stasis trial record", e); }
    }
}

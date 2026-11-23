package xnmc.remote;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import xnmc.Baritone;
import xnmc.Feature;
import xnmc.FeatureScreen;
import xnmc.Xnmc;
import xnmc.afkguard.AfkGuard;
import xnmc.afkguard.mixin.DisconnectedAccessor;

// A run nobody is watching, seen and steered from outside the game. Under <game>/xnmc it keeps status.json (how
// things stand, every five seconds, in a world or not) and events.jsonl (every line XNMC says), and it runs the
// orders left in inbox/. What needs someone goes to Discord through a webhook. Nothing here listens on the network.
public final class Remote {
	private static final Logger LOG = LoggerFactory.getLogger("xnmc/remote");
	private static final int STATUS_TICKS = 100, INBOX_TICKS = 20;
	private static final long ROTATE_BYTES = 5L << 20;
	private static final Wire.Throttle throttle = new Wire.Throttle();
	private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static String webhook = "";
	private static boolean alerts = true;
	private static int ticks;
	// The disconnect screen already told of, and where he last stood: a disconnect has no player to ask.
	private static Screen told;
	private static String where = "";
	private static boolean warned;

	private static Path dir() { return FabricLoader.getInstance().getGameDir().resolve("xnmc"); }
	private static Path settings() { return FabricLoader.getInstance().getConfigDir().resolve("xnmc-remote.properties"); }

	public static Feature feature() {
		return new Feature("Remote", Remote::status, s ->
				s.row(FeatureScreen.act("Send test alert", Remote::test), FeatureScreen.act(alerts ? "Alerts off" : "Alerts on", () -> setAlerts(!alerts))));
	}

	public static void init() {
		Properties saved = new Properties();
		try (var in = Files.newBufferedReader(settings())) {
			saved.load(in);
			webhook = saved.getProperty("webhook", "").strip();
			alerts = !"false".equals(saved.getProperty("alerts"));
		} catch (IOException e) { /* nothing saved yet */ }
		try { Files.createDirectories(dir().resolve("inbox")); }
		catch (IOException e) { LOG.warn("Could not make {}", dir(), e); }
		ClientTickEvents.END_CLIENT_TICK.register(Remote::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			ServerData server = mc.getCurrentServer();
			event("Remote", "joined " + (server != null ? server.ip : "a world"));
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(literal("xnmc").then(literal("remote")
				.executes(c -> { say(status()); return 1; })
				.then(literal("test").executes(c -> { test(); return 1; }))
				.then(literal("alerts")
						.then(literal("on").executes(c -> { setAlerts(true); return 1; }))
						.then(literal("off").executes(c -> { setAlerts(false); return 1; })))
				.then(literal("webhook")
						.then(literal("clear").executes(c -> { setWebhook(""); return 1; }))
						.then(argument("url", StringArgumentType.greedyString()).executes(c -> { setWebhook(StringArgumentType.getString(c, "url")); return 1; }))))));
	}

	public static String status() {
		String sends = webhook.isEmpty() ? "no Discord webhook set" : alerts ? "alerts go to Discord" : "alerts off";
		return "status and events in " + dir().getFileName() + "/; " + sends;
	}

	private static void say(String text) {
		Xnmc.say("Remote", ChatFormatting.GRAY, text);
	}

	private static void setAlerts(boolean on) {
		alerts = on;
		save();
		say(status() + ".");
	}

	private static void setWebhook(String url) {
		url = url.strip();
		if (!url.isEmpty() && !Wire.webhookOk(url)) { say("that isn't a Discord webhook address (https://discord.com/api/webhooks/...)."); return; }
		webhook = url;
		save();
		say(url.isEmpty() ? "webhook cleared." : "webhook saved; /xnmc remote test sends one alert.");
	}

	private static void save() {
		Properties out = new Properties();
		out.setProperty("webhook", webhook);
		out.setProperty("alerts", Boolean.toString(alerts));
		try (var w = Files.newBufferedWriter(settings())) { out.store(w, "XNMC Remote"); }
		catch (IOException e) { LOG.warn("Could not save Remote's settings", e); say("couldn't save that; it holds for this session."); }
	}

	private static void test() {
		if (webhook.isEmpty()) { say("no webhook yet: /xnmc remote webhook <address>, from a channel only you can see."); return; }
		post(Wire.alert("Remote", "test alert.", place(Minecraft.getInstance())));
		say("sent a test alert.");
	}

	// Every line XNMC says, kept with where it was said.
	public static void event(String feature, String text) {
		Minecraft mc = Minecraft.getInstance();
		JsonObject line = new JsonObject();
		line.addProperty("time", now());
		line.addProperty("feature", feature);
		line.addProperty("text", text);
		position(line, mc);
		try {
			Path file = dir().resolve("events.jsonl");
			if (Files.exists(file) && Files.size(file) > ROTATE_BYTES)
				Files.move(file, dir().resolve("events.1.jsonl"), StandardCopyOption.REPLACE_EXISTING);
			Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			if (!warned) LOG.warn("Could not write {}", dir().resolve("events.jsonl"), e);
			warned = true;
		}
	}

	// A line that needs someone: to Discord, unless it repeats or they come too fast.
	public static void alert(String feature, String text) {
		if (!alerts || webhook.isEmpty() || !throttle.allow(feature + text, System.currentTimeMillis())) return;
		post(Wire.alert(feature, text, place(Minecraft.getInstance())));
	}

	private static void post(String content) {
		JsonObject body = new JsonObject();
		body.addProperty("content", content);
		// What a server or a sign says is never a ping.
		JsonObject mentions = new JsonObject();
		mentions.add("parse", new JsonArray());
		body.add("allowed_mentions", mentions);
		HttpRequest request = HttpRequest.newBuilder(URI.create(webhook)).timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
		// The address is a secret: failures are logged without it.
		http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((reply, failure) -> {
			if (failure != null) LOG.warn("Discord alert not delivered: {}", failure.getClass().getSimpleName());
			else if (reply.statusCode() >= 300) LOG.warn("Discord alert refused: HTTP {}", reply.statusCode());
		});
	}

	private static void tick(Minecraft mc) {
		ticks++;
		Screen screen = mc.gui.screen();
		if (screen instanceof DisconnectedScreen) {
			if (screen != told) {
				told = screen;
				String reason = ((DisconnectedAccessor) screen).xnmc$getDetails().reason().getString();
				event("Remote", "disconnected: " + reason);
				alert("Remote", "disconnected: " + reason);
				write(mc);
			}
		} else told = null;
		if (ticks % INBOX_TICKS == 0) inbox(mc);
		if (ticks % STATUS_TICKS == 0) write(mc);
	}

	private static String now() { return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(); }

	private static void position(JsonObject into, Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		BlockPos at = p.blockPosition();
		into.addProperty("dimension", mc.level.dimension().identifier().toString());
		into.addProperty("x", at.getX());
		into.addProperty("y", at.getY());
		into.addProperty("z", at.getZ());
	}

	// Where he is, in words, or where he last was.
	private static String place(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p != null && mc.level != null) {
			BlockPos at = p.blockPosition();
			String dimension = mc.level.dimension().identifier().getPath();
			where = dimension + " " + at.getX() + " " + at.getY() + " " + at.getZ();
		}
		return where;
	}

	private static void write(Minecraft mc) {
		JsonObject s = new JsonObject();
		s.addProperty("time", now());
		s.addProperty("xnmc", FabricLoader.getInstance().getModContainer("xnmc").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?"));
		LocalPlayer p = mc.player;
		boolean inWorld = p != null && mc.level != null;
		s.addProperty("connected", inWorld);
		ServerData server = mc.getCurrentServer();
		if (server != null) s.addProperty("server", server.ip);
		if (inWorld) {
			position(s, mc);
			place(mc);
			s.addProperty("health", Math.round(p.getHealth() * 10) / 10.0);
			s.addProperty("absorption", Math.round(p.getAbsorptionAmount() * 10) / 10.0);
			s.addProperty("food", p.getFoodData().getFoodLevel());
			int totems = p.getOffhandItem().is(Items.TOTEM_OF_UNDYING) ? p.getOffhandItem().getCount() : 0;
			for (ItemStack stack : p.getInventory().getNonEquipmentItems()) if (stack.is(Items.TOTEM_OF_UNDYING)) totems += stack.getCount();
			s.addProperty("totems", totems);
			JsonArray near = new JsonArray();
			for (var other : mc.level.players()) if (other != p) near.add(other.getName().getString() + " " + (int) other.distanceTo(p) + "m");
			s.add("players", near);
		} else {
			Screen screen = mc.gui.screen();
			if (screen != null) s.addProperty("screen", screen.getClass().getSimpleName());
			if (screen instanceof DisconnectedScreen) s.addProperty("disconnect", ((DisconnectedAccessor) screen).xnmc$getDetails().reason().getString());
			if (!where.isEmpty()) s.addProperty("last", where);
		}
		JsonObject features = new JsonObject();
		for (Feature f : Xnmc.FEATURES) {
			String status;
			try { status = f.status().get(); } catch (RuntimeException e) { status = "?"; }
			features.addProperty(f.name(), status);
		}
		s.add("features", features);
		try {
			Path aside = dir().resolve("status.json.new");
			Files.writeString(aside, s + "\n");
			Files.move(aside, dir().resolve("status.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			if (!warned) LOG.warn("Could not write {}", dir().resolve("status.json"), e);
			warned = true;
		}
	}

	// Orders are files named *.cmd, one line each, run in name order and removed as they are read.
	private static void inbox(Minecraft mc) {
		List<Path> waiting = new ArrayList<>();
		try (var files = Files.newDirectoryStream(dir().resolve("inbox"), "*.cmd")) {
			files.forEach(waiting::add);
		} catch (IOException e) { return; }
		waiting.sort(null);
		for (Path file : waiting) {
			String line;
			try {
				line = Files.readString(file).lines().findFirst().orElse("");
				Files.delete(file);
			} catch (IOException e) { continue; }
			run(mc, line);
		}
	}

	private static void run(Minecraft mc, String line) {
		var dispatcher = ClientCommands.getActiveDispatcher();
		Wire.Order order = Wire.parse(line, root -> dispatcher != null && dispatcher.getRoot().getChild(root) != null);
		switch (order.kind()) {
			case REFUSED -> event("Remote", "order refused: " + order.arg());
			case SCREENSHOT -> {
				String name = "xnmc-" + System.currentTimeMillis() + ".png";
				Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, done -> event("Remote", "screenshot " + name + ": " + done.getString()));
			}
			case DISCONNECT -> {
				if (mc.getConnection() == null) { event("Remote", "order refused: not connected"); return; }
				// Ordered out, he stays out: nothing reconnects him.
				AfkGuard.suspendForRemote();
				event("Remote", "disconnecting, as ordered.");
				mc.getConnection().getConnection().disconnect(Component.literal("XNMC Remote: disconnect ordered"));
			}
			case BARITONE -> {
				if (mc.player == null || !Baritone.present()) { event("Remote", "order refused: Baritone isn't there to take it"); return; }
				event("Remote", "to Baritone: " + order.arg());
				Baritone.send(order.arg());
			}
			case COMMAND -> {
				if (mc.player == null) { event("Remote", "order refused: not in a world"); return; }
				event("Remote", "running /" + order.arg());
				mc.player.connection.sendCommand(order.arg());
			}
		}
	}
}

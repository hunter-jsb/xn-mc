package xnmc;

import java.util.ArrayDeque;
import java.util.Queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

// Baritone's "#" chat commands, which it cancels at ClientPacketListener.sendChat so they never reach the server.
// They go out on the client tick: Baritone ignored "#" chat sent from inside a client command.
public final class Baritone {
	private static final Logger LOG = LoggerFactory.getLogger("xnmc/baritone");
	private static final Queue<String> outbox = new ArrayDeque<>();

	static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (!outbox.isEmpty() && mc.player != null) {
				String c = outbox.poll();
				LOG.info("#{}", c);
				mc.player.connection.sendChat("#" + c);
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> outbox.clear());
	}

	// baritone-meteor is the unobfuscated build Meteor Client needs; same commands.
	public static boolean present() {
		return FabricLoader.getInstance().isModLoaded("baritone") || FabricLoader.getInstance().isModLoaded("baritone-meteor");
	}

	public static void send(String command) {
		outbox.add(command);
	}
}

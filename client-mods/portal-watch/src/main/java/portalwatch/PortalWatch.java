package portalwatch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class PortalWatch implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("portalwatch");
	// Standing height on top of the nether's bedrock ceiling.
	private static final int ROOF = 128;
	// One portal's blocks, or a chunk reloading, must not ring twice.
	private static final int SAME_PORTAL = 16;
	// Stop short so the walk-up never steps into the portal.
	private static final int STANDOFF = 3;

	private static boolean armed;
	private static ClientLevel armedLevel;
	private static BlockPos pending;
	private static BlockPos approach;
	private static final List<BlockPos> known = new ArrayList<>();
	// Baritone ignored "#" chat sent from inside a client command; from the tick it works.
	private static final Queue<String> outbox = new ArrayDeque<>();
	// The trip a find interrupts, so resume can restart it.
	private static Trip trip;

	private record Trip(String label, List<String> commands) {}

	@Override
	public void onInitializeClient() {
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			if (armed && pending == null) {
				BlockPos p = find(chunk, roofOnly(level));
				if (p != null && !isKnown(p)) pending = p;
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(PortalWatch::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> reset());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
				ClientCommands.literal("portalwatch")
						.executes(c -> status())
						.then(ClientCommands.literal("on").executes(c -> arm(null)))
						.then(ClientCommands.literal("spawn").executes(c -> arm(goTo(0, 0))))
						.then(ClientCommands.literal("goto")
								.then(ClientCommands.argument("x", IntegerArgumentType.integer())
										.then(ClientCommands.argument("z", IntegerArgumentType.integer())
												.executes(c -> arm(goTo(IntegerArgumentType.getInteger(c, "x"),
														IntegerArgumentType.getInteger(c, "z")))))))
						.then(ClientCommands.literal("explore").executes(c -> arm(explore())))
						.then(ClientCommands.literal("resume").executes(c -> {
							if (trip == null) {
								say("nothing to resume. Start one with spawn, goto <x> <z> or explore.");
								return 0;
							}
							return arm(trip);
						}))
						.then(ClientCommands.literal("off").executes(c -> {
							armed = false;
							approach = null;
							say("off.");
							return 1;
						}))));
	}

	private static Trip goTo(int x, int z) {
		return new Trip("heading to " + x + " " + z, List.of("goto " + x + " " + z));
	}

	// Explore spirals out from a fixed origin, so a resume continues the same spiral.
	private static Trip explore() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return null;
		BlockPos at = mc.player.blockPosition();
		// Baritone's default pulls explore toward Y 64; hold the height you started at,
		// which on the roof keeps it from dropping through any hole players have made.
		return new Trip("exploring outward from " + at.getX() + " " + at.getZ(),
				List.of("set exploreMaintainY " + at.getY(), "explore " + at.getX() + " " + at.getZ()));
	}

	private static void tick(Minecraft mc) {
		while (!outbox.isEmpty() && mc.player != null) {
			String command = outbox.poll();
			LOG.info("sending #{}", command);
			mc.player.connection.sendChat("#" + command);
		}
		if (approach != null && mc.player != null) {
			double dx = mc.player.getX() - (approach.getX() + 0.5), dz = mc.player.getZ() - (approach.getZ() + 0.5);
			if (dx * dx + dz * dz <= 2.25) {
				approach = null;
				ring(mc);
				say("you're at the portal." + (trip != null ? " /portalwatch resume keeps " + trip.label() + "." : ""));
			}
		}
		if (!armed) return;
		if (mc.level != armedLevel) {
			armed = false;
			known.clear();
			say("dimension changed, so I turned off. Run it again here.");
			return;
		}
		if (pending == null || mc.player == null) return;
		BlockPos p = pending;
		pending = null;
		armed = false;
		known.add(p);
		approach = standoff(p, mc.player.blockPosition());
		baritone("goto " + approach.getX() + " " + approach.getZ());
		ring(mc);
		int dist = (int) Math.sqrt(mc.player.blockPosition().distSqr(p));
		say("portal at " + p.getX() + " " + p.getY() + " " + p.getZ() + ", " + dist
				+ " blocks away. Walking over; I'll stop " + STANDOFF + " blocks short.");
		mc.player.sendOverlayMessage(Component.literal("Portal found " + dist + " blocks away").withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	// The column STANDOFF blocks from the portal on the side you're coming from.
	private static BlockPos standoff(BlockPos portal, BlockPos from) {
		double dx = from.getX() - portal.getX(), dz = from.getZ() - portal.getZ();
		double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
		return new BlockPos(portal.getX() + (int) Math.round(dx / len * STANDOFF), portal.getY(),
				portal.getZ() + (int) Math.round(dz / len * STANDOFF));
	}

	private static void ring(Minecraft mc) {
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BELL_BLOCK, 1.0f, 1.0f));
		mc.getSoundManager().playDelayed(SimpleSoundInstance.forUI(SoundEvents.BELL_BLOCK, 1.0f, 1.0f), 15);
	}

	// next == null only watches; otherwise it also starts (and remembers) that trip.
	private static int arm(Trip next) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return 0;
		if (armedLevel != mc.level) {
			known.clear();
			trip = null;
		}
		armedLevel = mc.level;
		pending = null;
		approach = null;
		int before = known.size();
		ChunkPos c = mc.player.chunkPosition();
		int r = mc.options.getEffectiveRenderDistance() + 2;
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++) {
				LevelChunk chunk = mc.level.getChunkSource().getChunk(c.x() + dx, c.z() + dz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				BlockPos p = find(chunk, false);
				if (p != null && !isKnown(p)) known.add(p);
			}
		armed = true;
		if (next != null) trip = next;
		int ignored = known.size() - before;
		say("on" + (roofOnly(mc.level) ? " (roof portals only)" : "") + "."
				+ (ignored > 0 ? " Ignoring " + ignored + " portal(s) already in view." : "")
				+ (next != null ? " Now " + next.label() + "." : ""));
		if (next != null) next.commands().forEach(PortalWatch::baritone);
		return 1;
	}

	private static int status() {
		Minecraft mc = Minecraft.getInstance();
		say((armed ? "on" : "off") + (armed && roofOnly(mc.level) ? " (roof portals only)" : "")
				+ ", " + known.size() + " portal(s) remembered"
				+ (trip != null ? ", last trip: " + trip.label() : "") + ".");
		return 1;
	}

	private static boolean roofOnly(ClientLevel level) {
		Minecraft mc = Minecraft.getInstance();
		return level != null && mc.player != null && level.dimension() == Level.NETHER && mc.player.getY() >= ROOF;
	}

	private static BlockPos find(LevelChunk chunk, boolean roofOnly) {
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s.hasOnlyAir() || !s.maybeHas(st -> st.is(Blocks.NETHER_PORTAL))) continue;
			int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
			if (roofOnly && baseY + 15 < ROOF) continue;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++)
						if (s.getBlockState(x, y, z).is(Blocks.NETHER_PORTAL) && (!roofOnly || baseY + y >= ROOF))
							return new BlockPos(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z);
		}
		return null;
	}

	private static boolean isKnown(BlockPos p) {
		for (BlockPos k : known) {
			int dx = k.getX() - p.getX(), dz = k.getZ() - p.getZ();
			if (dx * dx + dz * dz <= SAME_PORTAL * SAME_PORTAL) return true;
		}
		return false;
	}

	// Baritone cancels "#..." chat at ClientPacketListener.sendChat, so this never reaches the server.
	private static void baritone(String command) {
		if (!FabricLoader.getInstance().isModLoaded("baritone")) {
			say("Baritone isn't installed, so I can't run #" + command + ".");
			return;
		}
		outbox.add(command);
	}

	private static void reset() {
		armed = false;
		armedLevel = null;
		pending = null;
		approach = null;
		trip = null;
		outbox.clear();
		known.clear();
	}

	private static void say(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null)
			mc.player.sendSystemMessage(Component.literal("[Portal Watch] " + text).withStyle(ChatFormatting.LIGHT_PURPLE));
	}
}

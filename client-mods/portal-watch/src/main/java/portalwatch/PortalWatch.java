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
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
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
	// Destinations wait for the tick: Baritone ignored "#" chat sent from inside a client command.
	private static final Queue<BlockPos> outbox = new ArrayDeque<>();
	// The last trip started; it outlives finds, dimension changes and reconnects so resume can pick it up.
	private static Trip trip;
	// Whether you're on that trip right now, as opposed to watching or walking to a portal.
	private static boolean following;

	// A plain goto, or a sweep: a square spiral whose lanes sit one view-width apart, so each strip
	// of land loads once. Baritone's #explore hugs the edge of loaded chunks from a view-distance
	// inside it, so each lap only pushed out about a chunk and re-walked the rest.
	private static final class Trip {
		final ResourceKey<Level> dimension;
		final int x, z; // goto target, or sweep origin
		final int lane; // blocks between sweep lanes; 0 for a goto
		int leg;
		BlockPos rejoin; // where a find pulled you off the sweep

		Trip(ResourceKey<Level> dimension, int x, int z, int lane) {
			this.dimension = dimension;
			this.x = x;
			this.z = z;
			this.lane = lane;
		}

		String label() {
			return lane == 0 ? "heading to " + x + " " + z
					: "sweeping out from " + x + " " + z + " (lap " + (leg / 4 + 1) + ", lanes " + lane + " blocks apart)";
		}

		// Legs run east, south, west, north, growing one lane every second leg.
		BlockPos target() {
			if (lane == 0) return new BlockPos(x, 0, z);
			if (rejoin != null) return rejoin;
			int cx = x, cz = z;
			for (int i = 0; i <= leg; i++) {
				int len = (i / 2 + 1) * lane;
				switch (i % 4) {
					case 0 -> cx += len;
					case 1 -> cz += len;
					case 2 -> cx -= len;
					default -> cz -= len;
				}
			}
			return new BlockPos(cx, 0, cz);
		}

	}

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
						.then(ClientCommands.literal("explore").executes(c -> arm(sweep())))
						.then(ClientCommands.literal("resume").executes(c -> {
							Minecraft mc = Minecraft.getInstance();
							if (trip == null) {
								say("nothing to resume. Start one with spawn, goto <x> <z> or explore.");
								return 0;
							}
							if (mc.level == null || mc.level.dimension() != trip.dimension) {
								say("your last trip was in another dimension.");
								return 0;
							}
							return arm(trip);
						}))
						.then(ClientCommands.literal("off").executes(c -> {
							armed = false;
							following = false;
							approach = null;
							say("off.");
							return 1;
						}))));
	}

	private static Trip goTo(int x, int z) {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? null : new Trip(mc.level.dimension(), x, z, 0);
	}

	private static Trip sweep() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return null;
		BlockPos at = mc.player.blockPosition();
		// You load the chunks within view distance on either side of your chunk, so lanes this
		// far apart overlap by at least a chunk.
		int lane = 2 * mc.options.getEffectiveRenderDistance() * 16;
		return new Trip(mc.level.dimension(), at.getX(), at.getZ(), lane);
	}

	private static void tick(Minecraft mc) {
		while (!outbox.isEmpty() && mc.player != null) send(mc, outbox.poll());
		if (approach != null && mc.player != null && near(mc, approach, 2.5)) {
			approach = null;
			ring(mc);
			say("you're at the portal." + (trip != null ? " /portalwatch resume keeps " + trip.label() + "." : ""));
		}
		if (!armed) return;
		if (mc.level != armedLevel) {
			armed = false;
			following = false;
			known.clear();
			say("dimension changed, so I turned off. Run it again here.");
			return;
		}
		if (mc.player == null) return;
		if (following && trip != null && trip.lane > 0 && near(mc, trip.target(), 3)) {
			if (trip.rejoin != null) trip.rejoin = null;
			else trip.leg++;
			moveTo(trip.target());
		}
		if (pending == null) return;
		BlockPos p = pending;
		pending = null;
		armed = false;
		// Come back to this spot, not the corner, so the strip between here and the corner still loads.
		if (following && trip != null && trip.lane > 0 && trip.rejoin == null) trip.rejoin = mc.player.blockPosition();
		following = false;
		known.add(p);
		approach = standoff(p, mc.player.blockPosition());
		moveTo(approach);
		ring(mc);
		int dist = (int) Math.sqrt(mc.player.blockPosition().distSqr(p));
		say("portal at " + p.getX() + " " + p.getY() + " " + p.getZ() + ", " + dist
				+ " blocks away. Heading over; I'll stop " + STANDOFF + " blocks short.");
		mc.player.sendOverlayMessage(Component.literal("Portal found " + dist + " blocks away").withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	private static boolean near(Minecraft mc, BlockPos to, double within) {
		double dx = mc.player.getX() - (to.getX() + 0.5), dz = mc.player.getZ() - (to.getZ() + 0.5);
		return dx * dx + dz * dz <= within * within;
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

	// next == null only watches; otherwise it also starts (or resumes) that trip.
	private static int arm(Trip next) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return 0;
		if (armedLevel != mc.level) known.clear();
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
		following = next != null;
		if (next != null) trip = next;
		int ignored = known.size() - before;
		say("on" + (roofOnly(mc.level) ? " (roof portals only)" : "") + "."
				+ (ignored > 0 ? " Ignoring " + ignored + " portal(s) already in view." : "")
				+ (next != null ? " Now " + next.label() + "." : ""));
		if (next != null) moveTo(next.target());
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

	private static void moveTo(BlockPos to) {
		outbox.add(to);
	}

	// Riding a happy ghast, Ghast Pilot flies you; on foot, Baritone walks. The choice is made per leg,
	// so getting on or off mid-trip just switches who carries the next one.
	private static void send(Minecraft mc, BlockPos to) {
		String xz = to.getX() + " " + to.getZ();
		if (mc.player.getVehicle() instanceof HappyGhast && FabricLoader.getInstance().isModLoaded("ghastpilot")) {
			LOG.info("flying to {}", xz);
			try {
				// Runs Ghast Pilot's client command locally; nothing is sent to the server.
				ClientCommands.getActiveDispatcher().execute("ghastpilot steer " + xz,
						(FabricClientCommandSource) mc.player.connection.getSuggestionsProvider());
			} catch (Exception e) {
				LOG.warn("ghastpilot steer failed", e);
				say("couldn't hand the flight to Ghast Pilot: " + e.getMessage());
			}
		} else if (FabricLoader.getInstance().isModLoaded("baritone")) {
			LOG.info("sending #goto {}", xz);
			// Baritone cancels "#..." chat at ClientPacketListener.sendChat, so this never reaches the server.
			mc.player.connection.sendChat("#goto " + xz);
		} else say("neither Baritone nor Ghast Pilot (on a happy ghast) can take me to " + xz + ".");
	}

	// The trip survives so resume works after reconnecting.
	private static void reset() {
		armed = false;
		following = false;
		armedLevel = null;
		pending = null;
		approach = null;
		outbox.clear();
		known.clear();
	}

	private static void say(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null)
			mc.player.sendSystemMessage(Component.literal("[Portal Watch] " + text).withStyle(ChatFormatting.LIGHT_PURPLE));
	}
}

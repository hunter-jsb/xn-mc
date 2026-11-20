package portalwatch;

import java.util.ArrayList;
import java.util.List;

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
	// Standing height on top of the nether's bedrock ceiling.
	private static final int ROOF = 128;
	// One portal's blocks, or a chunk reloading, must not ring twice.
	private static final int SAME_PORTAL = 16;

	private static boolean armed;
	private static ClientLevel armedLevel;
	private static BlockPos pending;
	private static final List<BlockPos> known = new ArrayList<>();

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
						.then(ClientCommands.literal("on").executes(c -> arm(false)))
						.then(ClientCommands.literal("spawn").executes(c -> arm(true)))
						.then(ClientCommands.literal("off").executes(c -> {
							armed = false;
							say("off.");
							return 1;
						}))));
	}

	private static void tick(Minecraft mc) {
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
		baritone("stop");
		mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BELL_BLOCK, 1.0f, 1.0f));
		mc.getSoundManager().playDelayed(SimpleSoundInstance.forUI(SoundEvents.BELL_BLOCK, 1.0f, 1.0f), 15);
		int dist = (int) Math.sqrt(mc.player.blockPosition().distSqr(p));
		say("portal at " + p.getX() + " " + p.getY() + " " + p.getZ() + ", " + dist
				+ " blocks away. Baritone stopped. #goto nether_portal walks you to it; /portalwatch spawn keeps going.");
		mc.player.sendOverlayMessage(Component.literal("Portal found " + dist + " blocks away").withStyle(ChatFormatting.LIGHT_PURPLE));
	}

	private static int arm(boolean toSpawn) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return 0;
		if (armedLevel != mc.level) known.clear();
		armedLevel = mc.level;
		pending = null;
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
		int ignored = known.size() - before;
		say("on" + (roofOnly(mc.level) ? " (roof portals only)" : "") + "."
				+ (ignored > 0 ? " Ignoring " + ignored + " portal(s) already in view." : ""));
		if (toSpawn) baritone("goto 0 0");
		return 1;
	}

	private static int status() {
		Minecraft mc = Minecraft.getInstance();
		say((armed ? "on" : "off") + (armed && roofOnly(mc.level) ? " (roof portals only)" : "")
				+ ", " + known.size() + " portal(s) remembered.");
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
		Minecraft mc = Minecraft.getInstance();
		if (!FabricLoader.getInstance().isModLoaded("baritone")) {
			say("Baritone isn't installed, so I can't run #" + command + ".");
			return;
		}
		if (mc.player != null) mc.player.connection.sendChat("#" + command);
	}

	private static void reset() {
		armed = false;
		armedLevel = null;
		pending = null;
		known.clear();
	}

	private static void say(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null)
			mc.player.sendSystemMessage(Component.literal("[Portal Watch] " + text).withStyle(ChatFormatting.LIGHT_PURPLE));
	}
}

package xnmc.portalwatch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.HashSet;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import xnmc.Baritone;
import xnmc.Feature;
import xnmc.FeatureScreen;
import xnmc.Xnmc;
import xnmc.ghastpilot.GhastPilot;

public final class PortalWatch {
	private static final Logger LOG = LoggerFactory.getLogger("xnmc/portalwatch");
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
	// Destinations wait for the tick, so a leg started from a command or button goes out like the rest.
	private static final Queue<BlockPos> outbox = new ArrayDeque<>();
	// The current route; scanned coverage is saved independently of route state.
	private static Trip trip;
	// Whether you're on that trip right now, as opposed to watching or walking to a portal.
	private static boolean following;
    private static PortalMode mode = PortalMode.BOTH;
    private static final ExploreSettings exploration = new ExploreSettings();
    private static Path exploreFile() { return FabricLoader.getInstance().getConfigDir().resolve("xnmc-portalwatch-explore.properties"); }
    private static void saveExploreSettings() {
        try { exploration.save(exploreFile()); }
        catch (IOException e) { LOG.warn("Could not save exploration settings",e); say("settings changed for this session; couldn't save them."); }
    }
    private static String patternLabel() {
        return exploration.pattern == ExploreSettings.Pattern.SPIRAL ? "Spiral sweep" : trailStrategy(exploration.bias);
    }
    static String trailStrategy(ExploreSettings.Bias bias) {
        return switch(bias) {
            case OUTWARD -> "Trail away from spawn";
            case INWARD -> "Trail toward spawn";
            case NEUTRAL -> "Trail without spawn bias";
        };
    }
    static String announcement(boolean on, String scope, String strategy) {
        return (on ? "On" : "Off") + " · " + scope + " · " + strategy;
    }
    private static void cyclePattern() {
        if (exploration.pattern == ExploreSettings.Pattern.SPIRAL) { exploration.pattern=ExploreSettings.Pattern.TRAIL; exploration.bias=ExploreSettings.Bias.OUTWARD; }
        else if (exploration.bias == ExploreSettings.Bias.NEUTRAL) exploration.pattern=ExploreSettings.Pattern.SPIRAL;
        else exploration.bias=exploration.bias.next();
        saveExploreSettings();
    }
    private static Feature searchSettings() {
        return new Feature("Portal Watch settings", () -> "Pattern and inset apply to a new search. Portal mode applies immediately.", s -> {
            s.choice(() -> "Portal search: " + mode.label, () -> setMode(mode.next()));
            s.choice(() -> "Pattern: " + patternLabel(), PortalWatch::cyclePattern);
            s.choice(() -> "Trail inset: " + exploration.insetLabel(), () -> { exploration.cycleInset(); saveExploreSettings(); });
        });
    }

    private static Path modeFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("xnmc-portalwatch-mode.txt");
    }

    private static boolean matchesMode(ClientLevel level, BlockPos pos) {
        return mode.accepts(level.dimension() == Level.NETHER, pos.getY());
    }

    public static int setMode(PortalMode next) {
        if (mode == next) { say(status()); return 1; }
        if (RoofPortalBuilder.active()) RoofPortalBuilder.stop("roof build cancelled because search mode changed.");
        if (approach != null) { stopMovement(); approach = null; }
        pending = null;
        mode = next;
        try { Files.writeString(modeFile(), mode.name() + "\n"); }
        catch (IOException e) { LOG.warn("Could not save Portal Watch mode", e); say("mode changed for this session, but couldn't save it."); }
        say(status());
        Minecraft mc = Minecraft.getInstance();
        if (armed && mc.level != null && mc.player != null) pending = nearestLoaded(mc, false);
        return 1;
    }

	// A plain goto, or a sweep: a square spiral whose lanes sit one view-width apart, so each strip
	// of land loads once. Baritone's #explore hugs the edge of loaded chunks from a view-distance
	// inside it, so each lap only pushed out about a chunk and re-walked the rest.
	private static final class Trip {
		final ResourceKey<Level> dimension;
		final int x, z; // goto target, or sweep origin
		final int lane; // blocks between sweep lanes; 0 for a goto
        final FrontierExplorer frontier;
		int leg;

		Trip(ResourceKey<Level> dimension, int x, int z, int lane) {
			this.dimension = dimension;
			this.x = x;
			this.z = z;
			this.lane = lane;
            this.frontier = null;
		}

        Trip(ResourceKey<Level> dimension, FrontierExplorer frontier) {
            this.dimension = dimension;
            this.frontier = frontier;
            x = z = lane = 0;
        }

		String label() {
            if (frontier != null) return frontier.label();
			return lane == 0 ? "Travel to " + x + " " + z : "Spiral sweep";
		}

		// Legs run east, south, west, north, growing one lane every second leg.
		BlockPos target() {
			if (lane == 0) return new BlockPos(x, 0, z);
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

	public static void init() {
        try { exploration.load(exploreFile()); }
        catch (IOException | IllegalArgumentException e) { LOG.warn("Could not read exploration settings; using defaults",e); }
        try {
            if (Files.exists(modeFile())) mode = PortalMode.valueOf(Files.readString(modeFile()).strip());
        } catch (IOException | IllegalArgumentException e) { LOG.warn("Could not read Portal Watch mode; using Both", e); }
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			if (armed && pending == null) {
                if (following && trip != null && trip.frontier != null) trip.frontier.recordScanned(chunk.getPos().x(),chunk.getPos().z());
				for (BlockPos p : findAll(chunk).stream().filter(p -> matchesMode(level, p)).sorted(java.util.Comparator.comparingInt(p -> onRoof(level) && p.getY() >= ROOF ? 0 : 1)).toList())
                    if (!isKnown(p)) { pending = p; break; }
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(PortalWatch::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> reset());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,ctx) -> dispatcher.register(commands()));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,ctx) -> dispatcher.register(
                ClientCommands.literal("build").executes(c -> buildHere())));
	}

    static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> commands() {
        return ClientCommands.literal("portalwatch")
						.executes(c -> { say(status()); return 1; })

						.then(ClientCommands.literal("on").executes(c -> on()))
						.then(ClientCommands.literal("build").executes(c -> buildHere()))
                        .then(ClientCommands.literal("settings").executes(c -> { FeatureScreen.open(searchSettings()); return 1; })
                                .then(ClientCommands.literal("mode")
                                .executes(c -> { say(mode.label); return 1; })
                                .then(ClientCommands.literal("roof").executes(c -> setMode(PortalMode.ROOF)))
                                .then(ClientCommands.literal("below").executes(c -> setMode(PortalMode.BELOW)))
                                .then(ClientCommands.literal("both").executes(c -> setMode(PortalMode.BOTH)))))
						.then(ClientCommands.literal("spawn").executes(c -> go(0, 0)))
						.then(ClientCommands.literal("goto")
								.then(ClientCommands.argument("x", IntegerArgumentType.integer())
										.then(ClientCommands.argument("z", IntegerArgumentType.integer())
												.executes(c -> go(IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"))))))
						.then(ClientCommands.literal("explore").executes(c -> explore()))
                        .then(ClientCommands.literal("sweep").executes(c -> sweepExplore()))
						.then(ClientCommands.literal("off").executes(c -> off()));
    }

	public static Feature feature() {
		return new Feature("Portal Watch", PortalWatch::status, s -> {
            s.row(FeatureScreen.act("Start search", PortalWatch::explore),FeatureScreen.act("Stop",PortalWatch::off));
            s.row(FeatureScreen.act("Watch only",PortalWatch::on),FeatureScreen.act("Build portal here",PortalWatch::buildHere));
            s.page("Settings: " + patternLabel(), searchSettings());
            s.page("Travel to coordinates", new Feature("Portal Watch travel", () -> "Watch for portals while travelling to a destination.", travel -> {
                var to=travel.field("x z");
                travel.row(FeatureScreen.act("Go to",() -> { int[] c=travel.coords(to); if(c!=null)go(c[0],c[c.length-1]); }),FeatureScreen.act("Spawn",()->go(0,0)));
            }));
		});
	}

	public static boolean armed() { return armed; }

	public static int on() { return arm(null); }
	public static int go(int x, int z) { return arm(goTo(x, z)); }
    public static int explore() {
        if (exploration.pattern == ExploreSettings.Pattern.SPIRAL) return arm(sweep());
        Minecraft mc = Minecraft.getInstance();
        if (!onRoof(mc.level)) { say("edge exploration starts on the Nether roof; Sweep remains available elsewhere."); return 0; }
        if (RoofPortalBuilder.active()) { say("cancel the roof build first with /portalwatch off."); return 0; }
        try {
            if (trip != null && trip.frontier != null) trip.frontier.saveHistory();
            FrontierExplorer frontier = new FrontierExplorer(exploration);
            stopMovement();
            return arm(new Trip(mc.level.dimension(), frontier));
        } catch (ReflectiveOperationException | IOException | RuntimeException e) {
            say("can't follow chunk boundaries: " + e.getMessage() + ". Enable XaeroPlus Palette NewChunks, or use /portalwatch sweep.");
            return 0;
        }
    }
    public static int sweepExplore() { exploration.pattern=ExploreSettings.Pattern.SPIRAL; saveExploreSettings(); return explore(); }

	public static int off() {
        if (trip != null && trip.frontier != null) trip.frontier.pause();
        if (RoofPortalBuilder.active()) RoofPortalBuilder.stop("roof build cancelled; placed blocks left intact.");
        if (following || approach != null) stopMovement();
        outbox.clear(); pending = null;
		armed = false;
		following = false;
		approach = null;
		say(status());
		return 1;
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
        RoofPortalBuilder.tick(mc);
        if (RoofPortalBuilder.active()) return;
        if (armedLevel != null && mc.level != armedLevel) { reset(); return; }
		while (!outbox.isEmpty() && mc.player != null) send(mc, outbox.poll());
		if (approach != null && mc.player != null && near(mc, approach, 2.5)) {
			approach = null;
			ring(mc);
			alert("you're at the portal.");
		}
		if (!armed) return;
		if (mc.player == null) return;
        if (pending == null && following && trip != null && trip.frontier != null) {
            if (!onRoof(mc.level)) { off(); alert("left the roof; edge exploration stopped."); return; }
            BlockPos next = trip.frontier.tick(mc);
            if (trip.frontier.stopped()) { armed = following = false; stopMovement(); return; }
            if (next != null) moveTo(next);
        }
		if (pending == null && following && trip != null && trip.lane > 0 && near(mc, trip.target(), 3)) {
			trip.leg++;
			moveTo(trip.target());
		}
		if (pending == null) return;
		BlockPos p = pending;
		pending = null;
		armed = false;
		following = false;
        outbox.clear();
        if (trip != null && trip.frontier != null) trip.frontier.pause();
		known.add(p);
        if (onRoof(mc.level) && p.getY() < ROOF) {
            approach = null; outbox.clear();
            stopMovement();
            ring(mc);
            alert("portal below the roof at " + p.getX() + " " + p.getY() + " " + p.getZ() + ". Staying on the roof and building above its chunk.");
            RoofPortalBuilder.start(p);
            return;
        }
		approach = standoff(p, mc.player.blockPosition());
		moveTo(approach);
		ring(mc);
		int dist = (int) Math.sqrt(mc.player.blockPosition().distSqr(p));
		alert("portal at " + p.getX() + " " + p.getY() + " " + p.getZ() + ", " + dist
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

	// next == null only watches; otherwise it starts that route.
	private static int arm(Trip next) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return 0;
        if (RoofPortalBuilder.active()) { say("cancel the roof build with /portalwatch off first."); return 0; }
        if (following || approach != null) stopMovement();
        if (trip != null && trip.frontier != null && trip != next) trip.frontier.pause();
		if (armedLevel != mc.level) known.clear();
		armedLevel = mc.level;
		pending = null;
		approach = null;
		ChunkPos c = mc.player.chunkPosition();
		int r = mc.options.getEffectiveRenderDistance() + 2;
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++) {
				LevelChunk chunk = mc.level.getChunkSource().getChunk(c.x() + dx, c.z() + dz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
                if (next != null && next.frontier != null) next.frontier.recordScanned(c.x()+dx,c.z()+dz);
                for (BlockPos p : findAll(chunk)) if (matchesMode(mc.level, p) && !isKnown(p)) known.add(p);
			}
		armed = true;
		following = next != null;
		trip = next;
        say(status());
        if (next != null && next.frontier == null) moveTo(next.target());
		return 1;
	}

	public static String status() {
		Minecraft mc = Minecraft.getInstance();
        if (RoofPortalBuilder.active()) return RoofPortalBuilder.status() + "; search: " + mode.label;
        String scope = mc.level == null || mc.level.dimension() == Level.NETHER ? mode.label : "All portals";
        String strategy = following && trip != null ? trip.label() : armed ? "Watch only" : patternLabel();
        return announcement(armed,scope,strategy);
	}

	private static boolean onRoof(ClientLevel level) {
		Minecraft mc = Minecraft.getInstance();
		return level != null && mc.player != null && level.dimension() == Level.NETHER && mc.player.getY() >= ROOF;
	}

	static List<BlockPos> findAll(LevelChunk chunk) {
        Set<BlockPos> blocks = new HashSet<>();
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s.hasOnlyAir() || !s.maybeHas(st -> st.is(Blocks.NETHER_PORTAL))) continue;
			int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++)
						if (s.getBlockState(x, y, z).is(Blocks.NETHER_PORTAL))
                            blocks.add(new BlockPos(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z));
		}
        return PortalGroups.group(blocks);
	}

    private static BlockPos nearestLoaded(Minecraft mc, boolean includeKnown) {
        List<BlockPos> found = new ArrayList<>();
        ChunkPos c = mc.player.chunkPosition();
        int r = mc.options.getEffectiveRenderDistance() + 2;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            LevelChunk chunk = mc.level.getChunkSource().getChunk(c.x() + dx, c.z() + dz, ChunkStatus.FULL, false);
            if (chunk != null) for (BlockPos p : findAll(chunk))
                if (matchesMode(mc.level, p) && (includeKnown || !isKnown(p))) found.add(p);
        }
        BlockPos here = mc.player.blockPosition();
        return found.stream().min(java.util.Comparator.comparingLong(p -> {
            long dx = (long) p.getX() - here.getX(), dz = (long) p.getZ() - here.getZ();
            return (dx * dx + dz * dz) * 2 + (onRoof(mc.level) && p.getY() >= ROOF ? 0 : 1);
        })).orElse(null);
    }

    public static int buildHere() {
        if (RoofPortalBuilder.active()) { say("already building a portal."); return 0; }
        stopMovement();
        if (trip != null && trip.frontier != null) trip.frontier.pause();
        armed = following = false; pending = approach = null; outbox.clear();
        RoofPortalBuilder.startHere();
        return RoofPortalBuilder.active() ? 1 : 0;
    }

    private static void stopMovement() {
        outbox.clear();
        if (GhastPilot.engaged()) GhastPilot.off();
        if (Baritone.present()) try { PortalBuilderBridge.cancelCurrent(); }
        catch (ReflectiveOperationException e) { say("couldn't cancel Baritone: " + e.getMessage()); }
    }

	private static boolean isKnown(BlockPos p) {
		for (BlockPos k : known) {
			int dx = k.getX() - p.getX(), dz = k.getZ() - p.getZ();
			if (dx * dx + dz * dz <= SAME_PORTAL * SAME_PORTAL && Math.abs(k.getY() - p.getY()) <= 4) return true;
		}
		return false;
	}

	private static void moveTo(BlockPos to) {
		outbox.add(to);
	}

	// Riding a happy ghast, Ghast Pilot flies you; on foot, Baritone walks. The choice is made per leg,
	// so getting on or off mid-trip just switches who carries the next one.
	private static void send(Minecraft mc, BlockPos to) {
        if (following && trip != null && trip.lane>0 && mc.level!=null) {
            var b=mc.level.getWorldBorder();
            var bounds=new FrontierPlanner.Bounds(b.getMinX(),b.getMinZ(),b.getMaxX(),b.getMaxZ());
            if (!bounds.contains(to.getX()+.5,to.getZ()+.5)) {
                armed=following=false; stopMovement();
                alert("spiral stopped at the world border; choose a trail pattern to follow it sideways or inward.");
                return;
            }
        }
		String xz = to.getX() + " " + to.getZ();
		if (GhastPilot.ghast() != null) {
			LOG.info("flying to {}", xz);
			GhastPilot.steer(to.getX(), to.getZ());
		} else if (Baritone.present()) {
			Baritone.send("goto " + xz);
		} else say("Baritone isn't installed, so on foot I can't take you to " + xz + ".");
	}

	// Coverage survives independently; active travel ends on disconnect or dimension change.
	private static void reset() {
        if (trip != null && trip.frontier != null) trip.frontier.pause();
        if (RoofPortalBuilder.active()) RoofPortalBuilder.stop(null);
		armed = false;
		following = false;
		armedLevel = null;
		pending = null;
		approach = null;
		outbox.clear();
		known.clear();
	}

	// A find or a stop: what an unattended search is left running for.
	static void alert(String text) {
		Xnmc.alert("Portal Watch", ChatFormatting.LIGHT_PURPLE, text);
	}

	static void say(String text) {
		Xnmc.say("Portal Watch", ChatFormatting.LIGHT_PURPLE, text);
	}
}

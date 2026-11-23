package xnmc.portalwatch;

import java.util.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

final class FrontierExplorer {
    private final PaletteChunkData data;
    private final Set<FrontierPlanner.Chunk> visited = new HashSet<>();
    private final FrontierProgress progress = new FrontierProgress();
    private final ExploreHistory history;
    private final ExploreSettings.Bias bias;
    private final int configuredInset;
    private final String worldKey;
    private long lastSave;
    private BlockPos goal;
    private long nextCheck;
    private long waitUntil;
    private boolean stopped;
    FrontierExplorer(ExploreSettings settings) throws ReflectiveOperationException, IOException {
        data = new PaletteChunkData();
        if (!data.enabled()) throw new IllegalStateException("enable XaeroPlus Palette NewChunks first");
        bias = settings.bias;
        configuredInset = settings.inset;
        var mc = Minecraft.getInstance();
        worldKey=worldKey(mc);
        history = new ExploreHistory(FabricLoader.getInstance().getGameDir().resolve("xnmc/portalwatch-coverage/")
                .resolve(UUID.nameUUIDFromBytes(worldKey.getBytes(StandardCharsets.UTF_8))+".csv"));
        waitUntil = System.nanoTime() / 1_000_000 + 10_000;
    }
    private static String worldKey(Minecraft mc) {
        String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip.toLowerCase(Locale.ROOT)
                : "singleplayer:" + (mc.getSingleplayerServer() == null ? "unknown" : mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
        return server + ":" + mc.level.dimension().identifier();
    }
    boolean matchesWorld(Minecraft mc) { return mc.level!=null && worldKey.equals(worldKey(mc)); }
    String label() { return PortalWatch.trailStrategy(bias); }
    boolean stopped() { return stopped; }
    BlockPos tick(Minecraft mc) {
        if (stopped || mc.player == null || mc.level == null) return null;
        if (!matchesWorld(mc)) { stop("server or dimension changed"); return null; }
        long now = System.nanoTime() / 1_000_000;
        if (now-lastSave>5000) { saveHistory(); lastSave=now; }
        var border = mc.level.getWorldBorder();
        var bounds = new FrontierPlanner.Bounds(border.getMinX(), border.getMinZ(), border.getMaxX(), border.getMaxZ());
        if (goal != null) {
            if (!bounds.contains(goal.getX() + .5, goal.getZ() + .5)) {
                stop("world border now excludes the current target");
                return null;
            }
            double dx = mc.player.getX() - (goal.getX() + .5), dz = mc.player.getZ() - (goal.getZ() + .5);
            if (dx * dx + dz * dz > 9) {
                if (progress.stalled(Math.hypot(dx, dz), now))
                    stop("no progress toward target for 30 seconds; move to a clear spot and start a new search");
                return null;
            }
            visited.add(new FrontierPlanner.Chunk(Math.floorDiv(goal.getX(),16), Math.floorDiv(goal.getZ(),16)));
            goal = null;
        }
        if (now < nextCheck) return null;
        nextCheck = now + 2_000;
        try {
            if (!data.enabled()) throw new IllegalStateException("Palette NewChunks was disabled");
            int x = mc.player.chunkPosition().x(), z = mc.player.chunkPosition().z();
            int view = Math.max(2,mc.options.getEffectiveRenderDistance());
            int inset = configuredInset < 0 ? Math.max(1,Math.min(12,view-2)) : Math.min(configuredInset,view-1);
            int radius = Math.max(6, Math.min(32,view+inset+3));
            var map = data.snapshot(mc.level.dimension(), x, z, radius);
            if (now < waitUntil && map.values().stream().allMatch(k -> k == FrontierPlanner.Kind.UNKNOWN)) {
                return null;
            }
            var here = new FrontierPlanner.Chunk(x,z);
            visited.add(here);
            var step = FrontierPlanner.next(map, here, visited, bounds,bias,inset,view,history.scanned);
            if (step.target() == null) {
                stop(step.reason());
                return null;
            }
            goal = new BlockPos(step.target().x() * 16 + 8, 128, step.target().z() * 16 + 8);
            progress.reset(Math.hypot(mc.player.getX() - (goal.getX() + .5), mc.player.getZ() - (goal.getZ() + .5)), now);
            return goal;
        } catch (ReflectiveOperationException | RuntimeException e) {
            stop("chunk evidence unavailable: " + e.getMessage());
            return null;
        }
    }
    private void stop(String reason) {
        stopped = true;
        goal = null;
        saveHistory();
        PortalWatch.alert("edge explore stopped: " + reason + ".");
    }
    void recordScanned(int x, int z) { history.record(x,z); }
    void saveHistory() {
        try { history.save(); }
        catch (IOException e) { System.getLogger("xnmc/portalwatch").log(System.Logger.Level.WARNING,"Could not save exploration coverage",e); }
    }
    void clearHistory() throws IOException { history.clear(); visited.clear(); pause(); }
    void pause() { goal = null; nextCheck = 0; saveHistory(); }
}

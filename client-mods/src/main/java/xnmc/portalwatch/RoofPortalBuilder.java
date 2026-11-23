package xnmc.portalwatch;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import xnmc.Baritone;
import xnmc.ghastpilot.GhastPilot;

/** Detected builds stay on the roof; manual builds use a clear, solid-backed site at the current height. */
final class RoofPortalBuilder {
    private enum Stage { APPROACH, FRAME, LIGHT_APPROACH, LIGHT }
    private static Stage stage;
    private static boolean roofOnly;
    private static PortalBuilderBridge bridge;
    private static PortalPlan plan;
    private static ClientLevel world;
    private static LocalPlayer owner;
    private static final Map<BlockPos, BlockState> desired = new java.util.concurrent.ConcurrentHashMap<>();
    private static long deadline, lastProgress, lightDeadline;
    private static int completed, lightTicks, previousSlot = -1, swappedFrom = -1;
    private static float health, absorption;
    private static final Set<Item> SUPPORTS = Set.of(Items.DIRT, Items.COARSE_DIRT, Items.STONE, Items.COBBLESTONE,
            Items.DEEPSLATE, Items.COBBLED_DEEPSLATE, Items.NETHERRACK, Items.BLACKSTONE, Items.BASALT,
            Items.SMOOTH_BASALT, Items.GRANITE, Items.DIORITE, Items.ANDESITE, Items.TUFF);

    static boolean active() { return stage != null; }
    static String status() { return stage == null ? "" : "portal build: " + stage.name().toLowerCase(Locale.ROOT).replace('_', ' '); }
    private static long now() { return System.nanoTime() / 1_000_000; }
    private static BlockPos stand() { return new BlockPos(plan.standX(), plan.y(), plan.standZ()); }
    private static BlockPos at(int u, int y) {
        return new BlockPos(plan.x() + (plan.alongX() ? u : 0), plan.y() + y, plan.z() + (plan.alongX() ? 0 : u));
    }
    static void start(BlockPos below) { start(below,true); }
    static void startHere() {
        var p=Minecraft.getInstance().player;
        if (p!=null) start(p.blockPosition(),false);
    }
    private static void start(BlockPos below, boolean detected) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (active()) { PortalWatch.say("already building a portal."); return; }
        if (p == null || mc.level == null) return;
        if (mc.level.dimension()==Level.END) { PortalWatch.say("Nether portals cannot activate in the End."); return; }
        if (detected && (mc.level.dimension() != Level.NETHER || p.getY() < PortalPlan.Y)) {
            PortalWatch.say("build from the Nether roof; I won't travel below it."); return;
        }
        try {
            if (Baritone.present()) PortalBuilderBridge.cancelCurrent();
        } catch (ReflectiveOperationException e) { PortalWatch.say("couldn't stop Baritone: " + e.getMessage()); return; }
        if (GhastPilot.engaged()) GhastPilot.off();
        if (p.isPassenger()) { PortalWatch.say("dismount onto solid ground, then /build."); return; }
        if (!Baritone.present()) { PortalWatch.say("Baritone is needed to build the frame."); return; }
        if (!p.onGround() || !p.isAlive() || p.hurtTime > 0) { PortalWatch.say("stand on solid ground before building a portal."); return; }
        int baseY=detected ? PortalPlan.Y : p.blockPosition().getY();
        var chunk = mc.level.getChunkSource().getChunk(Math.floorDiv(below.getX(), 16), Math.floorDiv(below.getZ(), 16),
                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        if (chunk == null) { PortalWatch.say("the build chunk is not loaded; move nearer before building."); return; }
        for (BlockPos existing : PortalWatch.findAll(chunk)) if (detected && existing.getY() >= 128) {
            PortalWatch.say("that chunk already has a portal at " + existing.getX() + " " + existing.getY() + " " + existing.getZ() + "; no duplicate frame built.");
            return;
        }
        List<BlockState> corners = new ArrayList<>();
        List<Item> supports = new ArrayList<>();
        int obsidian = 0, supportCount = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = p.getInventory().getItem(slot);
            if (stack.is(Items.OBSIDIAN)) obsidian += stack.getCount();
            if (SUPPORTS.contains(stack.getItem())) {
                supportCount += stack.getCount();
                if (!supports.contains(stack.getItem())) supports.add(stack.getItem());
                for (int n = 0; n < stack.getCount() && corners.size() < 4; n++)
                    corners.add(((BlockItem) stack.getItem()).getBlock().defaultBlockState());
            }
        }
        if (obsidian < 10 || supportCount < 10 || ignitionSlot(p) < 0) {
            PortalWatch.say("stopped searching. Building needs 10 obsidian, 10 ordinary support blocks (mixed types are fine), and flint and steel or a fire charge. Restock, then /portalwatch build.");
            return;
        }
        roofOnly=detected;
        plan = PortalPlan.sites(below.getX(), below.getZ(),baseY).stream().filter(site -> clear(mc, site)).findFirst().orElse(null);
        if (plan == null) { PortalWatch.say("no clear, solid-backed build site at this height in your chunk; clear a flat area first."); return; }
        desired.clear();
        int corner = 0;
        for (PortalPlan.Cell c : plan.frame()) desired.put(new BlockPos(c.x(), c.y(), c.z()), c.obsidian() ? Blocks.OBSIDIAN.defaultBlockState() : corners.get(corner++));
        // Keep the opening explicitly empty so Baritone cannot use it as scaffold space.
        for (int y = 1; y <= 3; y++) for (int u = 1; u <= 2; u++) desired.put(at(u, y), Blocks.AIR.defaultBlockState());
        world = mc.level;
        owner = p;
        health = p.getHealth(); absorption = p.getAbsorptionAmount();
        completed = 0;
        deadline = now() + 10 * 60_000;
        lastProgress = now();
        try {
            bridge = new PortalBuilderBridge(supports);
            stage = Stage.APPROACH;
            bridge.go(stand());
            PortalWatch.say("building a portal in chunk " + Math.floorDiv(below.getX(), 16) + " " + Math.floorDiv(below.getZ(), 16)
                    + " at " + plan.x() + " " + plan.y() + " " + plan.z() + ". I'll light it and stop outside. /portalwatch off cancels.");
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.getLogger("xnmc/portalwatch").log(System.Logger.Level.ERROR, "Could not start portal builder", e);
            stop("can't start the Baritone builder: " + e.getMessage());
        }
    }
    private static boolean clear(Minecraft mc, PortalPlan site) {
        // Clear working space over bedrock (detected builds) or solid dry ground (manual builds).
        for (int u = 0; u < 4; u++) for (int v = -2; v <= 2; v++) {
            int x = site.x() + (site.alongX() ? u : v), z = site.z() + (site.alongX() ? v : u);
            BlockPos floor=new BlockPos(x,site.y()-1,z);
            if (roofOnly ? !mc.level.getBlockState(floor).is(Blocks.BEDROCK)
                    : !mc.level.getBlockState(floor).isFaceSturdy(mc.level,floor,Direction.UP)) return false;
            if (!mc.level.getFluidState(floor).isEmpty()) return false;
            if (site.y() < mc.level.getMinY() || site.y()+4 >= mc.level.getMaxY()) return false;
            for (int y = site.y(); y <= site.y()+4; y++) {
                BlockPos b = new BlockPos(x, y, z);
                if (!mc.level.getWorldBorder().isWithinBounds(b) || !mc.level.getBlockState(b).isAir() || !mc.level.getFluidState(b).isEmpty()) return false;
                if (v == 0 && mc.player.getBoundingBox().intersects(new AABB(b))) return false;
            }
        }
        return true;
    }
    static void tick(Minecraft mc) {
        if (!active()) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level != world) { stop("world changed; portal build stopped."); return; }
        if (p.isPassenger() || (roofOnly && p.getY() < 128) || p.getHealth() < health || p.getAbsorptionAmount() < absorption || p.hurtTime > 0) {
            stop("player state changed; portal build stopped."); return;
        }
        if (p.getFoodData().getFoodLevel() <= 12 || p.containerMenu != p.inventoryMenu) {
            stop("low food or an open container; portal build stopped."); return;
        }
        if (now() > deadline) { stop("portal build timed out; leaving placed blocks as they are."); return; }
        for (BlockPos q : BlockPos.betweenClosed(p.blockPosition().offset(-2, -1, -2), p.blockPosition().offset(2, 2, 2)))
            if (world.getFluidState(q).is(FluidTags.LAVA)) { stop("lava nearby; portal build stopped."); return; }
        if (lit()) { stop("portal lit. Stopped outside; linking still needs to be checked manually."); return; }
        try {
            if (stage == Stage.APPROACH && close(p, stand())) {
                bridge.cancel();
                refreshCorners(p);
                bridge.build(plan, desired::get);
                stage = Stage.FRAME;
                lastProgress = now();
            } else if (stage == Stage.FRAME) {
                refreshCorners(p);
                int count = 0;
                for (var entry : desired.entrySet()) if (world.getBlockState(entry.getKey()).equals(entry.getValue())) count++;
                if (count > completed) { completed = count; lastProgress = now(); }
                if (count == desired.size()) {
                    bridge.cancel();
                    bridge.go(stand());
                    stage = Stage.LIGHT_APPROACH;
                } else if (now() - lastProgress > 90_000) stop("frame made no progress for 90 seconds; check materials or obstructions.");
            } else if (stage == Stage.LIGHT_APPROACH && close(p, stand())) {
                bridge.cancel();
                stage = Stage.LIGHT;
                lightDeadline = now() + 10_000;
                lightTicks = 0;
            } else if (stage == Stage.LIGHT) light(mc, p);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.getLogger("xnmc/portalwatch").log(System.Logger.Level.ERROR, "Roof builder failed", e);
            stop("Baritone builder failed: " + e.getMessage());
        }
    }
    private static void refreshCorners(LocalPlayer p) {
        for (PortalPlan.Cell c : plan.frame()) {
            if (c.obsidian()) continue;
            BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
            BlockState actual = world.getBlockState(pos), want = desired.get(pos);
            if (SUPPORTS.contains(actual.getBlock().asItem())) { desired.put(pos, actual); continue; }
            boolean stillAvailable = false;
            BlockState alternative = null;
            for (int slot = 0; slot < 36; slot++) {
                ItemStack s = p.getInventory().getItem(slot);
                if (s.isEmpty() || !SUPPORTS.contains(s.getItem())) continue;
                if (s.getItem() == want.getBlock().asItem()) stillAvailable = true;
                if (alternative == null) alternative = ((BlockItem) s.getItem()).getBlock().defaultBlockState();
            }
            if (!stillAvailable && alternative != null) desired.put(pos, alternative);
        }
    }
    private static boolean close(LocalPlayer p, BlockPos q) {
        return Math.abs(p.getY() - q.getY()) < 0.5 && p.position().distanceToSqr(Vec3.atBottomCenterOf(q)) < 0.4;
    }
    private static boolean lit() {
        for (int y = 1; y <= 3; y++) for (int u = 1; u <= 2; u++)
            if (!world.getBlockState(at(u, y)).is(Blocks.NETHER_PORTAL)) return false;
        return true;
    }
    private static int ignitionSlot(LocalPlayer p) {
        for (Item preferred : List.of(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE)) for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && s.is(preferred) && (!s.isDamageableItem() || s.getDamageValue() < s.getMaxDamage())) return i;
        }
        return -1;
    }
    private static void light(Minecraft mc, LocalPlayer p) {
        if (now() > lightDeadline) { stop("frame is built, but ignition wasn't confirmed; light it manually."); return; }
        if (lightTicks++ % 20 != 0) return;
        if (!close(p, stand())) { stop("moved away before ignition; frame left unlit."); return; }
        if (previousSlot < 0) {
            previousSlot = p.getInventory().getSelectedSlot();
            int slot = ignitionSlot(p);
            if (slot < 0) { stop("frame built; no ignition item remains."); return; }
            if (slot >= 9) {
                if (p.containerMenu != p.inventoryMenu) { stop("close the container before lighting."); return; }
                swappedFrom = slot;
                mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, slot, 8, ContainerInput.SWAP, p);
                slot = 8;
            }
            p.getInventory().setSelectedSlot(slot);
        }
        if (!p.getMainHandItem().is(Items.FLINT_AND_STEEL) && !p.getMainHandItem().is(Items.FIRE_CHARGE)) return;
        BlockPos base = at(1, 0);
        Vec3 hit = new Vec3(base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5);
        BlockHitResult sight = world.clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(), hit.add(0, -0.01, 0),
                net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
        if (!sight.getBlockPos().equals(base) || sight.getDirection() != Direction.UP) return;
        Vec3 look = hit.subtract(p.getEyePosition());
        p.setYRot((float) Math.toDegrees(Math.atan2(look.z, look.x)) - 90);
        p.setXRot((float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z))));
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, base, false));
    }
    static void stop(String why) {
        if (bridge != null) { bridge.close(); bridge = null; }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player == owner) {
            if (swappedFrom >= 0 && mc.level == world && mc.getConnection() != null
                    && mc.getConnection().getConnection().isConnected() && mc.player.containerMenu == mc.player.inventoryMenu)
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, swappedFrom, 8, ContainerInput.SWAP, mc.player);
            if (previousSlot >= 0) mc.player.getInventory().setSelectedSlot(previousSlot);
        }
        previousSlot = swappedFrom = -1;
        stage = null; world = null; owner = null; desired.clear();
        if (why != null) PortalWatch.say(why);
    }
}

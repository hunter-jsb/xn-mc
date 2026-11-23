package xnmc.debris;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import xnmc.Baritone;
import xnmc.Feature;
import xnmc.FeatureScreen;
import xnmc.Xnmc;
import xnmc.afkguard.AfkGuard;

// Tunnels the nether at Y 15 along chunk borders and mines the ancient debris it can see from the tunnel, the way a
// player would: only blocks with an exposed face in line of sight, never a beeline to buried ore. Baritone walks
// and digs (it already refuses to break a block that touches lava); this plans the lanes and keeps you alive. It
// needs fire resistance and AFK Guard to start, drinks more from the hotbar, backs off along the tunnel when lava
// gets close, and stops rather than pushing on whenever something is off.
public final class DebrisMiner {
	private static final Logger LOG = LoggerFactory.getLogger("xnmc/debris");
	// Debris is most common around Y 15, and it's below most of the lava sea's caves.
	private static final int Y = 15;
	private static final int CHUNK = 16;
	// Short legs keep Baritone on the border; a long goto lets it wander off through caves.
	private static final int STEP = 8;
	private static final int DEFAULT_LENGTH = 256, LANES = 16;
	// Debris within this many blocks, in sight, counts as seen from the tunnel.
	private static final int SEE = 5;
	private static final int FIRE_RES_START = 120 * 20, FIRE_RES_LOW = 45 * 20;
	private static final int STUCK = 90 * 20, DEBRIS_TIMEOUT = 60 * 20, DRINK_TIMEOUT = 80;
	// Baritone's itemSaver keeps a tool with this many uses left, so below it the pickaxe is spent.
	private static final int TOOL_RESERVE = 10;
	private static final int RETREAT = 6;
	private static final int TRAIL = 64;
	// Dump junk when fewer main-inventory slots than this are empty.
	private static final int DUMP_AT = 3;
	// Ticks between thrown stacks, and the most the view turns per tick, so it reads as a person sorting.
	private static final int THROW_EVERY = 4;
	private static final float TURN = 15;
	// What tunnelling picks up that isn't worth keeping. One netherrack stack stays for Baritone to bridge with.
	private static final Predicate<ItemStack> JUNK = s -> s.is(Items.NETHERRACK) || s.is(Items.BLACKSTONE) || s.is(Items.BASALT)
			|| s.is(Items.SMOOTH_BASALT) || s.is(Items.GRAVEL) || s.is(Items.FLINT) || s.is(Items.SOUL_SAND) || s.is(Items.SOUL_SOIL)
			|| s.is(Items.MAGMA_BLOCK) || s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE);

	private enum State { OFF, LANE, DEBRIS, DRINK, DUMP, RETREAT }

	private enum Dump { SETTLE, TURN, THROW, CLOSE }

	private static State state = State.OFF, afterPause;
	// The run's lanes; kept after a stop or disconnect so resume carries on.
	private static Plan plan;
	private static BlockPos target;
	private static int sinceProgress, skips, drinkTicks, drinkSlot, prevSlot, fireBefore, startDebris;
	private static Dump dump;
	private static int dumpTicks, thrown;
	private static float dumpYaw;
	private static boolean lavaStop;
	// Waypoints reached, newest first: the tunnel behind you, known to be clear, to back off along.
	private static final Deque<BlockPos> trail = new ArrayDeque<>();
	private static final Set<BlockPos> skipped = new HashSet<>();
	private static String last = "not started.";

	// Lanes run along one chunk border, then step one chunk sideways along the crossing border and come back, so
	// every block of tunnel sits on a chunk border.
	private static final class Plan {
		final ResourceKey<Level> dimension;
		final Direction heading, side;
		final int x0, z0, length;
		int lane;
		boolean turning;
		int d;

		Plan(ResourceKey<Level> dimension, Direction heading, int x0, int z0, int length) {
			this.dimension = dimension;
			this.heading = heading;
			this.side = heading.getClockWise();
			this.x0 = x0;
			this.z0 = z0;
			this.length = length;
		}

		BlockPos waypoint() {
			int hx = heading.getStepX(), hz = heading.getStepZ(), sx = side.getStepX(), sz = side.getStepZ();
			boolean back = lane % 2 == 1;
			int dir = back ? -1 : 1;
			int ox = x0 + sx * CHUNK * lane + (back ? hx * length : 0);
			int oz = z0 + sz * CHUNK * lane + (back ? hz * length : 0);
			if (!turning) return new BlockPos(ox + hx * dir * d, Y, oz + hz * dir * d);
			return new BlockPos(ox + hx * dir * length + sx * d, Y, oz + hz * dir * length + sz * d);
		}

		void advance() {
			d += STEP;
			if (!turning && d > length) {
				turning = true;
				d = STEP;
			} else if (turning && d > CHUNK) {
				turning = false;
				lane++;
				d = STEP;
			}
		}

		String label() {
			return "lane " + (lane + 1) + " of " + LANES + ", " + (turning ? "stepping to the next lane" : d + "/" + length + " blocks");
		}
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(DebrisMiner::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			if (state == State.OFF) return;
			release(mc);
			state = State.OFF;
			last = "disconnected mid-run. Resume when you're back.";
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
				ClientCommands.literal("debris")
						.executes(c -> { say(status()); return 1; })
						.then(ClientCommands.literal("start").executes(c -> start(DEFAULT_LENGTH))
								.then(ClientCommands.argument("length", IntegerArgumentType.integer(32, 2048))
										.executes(c -> start(IntegerArgumentType.getInteger(c, "length")))))
						.then(ClientCommands.literal("resume").executes(c -> resume()))
						.then(ClientCommands.literal("stop").executes(c -> { stop("stopped."); return 1; }))));
	}

	public static Feature feature() {
		return new Feature("Debris Miner", DebrisMiner::status, s -> s.row(
				FeatureScreen.act("Start here", () -> start(DEFAULT_LENGTH)),
				FeatureScreen.act("Resume", DebrisMiner::resume),
				FeatureScreen.act("Stop", () -> stop("stopped."))));
	}

	public static int start(int length) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return 0;
		length = Math.max(STEP, length / CHUNK * CHUNK);
		if (!ready(mc, p)) return 0;
		BlockPos at = p.blockPosition();
		Direction heading = p.getDirection();
		plan = new Plan(mc.level.dimension(), heading, Math.round(at.getX() / (float) CHUNK) * CHUNK,
				Math.round(at.getZ() / (float) CHUNK) * CHUNK, length);
		trail.clear();
		skipped.clear();
		startDebris = count(p, Items.ANCIENT_DEBRIS);
		say("tunnelling " + heading.getName() + " along chunk borders at Y " + Y + ": " + LANES + " lanes of " + length
				+ " blocks, one chunk apart, stepping to the " + plan.side.getName() + ".");
		goLane();
		return 1;
	}

	public static int resume() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return 0;
		if (state != State.OFF) { say("already running."); return 0; }
		if (plan == null) { say("nothing to resume; start a run first."); return 0; }
		if (mc.level.dimension() != plan.dimension) { say("the last run was in another dimension."); return 0; }
		if (!ready(mc, p)) return 0;
		// Past the lava that stopped it; Baritone finds its own way round.
		if (lavaStop) {
			plan.advance();
			plan.advance();
			lavaStop = false;
		}
		say("resuming, " + plan.label() + ".");
		goLane();
		return 1;
	}

	// The checks a run needs before it moves, shared by start and resume.
	private static boolean ready(Minecraft mc, LocalPlayer p) {
		if (state != State.OFF) { say("already running; stop it first."); return false; }
		if (mc.level.dimension() != Level.NETHER) { say("only in the nether."); return false; }
		if (!Baritone.present()) { say("Baritone isn't installed."); return false; }
		if (Math.abs(p.getY() - Y) > 8) { say("get within 8 blocks of Y " + Y + " first."); return false; }
		if (pickaxe(p) < 0) { say("put a pickaxe with durability left in your hotbar."); return false; }
		if (fireTicks(p) < FIRE_RES_START && potionSlot(p) < 0) {
			say("needs fire resistance: drink one (2+ minutes left) or put a fire resistance potion in your hotbar.");
			return false;
		}
		if (!AfkGuard.armed()) AfkGuard.arm();
		if (!AfkGuard.armed()) { say("AFK Guard didn't arm, so I won't start."); return false; }
		boolean gold = false;
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
			gold |= p.getItemBySlot(slot).is(ItemTags.PIGLIN_SAFE_ARMOR);
		if (!gold) say("no gold armor on, so piglins will attack.");
		// Never mine with a pickaxe that's about to break.
		Baritone.send("set itemSaver true");
		return true;
	}

	private static void goLane() {
		state = State.LANE;
		target = plan.waypoint();
		sinceProgress = 0;
		Baritone.send("goto " + xyz(target));
	}

	private static void tick(Minecraft mc) {
		if (state == State.OFF) return;
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) return;
		if (mc.level.dimension() != plan.dimension) { stop("you left the nether."); return; }
		if (state == State.RETREAT) {
			if (near(p, target, 1.5)) halt("backed off from lava and stopped in the tunnel. Resume skips past it.");
			else if (++sinceProgress > STUCK) halt("couldn't get clear of the lava, so I stopped where you are.");
			return;
		}
		if (lavaDanger(mc.level, p)) { retreat(mc, p); return; }
		if (state == State.DRINK) { drink(mc, p); return; }
		if (state == State.DUMP) { dump(mc, p); return; }
		if (p.tickCount % 20 == 0) {
			String why = unsafe(p);
			if (why != null) { stop(why); return; }
		}
		if (fireTicks(p) < FIRE_RES_LOW) {
			int slot = potionSlot(p);
			if (slot < 0) { stop("fire resistance is running out and there's none left in your hotbar."); return; }
			startDrink(p, slot);
			return;
		}
		if (freeSlots(p) < DUMP_AT && junkSlot(p) >= 0) { startDump(p); return; }
		if (state == State.DEBRIS) {
			if (!mc.level.getBlockState(target).is(Blocks.ANCIENT_DEBRIS) && near(p, target, 1.6)) {
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 1.0f));
				say("got the debris at " + xyz(target) + " (" + (count(p, Items.ANCIENT_DEBRIS) - startDebris) + " this run).");
				goLane();
			} else if (++sinceProgress > DEBRIS_TIMEOUT) {
				skipped.add(target);
				say("couldn't reach the debris at " + xyz(target) + "; leaving it.");
				goLane();
			}
			return;
		}
		if (p.tickCount % 10 == 0) {
			BlockPos d = seenDebris(mc.level, p);
			if (d != null) {
				state = State.DEBRIS;
				target = d;
				sinceProgress = 0;
				LOG.info("debris in sight at {}", d);
				Baritone.send("goto " + xyz(d));
				return;
			}
		}
		if (near(p, target, 1.5)) {
			trail.push(target);
			while (trail.size() > TRAIL) trail.removeLast();
			skips = 0;
			plan.advance();
			if (plan.lane >= LANES) { stop("finished all " + LANES + " lanes."); return; }
			goLane();
		} else if (++sinceProgress > STUCK) {
			if (++skips > 3) { stop("Baritone kept getting stuck, so I stopped."); return; }
			say("stuck short of " + xyz(target) + "; skipping ahead.");
			plan.advance();
			goLane();
		}
	}

	// Lava where it can reach you: in your space or right beside it, or flowing at or above your feet nearby.
	// Lava behind a wall diagonal to you can't get in, so it doesn't count.
	private static boolean lavaDanger(ClientLevel level, LocalPlayer p) {
		if (p.isInLava()) return true;
		BlockPos feet = p.blockPosition();
		for (BlockPos body : new BlockPos[] {feet, feet.above()}) {
			if (lava(level, body)) return true;
			for (Direction d : Direction.values()) if (lava(level, body.relative(d))) return true;
		}
		for (BlockPos q : BlockPos.betweenClosed(feet.offset(-2, 0, -2), feet.offset(2, 3, 2))) {
			FluidState f = level.getFluidState(q);
			if (f.is(FluidTags.LAVA) && !f.isSource()) return true;
		}
		return false;
	}

	private static boolean lava(ClientLevel level, BlockPos q) {
		return level.getFluidState(q).is(FluidTags.LAVA);
	}

	private static boolean lavaNear(ClientLevel level, BlockPos at, int r) {
		for (BlockPos q : BlockPos.betweenClosed(at.offset(-r, -1, -r), at.offset(r, r, r)))
			if (lava(level, q)) return true;
		return false;
	}

	// Back along the tunnel to the newest waypoint that's a few blocks off and clear of lava, then stop there.
	private static void retreat(Minecraft mc, LocalPlayer p) {
		release(mc);
		BlockPos at = p.blockPosition();
		BlockPos to = null;
		for (BlockPos w : trail)
			if (w.distSqr(at) >= RETREAT * RETREAT && !lavaNear(mc.level, w, 3)) { to = w; break; }
		Baritone.send("stop");
		alarm(mc, p, "Lava! Backing off");
		lavaStop = true;
		if (to == null) {
			halt("lava next to you at " + xyz(at) + " and nowhere safe behind you; stopped. Get clear yourself.");
			return;
		}
		say("lava next to you at " + xyz(at) + "; backing off along the tunnel to " + xyz(to) + ".");
		state = State.RETREAT;
		target = to;
		sinceProgress = 0;
		Baritone.send("goto " + xyz(to));
	}

	private static void startDrink(LocalPlayer p, int slot) {
		Baritone.send("pause");
		afterPause = state;
		state = State.DRINK;
		drinkTicks = 0;
		drinkSlot = slot;
		prevSlot = p.getInventory().getSelectedSlot();
		fireBefore = fireTicks(p);
		say("fire resistance is low; drinking another.");
	}

	// Select the potion and hold use until the effect jumps, as you would.
	private static void drink(Minecraft mc, LocalPlayer p) {
		// A few ticks for Baritone to let go of the controls.
		if (++drinkTicks < 4) return;
		p.getInventory().setSelectedSlot(drinkSlot);
		boolean done = fireTicks(p) > fireBefore + 30 * 20;
		if (!done && drinkTicks < DRINK_TIMEOUT) {
			mc.options.keyUse.setDown(true);
			return;
		}
		mc.options.keyUse.setDown(Xnmc.held(mc.options.keyUse));
		p.getInventory().setSelectedSlot(prevSlot);
		if (!done) { stop("couldn't drink the fire resistance potion."); return; }
		state = afterPause;
		sinceProgress = 0;
		Baritone.send("resume");
	}

	private static void startDump(LocalPlayer p) {
		Baritone.send("pause");
		afterPause = state;
		state = State.DUMP;
		dump = Dump.SETTLE;
		dumpTicks = 0;
		thrown = 0;
		// Throw back down the tunnel already dug, so the way ahead doesn't pick it all up again.
		BlockPos behind = trail.peek();
		dumpYaw = behind == null ? p.getYRot() + 180
				: (float) Math.toDegrees(Math.atan2(behind.getZ() + 0.5 - p.getZ(), behind.getX() + 0.5 - p.getX())) - 90;
	}

	// Stand still, face back down the tunnel, open the inventory and throw the junk out a stack at a time.
	private static void dump(Minecraft mc, LocalPlayer p) {
		dumpTicks++;
		switch (dump) {
			case SETTLE -> {
				double v = p.getDeltaMovement().horizontalDistanceSqr();
				// Wait for any screen you have open, and for Baritone to bring you to a stop.
				if (mc.gui.screen() == null && dumpTicks > 4 && (p.onGround() && v < 1e-4 || dumpTicks > 40)) dump = Dump.TURN;
			}
			case TURN -> {
				float err = Mth.wrapDegrees(dumpYaw - p.getYRot());
				p.setYRot(p.getYRot() + Mth.clamp(err, -TURN, TURN));
				p.setXRot(p.getXRot() + Mth.clamp(-10 - p.getXRot(), -TURN, TURN));
				if (Math.abs(err) < 1 && Math.abs(p.getXRot() + 10) < 1) {
					mc.gui.setScreen(new InventoryScreen(p));
					dump = Dump.THROW;
					dumpTicks = 0;
				}
			}
			case THROW -> {
				if (!(mc.gui.screen() instanceof InventoryScreen)) { dump = Dump.CLOSE; dumpTicks = 0; return; }
				if (dumpTicks % THROW_EVERY != 0) return;
				int slot = junkSlot(p);
				if (slot < 0) {
					mc.gui.setScreen(null);
					dump = Dump.CLOSE;
					dumpTicks = 0;
					return;
				}
				// The inventory menu numbers the hotbar 36-44 and the main inventory 9-35; button 1 throws the stack.
				mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, slot < 9 ? 36 + slot : slot, 1, ContainerInput.THROW, p);
				thrown++;
			}
			case CLOSE -> {
				if (dumpTicks < 5) return;
				LOG.info("dropped {} stacks of junk", thrown);
				state = afterPause;
				sinceProgress = 0;
				Baritone.send("resume");
			}
		}
	}

	private static int junkSlot(LocalPlayer p) {
		Inventory inv = p.getInventory();
		int keep = -1;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++)
			if (inv.getItem(i).is(Items.NETHERRACK) && (keep < 0 || inv.getItem(i).getCount() > inv.getItem(keep).getCount())) keep = i;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++)
			if (i != keep && JUNK.test(inv.getItem(i))) return i;
		return -1;
	}

	private static boolean roomForDebris(LocalPlayer p) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty() || s.is(Items.ANCIENT_DEBRIS) && s.getCount() < s.getMaxStackSize()) return true;
		}
		return false;
	}

	private static int freeSlots(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
		return n;
	}

	// Checked once a second; any answer stops the run.
	private static String unsafe(LocalPlayer p) {
		if (!AfkGuard.armed()) return "AFK Guard is off, so I stopped.";
		if (pickaxe(p) < 0) return "no pickaxe in your hotbar has durability left.";
		// A full inventory is fine while debris still has somewhere to go.
		if (!roomForDebris(p)) return "no room left for debris.";
		return null;
	}

	// Closest debris within SEE blocks with a face open to air that you have a clear line of sight to.
	private static BlockPos seenDebris(ClientLevel level, LocalPlayer p) {
		BlockPos at = p.blockPosition();
		Vec3 eye = p.getEyePosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (BlockPos q : BlockPos.betweenClosed(at.offset(-SEE, -SEE, -SEE), at.offset(SEE, SEE, SEE))) {
			if (!level.getBlockState(q).is(Blocks.ANCIENT_DEBRIS) || skipped.contains(q)) continue;
			// Baritone won't break it, and opening it would let the lava in.
			if (lava(level, q.above()) || lava(level, q.below())) continue;
			boolean wet = false;
			for (Direction d : Direction.Plane.HORIZONTAL) wet |= lava(level, q.relative(d));
			if (wet || !visible(level, p, eye, q)) continue;
			double dd = eye.distanceToSqr(Vec3.atCenterOf(q));
			if (dd < bestD) {
				bestD = dd;
				best = q.immutable();
			}
		}
		return best;
	}

	private static boolean visible(ClientLevel level, LocalPlayer p, Vec3 eye, BlockPos q) {
		Vec3 c = Vec3.atCenterOf(q);
		for (Direction d : Direction.values()) {
			if (!level.getBlockState(q.relative(d)).isAir()) continue;
			Vec3 aim = c.add(d.getStepX() * 0.45, d.getStepY() * 0.45, d.getStepZ() * 0.45);
			BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
			if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(q)) return true;
		}
		return false;
	}

	private static int fireTicks(LocalPlayer p) {
		MobEffectInstance e = p.getEffect(MobEffects.FIRE_RESISTANCE);
		if (e == null) return 0;
		return e.isInfiniteDuration() ? Integer.MAX_VALUE : e.getDuration();
	}

	// Hotbar only: that's where Baritone picks tools from and where a potion can be drunk without opening anything.
	private static int pickaxe(LocalPlayer p) {
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.is(ItemTags.PICKAXES) && (!s.isDamageableItem() || s.getMaxDamage() - s.getDamageValue() > TOOL_RESERVE)) return i;
		}
		return -1;
	}

	private static int potionSlot(LocalPlayer p) {
		for (int i = 0; i < Inventory.getSelectionSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			PotionContents c = s.is(Items.POTION) ? s.get(DataComponents.POTION_CONTENTS) : null;
			if (c == null) continue;
			for (MobEffectInstance e : c.getAllEffects()) if (e.is(MobEffects.FIRE_RESISTANCE)) return i;
		}
		return -1;
	}

	private static int count(LocalPlayer p, net.minecraft.world.item.Item item) {
		int n = 0;
		Inventory inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
		return n;
	}

	private static boolean near(LocalPlayer p, BlockPos to, double within) {
		double dx = p.getX() - (to.getX() + 0.5), dz = p.getZ() - (to.getZ() + 0.5);
		return dx * dx + dz * dz <= within * within && Math.abs(p.getY() - to.getY()) <= 1.2;
	}

	public static void stop(String why) {
		Minecraft mc = Minecraft.getInstance();
		if (state == State.OFF) { say("not running."); return; }
		Baritone.send("stop");
		release(mc);
		state = State.OFF;
		last = why;
		if (mc.player != null && !why.equals("stopped.")) alarm(mc, mc.player, "Debris Miner stopped");
		// Stopped by hand it only says so; stopped by anything else, it is why someone should look.
		if (why.equals("stopped.")) say(why);
		else Xnmc.alert("Debris Miner", ChatFormatting.DARK_AQUA, why);
	}

	// Stop after a retreat, which already sent Baritone on its way.
	private static void halt(String why) {
		state = State.OFF;
		last = why;
		Xnmc.alert("Debris Miner", ChatFormatting.DARK_AQUA, why);
	}

	// Let go of a potion mid-drink and put the hotbar back, or close the inventory mid-dump.
	private static void release(Minecraft mc) {
		if (state == State.DUMP && mc.gui.screen() instanceof InventoryScreen) mc.gui.setScreen(null);
		if (state != State.DRINK) return;
		mc.options.keyUse.setDown(Xnmc.held(mc.options.keyUse));
		if (mc.player != null) mc.player.getInventory().setSelectedSlot(prevSlot);
	}

	private static void alarm(Minecraft mc, LocalPlayer p, String overlay) {
		for (int i = 0; i < 3; i++)
			mc.getSoundManager().playDelayed(SimpleSoundInstance.forUI(SoundEvents.BELL_BLOCK, 1.0f, 1.0f), i * 8);
		p.sendOverlayMessage(Component.literal(overlay).withStyle(ChatFormatting.RED));
	}

	public static String status() {
		Minecraft mc = Minecraft.getInstance();
		if (state == State.OFF) return last + (plan != null ? " Resume: " + plan.label() + "." : "");
		String doing = switch (state) {
			case DEBRIS -> "mining debris at " + xyz(target);
			case DRINK -> "drinking fire resistance";
			case DUMP -> "dropping junk";
			case RETREAT -> "backing off from lava";
			default -> "tunnelling";
		};
		String extra = "";
		if (mc.player != null) {
			int fire = fireTicks(mc.player) / 20;
			extra = ", " + (count(mc.player, Items.ANCIENT_DEBRIS) - startDebris) + " debris, fire resistance "
					+ (fire > 9999 ? "forever" : fire / 60 + ":" + String.format("%02d", fire % 60));
		}
		return doing + ", " + plan.label() + extra + ".";
	}

	private static String xyz(BlockPos b) {
		return b.getX() + " " + b.getY() + " " + b.getZ();
	}

	private static void say(String text) {
		Xnmc.say("Debris Miner", ChatFormatting.DARK_AQUA, text);
	}
}

package xnmc.ghastpilot;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.phys.AABB;
import xnmc.Xnmc;

public final class GhastPilot {
	// Degrees per tick; snapping the camera round is jarring and looks botted.
	private static final float TURN = 6;
	// Beyond this heading error it turns in place instead of flying, so it can't orbit the target.
	private static final float ON_COURSE = 30;
	private static final float MAX_PITCH = 40;
	private static final double ARRIVE = 2;
	// How far ahead, in blocks, to look for terrain the ghast's body would hit.
	private static final int[] LOOKAHEAD = {2, 4, 6, 8, 10};

	private static KeyMapping toggle;
	private static boolean engaged;
	private static Integer destX, destZ, destY;
	// The height it settles back to after climbing over something; cruiseY is the height right now.
	private static double floorY, cruiseY;
	// Quiet runs are driven by Portal Watch, which does its own talking.
	private static boolean quiet;

	public static void init() {
		toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.xnmc.ghastpilot", InputConstants.KEY_O, Xnmc.KEYS));
		ClientTickEvents.START_CLIENT_TICK.register(GhastPilot::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
				ClientCommands.literal("ghastpilot")
						.executes(c -> { say(status()); return 1; })
						.then(ClientCommands.literal("goto")
								.then(ClientCommands.argument("x", IntegerArgumentType.integer())
										.then(ClientCommands.argument("z", IntegerArgumentType.integer())
												.executes(c -> go(int_(c, "x"), null, int_(c, "z"), false)))
										.then(ClientCommands.argument("y", IntegerArgumentType.integer())
												.then(ClientCommands.argument("z", IntegerArgumentType.integer())
														.executes(c -> go(int_(c, "x"), int_(c, "y"), int_(c, "z"), false))))))
						.then(ClientCommands.literal("forward").executes(c -> { forward(); return 1; }))
						.then(ClientCommands.literal("clear").executes(c -> {
							destX = destY = destZ = null;
							say("destination cleared; the key now flies forward.");
							return 1;
						}))
						.then(ClientCommands.literal("off").executes(c -> { off(); return 1; }))));
	}

	public static boolean engaged() { return engaged; }
	public static void forward() { engage(false, false); }
	public static void goTo(int x, Integer y, int z) { go(x, y, z, false); }
	public static void off() { disengage("off."); }
	public static String key() { return toggle.getTranslatedKeyMessage().getString(); }

	// Portal Watch's legs: a goto without the chat line, keeping the cruise height between legs.
	public static void steer(int x, int z) {
		go(x, null, z, true);
	}

	private static int int_(CommandContext<FabricClientCommandSource> c, String name) {
		return IntegerArgumentType.getInteger(c, name);
	}

	private static int go(int x, Integer y, int z, boolean silent) {
		// A new leg of the same run keeps its cruise height; a fresh goto starts from where you are.
		boolean sameRun = engaged && destX != null && silent && quiet;
		destX = x;
		destY = y;
		destZ = z;
		if (ghast() == null) {
			if (!silent) say("destination " + x + (y != null ? " " + y : "") + " " + z + " set. Get on a happy ghast and press "
					+ key() + ".");
			return silent ? 0 : 1;
		}
		if (!sameRun) engage(true, silent);
		return 1;
	}

	public static HappyGhast ghast() {
		LocalPlayer p = Minecraft.getInstance().player;
		return p != null && p.getVehicle() instanceof HappyGhast g ? g : null;
	}

	// toDest false flies wherever you face, with the autopilot only handling height.
	private static void engage(boolean toDest, boolean silent) {
		HappyGhast g = ghast();
		if (g == null) {
			say("get on a happy ghast first.");
			return;
		}
		if (!toDest) destX = destY = destZ = null;
		engaged = true;
		quiet = silent;
		floorY = destY != null ? destY : g.getY();
		cruiseY = Math.max(floorY, g.getY());
		if (silent) return;
		if (destX == null) say("flying the way you face; turn to steer. I'll keep the height and climb over terrain. "
				+ key() + " or back to stop.");
		else say("flying to " + destX + (destY != null ? " " + destY : "") + " " + destZ + ", " + (int) Math.sqrt(distSq(g)) + " blocks.");
	}

	private static void disengage(String why) {
		if (!engaged) return;
		engaged = false;
		Minecraft mc = Minecraft.getInstance();
		mc.options.keyUp.setDown(held(mc.options.keyUp));
		mc.options.keyJump.setDown(held(mc.options.keyJump));
		if (!quiet || why.startsWith("off")) say(why);
	}

	private static double distSq(HappyGhast g) {
		double dx = destX + 0.5 - g.getX(), dz = destZ + 0.5 - g.getZ();
		return dx * dx + dz * dz;
	}

	private static void tick(Minecraft mc) {
		while (toggle.consumeClick()) {
			if (engaged) disengage("off.");
			else engage(destX != null, false);
		}
		if (!engaged) return;
		HappyGhast g = ghast();
		if (g == null) {
			disengage("off: you're not on the ghast any more.");
			return;
		}
		if (held(mc.options.keyDown)) {
			disengage("off.");
			return;
		}
		LocalPlayer p = mc.player;
		boolean forward = true;
		if (destX != null) {
			if (distSq(g) <= ARRIVE * ARRIVE) {
				disengage("arrived at " + destX + " " + destZ + ".");
				if (!quiet) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BELL_BLOCK, 1.0f, 1.0f));
				return;
			}
			float want = (float) Math.toDegrees(Mth.atan2(destZ + 0.5 - g.getZ(), destX + 0.5 - g.getX())) - 90;
			float err = Mth.wrapDegrees(want - p.getYRot());
			p.setYRot(p.getYRot() + Mth.clamp(err, -TURN, TURN));
			forward = Math.abs(err) < ON_COURSE;
		}
		mc.options.keyUp.setDown(forward);
		hold(mc, p, g);
	}

	// Climb when terrain ahead would hit the ghast's body; settle back to floorY once the way down is clear.
	private static void hold(Minecraft mc, LocalPlayer p, HappyGhast g) {
		// Holding jump yourself lifts the height it keeps.
		if (held(mc.options.keyJump)) floorY = cruiseY = Math.max(cruiseY, g.getY());
		double yaw = Math.toRadians(p.getYRot());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);
		AABB body = g.getBoundingBox();
		if (!clear(mc, g, body, fx, fz)) cruiseY = Math.max(cruiseY, g.getY() + 3);
		else if (cruiseY > floorY && clear(mc, g, body.move(0, -2, 0), fx, fz) && mc.level.noCollision(g, body.move(0, -2, 0)))
			cruiseY = Math.max(floorY, cruiseY - 0.25);
		double below = cruiseY - g.getY();
		// Positive pitch looks down; the ghast flies where you look.
		float pitch = (float) Mth.clamp(-below * 8, -MAX_PITCH, MAX_PITCH);
		p.setXRot(p.getXRot() + Mth.clamp(pitch - p.getXRot(), -TURN, TURN));
		mc.options.keyJump.setDown(below > 1.5 || held(mc.options.keyJump));
	}

	private static boolean clear(Minecraft mc, HappyGhast g, AABB body, double fx, double fz) {
		for (int d : LOOKAHEAD)
			if (!mc.level.noCollision(g, body.move(fx * d, 0, fz * d))) return false;
		return true;
	}

	private static boolean held(KeyMapping k) {
		return Xnmc.held(k);
	}

	public static String status() {
		String dest = destX == null ? "no destination" : "destination " + destX + (destY != null ? " " + destY : "") + " " + destZ;
		return (engaged ? (destX == null ? "flying forward" : "flying") : "off") + ", " + dest + ". Key: " + key() + ".";
	}

	private static void say(String text) {
		Xnmc.say("Ghast Pilot", ChatFormatting.AQUA, text);
	}
}

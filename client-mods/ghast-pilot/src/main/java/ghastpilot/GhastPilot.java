package ghastpilot;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.phys.AABB;

public final class GhastPilot implements ClientModInitializer {
	// Degrees per tick; snapping the camera round is jarring and looks botted.
	private static final float TURN = 6;
	private static final float MAX_PITCH = 40;
	private static final double ARRIVE = 6;
	// How far ahead, in blocks, to look for terrain the ghast's body would hit.
	private static final int[] LOOKAHEAD = {2, 4, 6, 8, 10};

	private static KeyMapping toggle;
	private static boolean engaged;
	private static Integer destX, destZ;
	private static double cruiseY;

	@Override
	public void onInitializeClient() {
		toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ghastpilot.toggle", InputConstants.KEY_O,
				KeyMapping.Category.register(Identifier.fromNamespaceAndPath("ghastpilot", "ghastpilot"))));
		ClientTickEvents.START_CLIENT_TICK.register(GhastPilot::tick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
				ClientCommands.literal("ghastpilot")
						.executes(c -> { say(status()); return 1; })
						.then(ClientCommands.literal("goto")
								.then(ClientCommands.argument("x", IntegerArgumentType.integer())
										.then(ClientCommands.argument("z", IntegerArgumentType.integer())
												.executes(c -> {
													destX = IntegerArgumentType.getInteger(c, "x");
													destZ = IntegerArgumentType.getInteger(c, "z");
													if (ghast() != null) engage();
													else say("destination " + destX + " " + destZ + " set. Get on a happy ghast and press "
															+ toggle.getTranslatedKeyMessage().getString() + ".");
													return 1;
												}))))
						.then(ClientCommands.literal("clear").executes(c -> {
							destX = destZ = null;
							say("destination cleared; the key now just cruises forward.");
							return 1;
						}))
						.then(ClientCommands.literal("off").executes(c -> { disengage("off."); return 1; }))));
	}

	private static HappyGhast ghast() {
		LocalPlayer p = Minecraft.getInstance().player;
		return p != null && p.getVehicle() instanceof HappyGhast g ? g : null;
	}

	private static void engage() {
		HappyGhast g = ghast();
		if (g == null) {
			say("get on a happy ghast first.");
			return;
		}
		engaged = true;
		cruiseY = g.getY();
		if (destX == null) say("cruising forward; you steer. " + toggle.getTranslatedKeyMessage().getString() + " or back to stop.");
		else say("flying to " + destX + " " + destZ + ", " + (int) Math.sqrt(distSq(g)) + " blocks. It climbs over terrain but never dives.");
	}

	private static void disengage(String why) {
		if (!engaged) return;
		engaged = false;
		Minecraft mc = Minecraft.getInstance();
		mc.options.keyUp.setDown(held(mc.options.keyUp));
		mc.options.keyJump.setDown(held(mc.options.keyJump));
		say(why);
	}

	private static double distSq(HappyGhast g) {
		double dx = destX + 0.5 - g.getX(), dz = destZ + 0.5 - g.getZ();
		return dx * dx + dz * dz;
	}

	private static void tick(Minecraft mc) {
		while (toggle.consumeClick()) {
			if (engaged) disengage("off.");
			else engage();
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
		mc.options.keyUp.setDown(true);
		if (destX == null) return;

		LocalPlayer p = mc.player;
		if (distSq(g) <= ARRIVE * ARRIVE) {
			disengage("arrived at " + destX + " " + destZ + ".");
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BELL_BLOCK, 1.0f, 1.0f));
			return;
		}
		float want = (float) Math.toDegrees(Mth.atan2(destZ + 0.5 - g.getZ(), destX + 0.5 - g.getX())) - 90;
		p.setYRot(p.getYRot() + Mth.clamp(Mth.wrapDegrees(want - p.getYRot()), -TURN, TURN));

		// Holding jump yourself sets a new, higher cruise height.
		if (held(mc.options.keyJump)) cruiseY = Math.max(cruiseY, g.getY());
		// The ghast flies where you look, so check along your current heading.
		double yaw = Math.toRadians(p.getYRot());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);
		AABB body = g.getBoundingBox();
		for (int d : LOOKAHEAD)
			if (!mc.level.noCollision(g, body.move(fx * d, 0, fz * d))) {
				cruiseY = Math.max(cruiseY, g.getY() + 3);
				break;
			}
		double below = cruiseY - g.getY();
		// Positive pitch looks down; the ghast flies where you look.
		float pitch = (float) Mth.clamp(-below * 8, -MAX_PITCH, MAX_PITCH);
		p.setXRot(p.getXRot() + Mth.clamp(pitch - p.getXRot(), -TURN, TURN));
		mc.options.keyJump.setDown(below > 1.5 || held(mc.options.keyJump));
	}

	// The physical key, not the mapping state this mod sets.
	private static boolean held(KeyMapping k) {
		InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(k);
		return key.getType() == InputConstants.Type.KEYBOARD && InputConstants.isKeyDown(key.getValue());
	}

	private static String status() {
		String dest = destX == null ? "no destination" : "destination " + destX + " " + destZ;
		return (engaged ? "flying" : "off") + ", " + dest + ". Key: " + toggle.getTranslatedKeyMessage().getString() + ".";
	}

	private static void say(String text) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null) p.sendSystemMessage(Component.literal("[Ghast Pilot] " + text).withStyle(ChatFormatting.AQUA));
	}
}

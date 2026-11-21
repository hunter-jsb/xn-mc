package xnmc;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import xnmc.afkguard.AfkGuard;
import xnmc.ghastpilot.GhastPilot;
import xnmc.portalwatch.PortalWatch;

public final class Xnmc implements ClientModInitializer {
	public static final KeyMapping.Category KEYS = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("xnmc", "xnmc"));

	private static KeyMapping menu;

	@Override
	public void onInitializeClient() {
		AfkGuard.init();
		GhastPilot.init();
		PortalWatch.init();
		menu = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.xnmc.menu", InputConstants.KEY_SEMICOLON, KEYS));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (menu.consumeClick()) mc.gui.setScreen(new XnmcScreen(null));
		});
		// Scheduled so the chat screen has closed before the menu opens.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(ClientCommands.literal("xnmc")
				.executes(c -> { Minecraft.getInstance().schedule(() -> Minecraft.getInstance().gui.setScreen(new XnmcScreen(null))); return 1; })));
	}

	// One chat line, prefixed by the feature that said it; only you see it.
	public static void say(String feature, ChatFormatting color, String text) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null) p.sendSystemMessage(Component.literal("[" + feature + "] " + text).withStyle(color));
	}

	// The physical key, not the mapping state a feature may be holding down.
	public static boolean held(KeyMapping k) {
		InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(k);
		return key.getType() == InputConstants.Type.KEYBOARD && InputConstants.isKeyDown(key.getValue());
	}
}

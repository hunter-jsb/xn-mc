package xnmc;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import xnmc.afkguard.AfkGuard;
import xnmc.ghastpilot.GhastPilot;
import xnmc.portalwatch.PortalWatch;

// The one place for every feature: a status line and buttons each. Buttons act and close, so
// you're straight back in the game and any reply lands in chat.
public final class XnmcScreen extends Screen {
	private static final int W = 300, ROW = 20, GAP = 2, SECTION = 50;
	private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFAAAAAA, RED = 0xFFFF7777;
	private final Screen parent;
	private EditBox coords;
	private String error = "";
	private int left, top;

	public XnmcScreen(Screen parent) {
		super(Component.literal("XNMC"));
		this.parent = parent;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	protected void init() {
		left = (width - W) / 2;
		top = Math.max(6, (height - 236) / 2);
		coords = new EditBox(font, left, top + 22, W, ROW, Component.literal("Coordinates"));
		coords.setHint(Component.literal("x z  or  x y z, for the Go to buttons"));
		coords.setMaxLength(40);
		addRenderableWidget(coords);

		int y = top + 22 + ROW + 18;
		row(y + 12, b("Watch", PortalWatch::on), b("Spawn", () -> PortalWatch.go(0, 0)),
				b("Go to", () -> { int[] c = coords(); if (c != null) PortalWatch.go(c[0], c[c.length - 1]); }),
				b("Explore", PortalWatch::explore), b("Resume", PortalWatch::resume), b("Off", PortalWatch::off));
		y += SECTION;
		row(y + 12, b("Fly forward", GhastPilot::forward),
				b("Go to", () -> { int[] c = coords(); if (c != null) GhastPilot.goTo(c[0], c.length == 3 ? c[1] : null, c[c.length - 1]); }),
				b("Off", GhastPilot::off));
		y += SECTION;
		row(y + 12, b("Arm for 12 hours", AfkGuard::arm), b("Off", AfkGuard::off));
		addRenderableWidget(Button.builder(Component.literal("Done"), x -> onClose()).bounds(left, y + SECTION, W, ROW).build());
	}

	private record Action(String label, Runnable run) {}

	private Action b(String label, Runnable run) {
		return new Action(label, run);
	}

	// Buttons share the row evenly.
	private void row(int y, Action... actions) {
		int w = (W - GAP * (actions.length - 1)) / actions.length;
		for (int i = 0; i < actions.length; i++) {
			Action a = actions[i];
			addRenderableWidget(Button.builder(Component.literal(a.label()), x -> {
				error = "";
				a.run().run();
				if (error.isEmpty()) onClose();
			}).bounds(left + i * (w + GAP), y, w, ROW).build());
		}
	}

	private int[] coords() {
		String[] parts = coords.getValue().trim().split("[\\s,]+");
		if (parts.length == 2 || parts.length == 3) {
			try {
				int[] c = new int[parts.length];
				for (int i = 0; i < parts.length; i++) c[i] = Integer.parseInt(parts[i]);
				return c;
			} catch (NumberFormatException ignored) {}
		}
		error = "Type whole-number coordinates as x z or x y z first.";
		return null;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(font, "XNMC", width / 2, top + 6, WHITE);
		int y = top + 22 + ROW + 18;
		section(g, y, "Portal Watch", PortalWatch.status());
		section(g, y + SECTION, "Ghast Pilot", GhastPilot.status());
		section(g, y + 2 * SECTION, "AFK Guard", AfkGuard.status());
		if (!error.isEmpty()) g.centeredText(font, error, width / 2, top + 22 + ROW + 4, RED);
	}

	private void section(GuiGraphicsExtractor g, int y, String name, String status) {
		g.text(font, name, left, y, WHITE);
		g.text(font, font.plainSubstrByWidth(status, W - font.width(name) - 8), left + font.width(name) + 8, y, GREY);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

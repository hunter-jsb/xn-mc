package xnmc;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

// Every feature on one list: its name opens its page, its status sits beside it.
public final class XnmcScreen extends Screen {
	private static final int W = 300, ROW = 20, GAP = 4, NAME = 110;
	private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFAAAAAA;
	private final Screen parent;
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
		top = Math.max(6, (height - (Xnmc.FEATURES.size() + 2) * (ROW + GAP)) / 2);
		int y = top + 20;
		for (Feature f : Xnmc.FEATURES) {
			addRenderableWidget(Button.builder(Component.literal(f.name()), b -> minecraft.gui.setScreen(new FeatureScreen(f, this)))
					.bounds(left, y, NAME, ROW).build());
			y += ROW + GAP;
		}
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(left, y + 8, W, ROW).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(font, "XNMC", width / 2, top + 4, WHITE);
		int y = top + 20;
		for (Feature f : Xnmc.FEATURES) {
			g.text(font, font.plainSubstrByWidth(f.status().get(), W - NAME - 8), left + NAME + 8, y + 6, GREY);
			y += ROW + GAP;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

package xnmc;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.function.Supplier;

// A feature's own page. Its controls lay themselves out top to bottom with field() and row(); a button acts and
// drops you back into the game, where any reply lands in chat, unless it reported an error.
public final class FeatureScreen extends Screen {
    public static void open(Feature feature) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        mc.schedule(() -> mc.gui.setScreen(new FeatureScreen(feature,null)));
    }
	private static final int W = 300, ROW = 20, GAP = 4;
	private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFAAAAAA, RED = 0xFFFF7777;
	private final Feature feature;
	private final Screen parent;
	private String error = "";
	private int left, top, y;

	public record Action(String label, Runnable run) {}

	public static Action act(String label, Runnable run) {
		return new Action(label, run);
	}

	FeatureScreen(Feature feature, Screen parent) {
		super(Component.literal(feature.name()));
		this.feature = feature;
		this.parent = parent;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	protected void init() {
		left = (width - W) / 2;
		top = Math.max(6, height / 4 - 20);
		// Title, then up to two lines of status.
		y = top + 44;
		feature.controls().accept(this);
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(left, y + 8, W, ROW).build());
	}

	public EditBox field(String hint) {
		EditBox box = new EditBox(font, left, y, W, ROW, Component.literal(hint));
		box.setHint(Component.literal(hint));
		box.setMaxLength(64);
		addRenderableWidget(box);
		y += ROW + GAP;
		return box;
	}

	// Buttons share the row evenly.
	public void row(Action... actions) {
		int w = (W - GAP * (actions.length - 1)) / actions.length;
		for (int i = 0; i < actions.length; i++) {
			Action a = actions[i];
			addRenderableWidget(Button.builder(Component.literal(a.label()), b -> {
				error = "";
				a.run().run();
				if (error.isEmpty()) minecraft.gui.setScreen(null);
			}).bounds(left + i * (w + GAP), y, w, ROW).build());
		}
		y += ROW + GAP;
	}

	// A setting can change without closing the feature page.
	public void choice(Supplier<String> label, Runnable change) {
		addRenderableWidget(Button.builder(Component.literal(label.get()), b -> {
			change.run();
			b.setMessage(Component.literal(label.get()));
		}).bounds(left, y, W, ROW).build());
		y += ROW + GAP;
	}

	public void error(String text) {
		error = text;
	}

    public void page(String label, Feature child) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> minecraft.gui.setScreen(new FeatureScreen(child, this)))
                .bounds(left,y,W,ROW).build());
        y += ROW + GAP;
    }

	// "x z" or "x y z" from a field; anything else reports an error and returns null.
	public int[] coords(EditBox box) {
		String[] parts = box.getValue().trim().split("[\\s,]+");
		if (parts.length == 2 || parts.length == 3) {
			try {
				int[] c = new int[parts.length];
				for (int i = 0; i < parts.length; i++) c[i] = Integer.parseInt(parts[i]);
				return c;
			} catch (NumberFormatException ignored) {}
		}
		error("Type whole-number coordinates as x z or x y z first.");
		return null;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(font, feature.name(), width / 2, top, WHITE);
        var lines = font.split(Component.literal(feature.status().get()), W);
        for (int i = 0; i < Math.min(2, lines.size()); i++) g.text(font, lines.get(i), left, top + 16 + i * 9, GREY);
		if (!error.isEmpty()) g.centeredText(font, error, width / 2, y + 8 + ROW + 6, RED);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

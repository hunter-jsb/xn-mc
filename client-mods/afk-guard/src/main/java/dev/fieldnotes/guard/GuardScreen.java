package dev.fieldnotes.guard;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class GuardScreen extends Screen {
    private final Screen parent;
    public GuardScreen(Screen parent) { super(Component.literal("AFK Guard")); this.parent = parent; }
    protected void init() {
        int x = width / 2 - 110, y = Math.max(100, height / 2 - 30);
        addRenderableWidget(Button.builder(Component.literal("Arm AFK monitoring (12 hours)"), b -> GuardClient.arm()).bounds(x, y, 220, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Turn monitoring off"), b -> GuardClient.disable()).bounds(x, y + 25, 220, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Test safety logout (no damage)"), b -> GuardClient.test()).bounds(x, y + 50, 220, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Acknowledge lock; remain offline"), b -> GuardClient.acknowledge()).bounds(x, y + 75, 220, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(x, y + 110, 220, 20).build());
    }
    public void onClose() { minecraft.gui.setScreen(parent); }
    public void extractRenderState(GuiGraphicsExtractor graphics, int mx, int my, float delta) {
        super.extractRenderState(graphics, mx, my, delta);
        graphics.centeredText(font, "AFK Guard — " + GuardClient.guard.mode(), width / 2, 24, 0xFFFFFFFF);
        graphics.textWithWordWrap(font, Component.literal(GuardClient.status()), 20, 42, Math.max(100, width - 40), 0xFFFFCC66);
        graphics.textWithWordWrap(font, Component.literal("First damage • 8-heart cutoff • 6-drumstick cutoff • 12-hour limit"), 20, 68, Math.max(100, width - 40), 0xFFFFFFFF);
        if (!GuardClient.lastNotice().isEmpty()) graphics.textWithWordWrap(font, Component.literal(GuardClient.lastNotice()), 20, height - 36, Math.max(100, width - 40), 0xFFCCCCCC);
    }
}

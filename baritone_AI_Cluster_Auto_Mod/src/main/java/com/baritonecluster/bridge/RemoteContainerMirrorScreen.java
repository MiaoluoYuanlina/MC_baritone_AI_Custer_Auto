package com.baritonecluster.bridge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Client-only mirror of the possessed player's current menu. It never mutates the controller's own inventory. */
public final class RemoteContainerMirrorScreen extends Screen {
    public interface ClickHandler { void click(int menuSlot, int button, boolean quickMove); }

    private volatile RemoteUiState state;
    private final ClickHandler clickHandler;
    private final Runnable closeHandler;
    private final boolean possessionMirror;

    public RemoteContainerMirrorScreen(RemoteUiState state, ClickHandler clickHandler, Runnable closeHandler) {
        this(state, clickHandler, closeHandler, true);
    }

    public RemoteContainerMirrorScreen(RemoteUiState state, ClickHandler clickHandler, Runnable closeHandler, boolean possessionMirror) {
        super(Component.literal("远程容器"));
        this.state = state;
        this.clickHandler = clickHandler;
        this.closeHandler = closeHandler;
        this.possessionMirror = possessionMirror;
    }

    public void update(RemoteUiState updated) { state = updated; }
    public String sourceInstanceId() { return state.sourceInstanceId(); }
    public boolean isPossessionMirror() { return possessionMirror; }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        RemoteUiState current = state;
        Bounds bounds = bounds(current);
        graphics.fill(bounds.left - 8, bounds.top - 24, bounds.right + 8, bounds.bottom + 8, 0xE0181818);
        graphics.outline(bounds.left - 8, bounds.top - 24, bounds.right - bounds.left + 16, bounds.bottom - bounds.top + 32, 0xFF707070);
        graphics.text(font, current.title().isBlank() ? current.screenType() : current.title(), bounds.left, bounds.top - 18, 0xFFFFFFFF);
        for (RemoteSlot slot : current.slots()) {
            int x = bounds.left + slot.x(), y = bounds.top + slot.y();
            boolean hovered = mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18;
            graphics.fill(x, y, x + 18, y + 18, hovered ? 0xFF808080 : 0xFF383838);
            ItemStack stack = stack(slot);
            if (!stack.isEmpty()) {
                graphics.item(stack, x + 1, y + 1);
                graphics.itemDecorations(font, stack, x + 1, y + 1);
                if (hovered) graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
            }
        }
        if (current.carried() != null) {
            ItemStack carried = stack(current.carried());
            if (!carried.isEmpty()) { graphics.item(carried, mouseX - 8, mouseY - 8); graphics.itemDecorations(font, carried, mouseX - 8, mouseY - 8); }
        }
        graphics.text(font, "此界面来自目标玩家；所有点击只经 C# 发送到目标实例", bounds.left, bounds.bottom + 12, 0xFFB8D8FF);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        RemoteUiState current = state;
        Bounds bounds = bounds(current);
        for (RemoteSlot slot : current.slots()) {
            int x = bounds.left + slot.x(), y = bounds.top + slot.y();
            if (event.x() >= x && event.x() < x + 18 && event.y() >= y && event.y() < y + 18) {
                clickHandler.click(slot.menuSlot(), event.button(), (event.modifiers() & 1) != 0);
                return true;
            }
        }
        clickHandler.click(-999, event.button(), false);
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) { closeHandler.run(); return true; }
        return super.keyPressed(event);
    }

    @Override public void onClose() { closeHandler.run(); }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean isPauseScreen() { return false; }

    private Bounds bounds(RemoteUiState current) {
        int maximumX = 176, maximumY = 166;
        for (RemoteSlot slot : current.slots()) { maximumX = Math.max(maximumX, slot.x() + 18); maximumY = Math.max(maximumY, slot.y() + 18); }
        int left = (width - maximumX) / 2, top = (height - maximumY) / 2;
        return new Bounds(left, top, left + maximumX, top + maximumY);
    }

    private static ItemStack stack(RemoteSlot slot) {
        if (slot == null || slot.item() == null || slot.item().isBlank() || slot.count() <= 0) return ItemStack.EMPTY;
        Identifier identifier = Identifier.tryParse(slot.item());
        if (identifier == null) return ItemStack.EMPTY;
        var item = BuiltInRegistries.ITEM.getValue(identifier);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, slot.count());
    }

    private record Bounds(int left, int top, int right, int bottom) { }
}

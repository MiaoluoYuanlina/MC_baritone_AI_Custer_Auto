package com.baritonecluster.bridge;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/** Lists functional blocks that are close enough for the possessed player to interact with. */
public final class NearbyFunctionalBlocksScreen extends Screen {
    private static final int PAGE_SIZE = 10;
    private final List<RemoteFunctionalBlock> blocks;
    private final Consumer<RemoteFunctionalBlock> opener;
    private final Runnable back;
    private int page;

    public NearbyFunctionalBlocksScreen(List<RemoteFunctionalBlock> blocks, Consumer<RemoteFunctionalBlock> opener, Runnable back) {
        super(Component.literal("附近可打开的功能方块")); this.blocks = blocks; this.opener = opener; this.back = back;
    }

    @Override protected void init() {
        int pages = Math.max(1, (blocks.size() + PAGE_SIZE - 1) / PAGE_SIZE); page = Math.min(page, pages - 1);
        int start = page * PAGE_SIZE, end = Math.min(blocks.size(), start + PAGE_SIZE), buttonWidth = Math.min(360, width - 40);
        for (int index = start; index < end; index++) {
            RemoteFunctionalBlock block = blocks.get(index); int y = 48 + (index - start) * 23;
            String label = "方块：" + block.block() + "  |  坐标：(" + block.x() + ", " + block.y() + ", " + block.z() + ")";
            addRenderableWidget(Button.builder(Component.literal(label), button -> opener.accept(block)).bounds((width - buttonWidth) / 2, y, buttonWidth, 20).build());
        }
        int bottom = Math.min(height - 28, 48 + PAGE_SIZE * 23 + 5);
        addRenderableWidget(Button.builder(Component.literal("上一页"), button -> { if (page > 0) { page--; rebuildWidgets(); } }).bounds(width / 2 - 155, bottom, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回 F8"), button -> back.run()).bounds(width / 2 - 45, bottom, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("下一页"), button -> { if (page + 1 < pages) { page++; rebuildWidgets(); } }).bounds(width / 2 + 65, bottom, 90, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks); graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
        if (blocks.isEmpty()) graphics.centeredText(font, Component.literal("目标玩家交互距离内没有可打开的功能方块"), width / 2, 58, 0xFFFFAA55);
    }

    @Override public boolean isPauseScreen() { return false; }
}

package com.baritonecluster.bridge;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Searchable visual editor for Baritone's hard and soft block-breaking rules. */
public final class BlockMiningRulesScreen extends Screen {
    private static final List<String> DEFAULT_AVOID = List.of(
            "minecraft:crafting_table", "minecraft:furnace", "minecraft:chest", "minecraft:trapped_chest");

    private final Screen parent;
    private final Consumer<BlockMiningRules> changed;
    private final Set<String> disallow = new HashSet<>();
    private final Set<String> avoid = new HashSet<>();
    private final List<BlockEntry> allBlocks = new ArrayList<>();
    private final List<BlockEntry> filtered = new ArrayList<>();
    private final List<Button> rowButtons = new ArrayList<>();
    private EditBox searchBox;
    private int scroll;
    private int rowCount;

    public BlockMiningRulesScreen(Screen parent, BlockMiningRules rules, Consumer<BlockMiningRules> changed) {
        super(Component.literal("方块挖掘规则"));
        this.parent = parent;
        this.changed = changed;
        disallow.addAll(rules.disallowBreaking());
        avoid.addAll(rules.avoidBreaking());
        avoid.removeAll(disallow);
        for (var id : BuiltInRegistries.BLOCK.keySet()) {
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            if (block != null) allBlocks.add(new BlockEntry(id.toString(), block.getName().getString(), new ItemStack(block.asItem())));
        }
        allBlocks.sort(Comparator.comparing(BlockEntry::id));
        filtered.addAll(allBlocks);
    }

    @Override protected void init() {
        int panelWidth = Math.min(560, width - 24), left = (width - panelWidth) / 2;
        searchBox = new EditBox(font, left, 34, panelWidth, 20, Component.literal("搜索方块"));
        searchBox.setHint(Component.literal("搜索方块 ID 或本地化名称…"));
        searchBox.setMaxLength(128);
        searchBox.setResponder(this::applySearch);
        addRenderableWidget(searchBox);

        rowCount = Math.max(5, Math.min(14, (height - 126) / 24));
        for (int row = 0; row < rowCount; row++) {
            final int visibleRow = row;
            Button button = Button.builder(Component.empty(), ignored -> cycleRule(visibleRow))
                    .bounds(left + 24, 62 + row * 24, panelWidth - 24, 21).build();
            rowButtons.add(addRenderableWidget(button));
        }

        int bottom = Math.min(height - 28, 82 + rowCount * 24);
        addRenderableWidget(Button.builder(Component.literal("恢复默认"), ignored -> resetDefaults())
                .bounds(width / 2 - 174, bottom, 108, 22).build());
        addRenderableWidget(Button.builder(Component.literal("保存并返回"), ignored -> saveAndClose())
                .bounds(width / 2 - 55, bottom, 110, 22).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), ignored -> onClose())
                .bounds(width / 2 + 66, bottom, 108, 22).build());
        updateRows();
    }

    private void applySearch(String value) {
        String query = value.trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (BlockEntry entry : allBlocks)
            if (query.isEmpty() || entry.id().contains(query) || entry.name().toLowerCase(Locale.ROOT).contains(query)) filtered.add(entry);
        scroll = 0;
        updateRows();
    }

    private void cycleRule(int visibleRow) {
        int index = scroll + visibleRow;
        if (index < 0 || index >= filtered.size()) return;
        String id = filtered.get(index).id();
        if (disallow.remove(id)) {
            avoid.remove(id);
        } else if (avoid.remove(id)) {
            disallow.add(id);
        } else {
            avoid.add(id);
        }
        updateRows();
    }

    private void resetDefaults() {
        disallow.clear();
        avoid.clear();
        avoid.addAll(DEFAULT_AVOID);
        updateRows();
    }

    private void updateRows() {
        if (rowButtons.isEmpty()) return;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, filtered.size() - rowButtons.size())));
        for (int row = 0; row < rowButtons.size(); row++) {
            int index = scroll + row;
            Button button = rowButtons.get(row);
            button.visible = index < filtered.size();
            if (!button.visible) continue;
            BlockEntry entry = filtered.get(index);
            String state = disallow.contains(entry.id()) ? "[禁止挖] " : avoid.contains(entry.id()) ? "[尽量不挖] " : "[允许挖] ";
            String label = state + entry.name() + "  " + entry.id();
            button.setMessage(Component.literal(font.plainSubstrByWidth(label, button.getWidth() - 10)));
        }
    }

    private void saveAndClose() {
        List<String> hard = disallow.stream().sorted().toList();
        List<String> soft = avoid.stream().filter(id -> !disallow.contains(id)).sorted().toList();
        changed.accept(new BlockMiningRules(hard, soft));
        minecraft.setScreen(parent);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        graphics.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal("点击方块循环切换：允许挖 → 尽量不挖 → 禁止挖"), width / 2, 23, 0xFFB8C7D9);
        int panelWidth = Math.min(560, width - 24), left = (width - panelWidth) / 2;
        for (int row = 0; row < rowButtons.size(); row++) {
            int index = scroll + row;
            if (index >= filtered.size()) break;
            ItemStack icon = filtered.get(index).icon();
            if (!icon.isEmpty()) graphics.item(icon, left + 3, 64 + row * 24);
        }
        graphics.text(font, Component.literal("匹配 " + filtered.size() + " / " + allBlocks.size() + "，滚轮翻页"), left, 64 + rowCount * 24, 0xFF9FA9B5, false);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseY >= 58 && mouseY <= 62 + rowCount * 24) {
            scroll -= (int)Math.signum(vertical) * Math.max(1, Math.min(5, (int)Math.ceil(Math.abs(vertical))));
            updateRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private record BlockEntry(String id, String name, ItemStack icon) { }
}

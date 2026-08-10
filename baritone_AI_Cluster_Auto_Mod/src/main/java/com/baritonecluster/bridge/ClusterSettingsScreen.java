package com.baritonecluster.bridge;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Settings shared through C# and rendered only by the designated primary client. */
public final class ClusterSettingsScreen extends Screen {
    private final Screen parent;
    private final Consumer<ClusterDisplaySettings> changed;
    private BlockMiningRules blockRules;
    private final Consumer<BlockMiningRules> blockRulesChanged;
    private boolean allowBreak;
    private boolean showPlayers;
    private boolean showRoutes;
    private boolean showAiReplies;
    private boolean showTaskProgress;
    private Button allowBreakButton;
    private Button showPlayersButton;
    private Button showRoutesButton;
    private Button showAiRepliesButton;
    private Button showTaskProgressButton;

    public ClusterSettingsScreen(Screen parent, ClusterDisplaySettings settings, Consumer<ClusterDisplaySettings> changed,
                                 BlockMiningRules blockRules, Consumer<BlockMiningRules> blockRulesChanged) {
        super(Component.literal("Baritone AI 集群设置"));
        this.parent = parent;
        this.changed = changed;
        this.blockRules = blockRules;
        this.blockRulesChanged = blockRulesChanged;
        allowBreak = settings.allowBaritoneBreak();
        showPlayers = settings.showControllablePlayerBoxes();
        showRoutes = settings.showBaritoneRoutes();
        showAiReplies = settings.showAiRepliesInChat();
        showTaskProgress = settings.showTaskProgress();
    }

    @Override protected void init() {
        int left = width / 2 - 145, buttonWidth = 290;
        allowBreakButton = addRenderableWidget(Button.builder(label("容许 Baritone 寻路破坏方块", allowBreak), button -> {
            allowBreak = !allowBreak; refreshAndSend();
        }).bounds(left, 64, buttonWidth, 22).build());
        showPlayersButton = addRenderableWidget(Button.builder(label("透视可控玩家位置框", showPlayers), button -> {
            showPlayers = !showPlayers; refreshAndSend();
        }).bounds(left, 96, buttonWidth, 22).build());
        showRoutesButton = addRenderableWidget(Button.builder(label("渲染其他实例的 Baritone 路线", showRoutes), button -> {
            showRoutes = !showRoutes; refreshAndSend();
        }).bounds(left, 128, buttonWidth, 22).build());
        showAiRepliesButton = addRenderableWidget(Button.builder(label("在游戏聊天栏显示 AI 回复", showAiReplies), button -> {
            showAiReplies = !showAiReplies; refreshAndSend();
        }).bounds(left, 160, buttonWidth, 22).build());
        showTaskProgressButton = addRenderableWidget(Button.builder(label("右上角显示运行中任务进度", showTaskProgress), button -> {
            showTaskProgress = !showTaskProgress; refreshAndSend();
        }).bounds(left, 192, buttonWidth, 22).build());
        addRenderableWidget(Button.builder(Component.literal("方块挖掘规则（搜索/可视化）…"), button ->
                minecraft.setScreen(new BlockMiningRulesScreen(this, blockRules, rules -> {
                    blockRules = rules;
                    blockRulesChanged.accept(rules);
                })))
                .bounds(left, 224, buttonWidth, 22).build());
        addRenderableWidget(Button.builder(Component.literal("返回 F8"), button -> onClose()).bounds(width / 2 - 55, 276, 110, 22).build());
    }

    private void refreshAndSend() {
        allowBreakButton.setMessage(label("容许 Baritone 寻路破坏方块", allowBreak));
        showPlayersButton.setMessage(label("透视可控玩家位置框", showPlayers));
        showRoutesButton.setMessage(label("渲染其他实例的 Baritone 路线", showRoutes));
        showAiRepliesButton.setMessage(label("在游戏聊天栏显示 AI 回复", showAiReplies));
        showTaskProgressButton.setMessage(label("右上角显示运行中任务进度", showTaskProgress));
        changed.accept(new ClusterDisplaySettings(allowBreak, showPlayers, showRoutes, showAiReplies, showTaskProgress));
    }

    private static Component label(String text, boolean enabled) { return Component.literal(text + "：" + (enabled ? "开启" : "关闭")); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        graphics.centeredText(font, title, width / 2, 20, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal("破坏方块设置同步到全部实例；显示选项只影响主要玩家客户端"), width / 2, 40, 0xFFAAAAAA);
        graphics.centeredText(font, Component.literal("关闭全局破坏后，方块规则仍会保留，并在重新允许破坏时生效"), width / 2, 256, 0xFFFFC76D);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

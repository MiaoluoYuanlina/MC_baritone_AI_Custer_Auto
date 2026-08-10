package com.baritonecluster.bridge;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** F8 screen shown only on the C#-designated primary client. */
public final class PossessionScreen extends Screen {
    private final List<RemoteInstance> instances;
    private final String localInstanceId;
    private final String selectedInstanceId;
    private final Consumer<String> selection;
    private final Consumer<String> openInventory;
    private final Consumer<String> openNearbyBlocks;
    private final List<RemoteClusterPlayer> players;
    private final BiConsumer<String, String> followPlayer;
    private final Consumer<String> stopBaritone;
    private final Runnable openSettings;

    public PossessionScreen(List<RemoteInstance> instances, String localInstanceId, String selectedInstanceId, Consumer<String> selection, Consumer<String> openInventory, Consumer<String> openNearbyBlocks,
                            List<RemoteClusterPlayer> players, BiConsumer<String, String> followPlayer, Consumer<String> stopBaritone, Runnable openSettings) {
        super(Component.literal("Baritone AI 集群附身控制"));
        this.instances = instances;
        this.localInstanceId = localInstanceId;
        this.selectedInstanceId = selectedInstanceId;
        this.selection = selection;
        this.openInventory = openInventory;
        this.openNearbyBlocks = openNearbyBlocks;
        this.players = players;
        this.followPlayer = followPlayer;
        this.stopBaritone = stopBaritone;
        this.openSettings = openSettings;
    }

    @Override protected void init() {
        int totalWidth = Math.min(820, width - 24), actionWidth = 88, gap = 4;
        int possessionWidth = Math.max(120, totalWidth - actionWidth * 4 - gap * 4), left = (width - totalWidth) / 2;
        int index = 0;
        for (RemoteInstance instance : instances) {
            if (instance.instanceId().equals(localInstanceId) || instance.primary()) continue;
            int y = 58 + index * 26;
            String label = instance.displayName();
            if (instance.instanceId().equals(selectedInstanceId) && !label.startsWith("✓")) label = "✓ " + label;
            String target = instance.instanceId();
            addRenderableWidget(Button.builder(Component.literal("附身 · " + label), button -> { selection.accept(target); onClose(); }).bounds(left, y, possessionWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("打开背包"), button -> openInventory.accept(target)).bounds(left + possessionWidth + gap, y, actionWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("附近方块"), button -> openNearbyBlocks.accept(target)).bounds(left + possessionWidth + gap + actionWidth + gap, y, actionWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("跟随玩家"), button -> minecraft.setScreen(new FollowPlayerScreen(this, target, instance.playerName(), players, followPlayer))).bounds(left + possessionWidth + gap + (actionWidth + gap) * 2, y, actionWidth, 20).build());
            addRenderableWidget(Button.builder(Component.literal("停止 Baritone"), button -> stopBaritone.accept(target)).bounds(left + possessionWidth + gap + (actionWidth + gap) * 3, y, actionWidth, 20).build());
            index++;
        }
        int bottom = Math.min(height - 32, 70 + index * 26);
        addRenderableWidget(Button.builder(Component.literal("结束附身"), button -> { selection.accept(null); onClose(); }).bounds(width / 2 - 158, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("集群设置"), button -> openSettings.run()).bounds(width / 2 - 50, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose()).bounds(width / 2 + 58, bottom, 100, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        graphics.centeredText(font, title, width / 2, 18, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal("选择实例后：WASD/跳跃/潜行/疾跑、视角、左右键、E、F、Q、快捷栏会经 C# 转发"), width / 2, 36, 0xFFB0B0B0);
    }

    @Override public boolean isPauseScreen() { return false; }
}

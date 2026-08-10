package com.baritonecluster.bridge;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

public final class FollowPlayerScreen extends Screen {
    private static final int PAGE_SIZE = 10;
    private final Screen parent;
    private final String followerInstanceId;
    private final String followerName;
    private final List<RemoteClusterPlayer> players;
    private final BiConsumer<String, String> followHandler;
    private int page;

    public FollowPlayerScreen(Screen parent, String followerInstanceId, String followerName,
                              List<RemoteClusterPlayer> players, BiConsumer<String, String> followHandler) {
        super(Component.literal("选择要跟随的玩家"));
        this.parent = parent;
        this.followerInstanceId = followerInstanceId;
        this.followerName = followerName;
        String followerDimension = players.stream().filter(player -> player.instanceId().equals(followerInstanceId)).map(RemoteClusterPlayer::dimension).findFirst().orElse("");
        this.players = players.stream().filter(player -> !player.name().equalsIgnoreCase(followerName))
                .filter(player -> followerDimension.isBlank() || player.dimension().equals(followerDimension))
                .sorted(Comparator.comparing(RemoteClusterPlayer::dimension).thenComparing(RemoteClusterPlayer::name, String.CASE_INSENSITIVE_ORDER)).toList();
        this.followHandler = followHandler;
    }

    @Override protected void init() {
        int left = width / 2 - 190;
        int from = page * PAGE_SIZE, to = Math.min(players.size(), from + PAGE_SIZE);
        for (int index = from; index < to; index++) {
            RemoteClusterPlayer player = players.get(index);
            int y = 48 + (index - from) * 24;
            String position = player.positionAvailable() ? String.format("(%.0f, %.0f, %.0f)", player.x(), player.y(), player.z()) : "位置未知";
            String label = player.name() + " · " + shortDimension(player.dimension()) + " · " + position;
            addRenderableWidget(Button.builder(Component.literal(label), button -> {
                followHandler.accept(followerInstanceId, player.name());
                minecraft.setScreen(parent);
            }).bounds(left, y, 380, 20).build());
        }
        int pages = Math.max(1, (players.size() + PAGE_SIZE - 1) / PAGE_SIZE), bottom = Math.min(height - 32, 58 + PAGE_SIZE * 24);
        Button previous = addRenderableWidget(Button.builder(Component.literal("上一页"), button -> { page--; rebuildWidgets(); }).bounds(width / 2 - 155, bottom, 90, 20).build());
        previous.active = page > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal("下一页"), button -> { page++; rebuildWidgets(); }).bounds(width / 2 - 45, bottom, 90, 20).build());
        next.active = page + 1 < pages;
        addRenderableWidget(Button.builder(Component.literal("返回 F8"), button -> onClose()).bounds(width / 2 + 65, bottom, 90, 20).build());
    }

    private static String shortDimension(String dimension) {
        int separator = dimension.indexOf(':'); return separator >= 0 ? dimension.substring(separator + 1) : dimension;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal("控制实例：" + followerName + "；选择后命令经 C# 转发"), width / 2, 31, 0xFFB0B0B0);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

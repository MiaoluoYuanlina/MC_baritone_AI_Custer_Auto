package com.baritonecluster.bridge;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Compact top-right progress panel for the primary player's current C# task plan. */
public final class ClusterTaskHud {
    private ClusterTaskHud() { }

    public static void register(ClusterBridgeClient bridge) {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("baritone_ai_cluster_auto", "task_progress"),
                (graphics, deltaTracker) -> render(bridge, graphics));
    }

    private static void render(ClusterBridgeClient bridge, GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        ClusterTaskProgress progress = bridge.taskProgress();
        if (!bridge.isPrimaryInstance() || !bridge.displaySettings().showTaskProgress() || progress == null || !progress.active() || client.font == null) return;

        List<Line> lines = new ArrayList<>();
        int shownStep = Math.min(progress.currentStep() + 1, Math.max(1, progress.totalSteps()));
        lines.add(new Line("AI 任务进度  " + shownStep + "/" + progress.totalSteps(), 0xFFFFFFFF));
        lines.add(new Line(progress.title(), 0xFFFFD37A));
        int reservedLines = 4 + Math.min(5, progress.instances().size());
        int maxStepLines = Math.max(3, (client.getWindow().getGuiScaledHeight() - 32) / 11 - reservedLines);
        int firstStep = 0;
        int lastStep = progress.steps().size();
        if (progress.steps().size() > maxStepLines) {
            firstStep = Math.max(0, progress.currentStep() - 2);
            lastStep = Math.min(progress.steps().size(), firstStep + maxStepLines);
            firstStep = Math.max(0, lastStep - maxStepLines);
        }
        if (firstStep > 0) lines.add(new Line("… 前面 " + firstStep + " 步", 0xFF7F8995));
        for (int index = firstStep; index < lastStep; index++) {
            String marker = index < progress.currentStep() ? "✓ " : index == progress.currentStep() ? "▶ " : "· ";
            int color = index < progress.currentStep() ? 0xFF72E69A : index == progress.currentStep() ? 0xFF63D5FF : 0xFF9AA3AD;
            lines.add(new Line(marker + (index + 1) + ". " + progress.steps().get(index), color));
        }
        if (lastStep < progress.steps().size()) lines.add(new Line("… 后面 " + (progress.steps().size() - lastStep) + " 步", 0xFF7F8995));
        int displayedInstances = 0;
        for (var instance : progress.instances()) {
            if (displayedInstances++ >= 5) break;
            String amount = instance.requiredCount() > 0 ? "  " + instance.currentItemCount() + "/" + instance.requiredCount() : "";
            String working = instance.baritoneWorking() ? " · Baritone工作中" : "";
            lines.add(new Line(instance.playerName() + "：" + instance.stageLabel() + amount + working, 0xFFE3E8EE));
        }
        if (progress.instances().size() > 5) lines.add(new Line("另有 " + (progress.instances().size() - 5) + " 个实例…", 0xFFAAB2BC));

        int maxTextWidth = Math.min(330, client.getWindow().getGuiScaledWidth() - 24);
        int contentWidth = 180;
        for (Line line : lines) contentWidth = Math.max(contentWidth, Math.min(maxTextWidth, client.font.width(line.text())));
        int panelWidth = contentWidth + 16, lineHeight = 11, panelHeight = lines.size() * lineHeight + 12;
        int right = client.getWindow().getGuiScaledWidth() - 8, left = right - panelWidth, top = 8;
        graphics.fill(left, top, right, top + panelHeight, 0xC010141A);
        graphics.fill(left, top, left + 3, top + panelHeight, 0xFF36BFEF);
        int y = top + 7;
        for (Line line : lines) {
            String text = client.font.plainSubstrByWidth(line.text(), contentWidth);
            graphics.text(client.font, Component.literal(text), left + 9, y, line.color(), false);
            y += lineHeight;
        }
    }

    private record Line(String text, int color) { }
}

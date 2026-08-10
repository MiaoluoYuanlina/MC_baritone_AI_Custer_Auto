package com.baritonecluster.bridge;

import java.util.List;

public record ClusterTaskProgress(boolean active, boolean complete, String title, int currentStep, int totalSteps,
                                  List<String> steps, List<TaskInstanceProgress> instances) {
    public record TaskInstanceProgress(String instanceId, String playerName, String stage, String stageLabel,
                                       int currentStep, int currentItemCount, int requiredCount, boolean baritoneWorking) { }
}

package com.baritonecluster.bridge;

import java.util.List;

/** Cluster-wide Baritone block breaking policy. */
public record BlockMiningRules(List<String> disallowBreaking, List<String> avoidBreaking) {
    public BlockMiningRules {
        disallowBreaking = List.copyOf(disallowBreaking);
        avoidBreaking = List.copyOf(avoidBreaking);
    }
}

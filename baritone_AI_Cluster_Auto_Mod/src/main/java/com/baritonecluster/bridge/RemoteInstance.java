package com.baritonecluster.bridge;

public record RemoteInstance(String instanceId, String playerName, boolean primary, boolean possessionTarget) {
    public String displayName() {
        String name = playerName == null || playerName.isBlank() ? instanceId.substring(0, Math.min(12, instanceId.length())) : playerName;
        return (possessionTarget ? "✓ " : "") + name + " · " + instanceId.substring(0, Math.min(8, instanceId.length()));
    }
}

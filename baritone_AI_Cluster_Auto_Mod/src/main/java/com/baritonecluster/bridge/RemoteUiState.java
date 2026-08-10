package com.baritonecluster.bridge;

import java.util.List;

public record RemoteUiState(String sourceInstanceId, long sequence, String dimension, String playerName, String playerUuid,
                            double x, double y, double z, float yaw, float pitch,
                            float eyeHeight,
                            float health, float maxHealth, int food, float saturation,
                            int selectedHotbar,
                            List<RemoteSlot> hotbar, RemoteSlot offhand,
                            boolean screenOpen, String screenType, String title, int containerId,
                            List<RemoteSlot> slots, RemoteSlot carried,
                            List<RemoteFunctionalBlock> functionalBlocks,
                            List<RemotePlayerSnapshot> nearbyPlayers,
                            List<RemoteEntitySnapshot> nearbyEntities,
                            List<RemotePreviewBlock> previewBlocks,
                            int previewRadius) { }

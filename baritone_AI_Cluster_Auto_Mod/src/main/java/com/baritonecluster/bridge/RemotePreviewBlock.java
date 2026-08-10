package com.baritonecluster.bridge;

import java.util.Map;

/** One voxel in the remote player's interaction-range snapshot. */
public record RemotePreviewBlock(String block, int x, int y, int z, int color, boolean occluding, Map<String, String> properties) { }

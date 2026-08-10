package com.baritonecluster.bridge;

import java.util.List;

public record RemoteBaritoneRoute(String instanceId, String playerName, String dimension, List<RemotePathPoint> points) { }

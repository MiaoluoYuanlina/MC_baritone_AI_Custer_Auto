package com.baritonecluster.bridge;

public record RemoteClusterPlayer(String uuid, String name, String dimension, boolean positionAvailable,
                                  double x, double y, double z, boolean controllable, String instanceId) { }

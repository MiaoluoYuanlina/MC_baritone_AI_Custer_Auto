package com.baritonecluster.bridge;

import java.util.List;

public record RemoteEntitySnapshot(String uuid, String type,
                                   double x, double y, double z,
                                   float yaw, float pitch,
                                   double velocityX, double velocityY, double velocityZ,
                                   boolean invisible, boolean glowing, boolean onGround,
                                   String customName, String item, int itemCount,
                                   List<RemoteEntityEquipment> equipment) { }

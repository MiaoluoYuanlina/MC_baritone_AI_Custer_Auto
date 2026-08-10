package com.baritonecluster.bridge;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.Level;

/** A client-only, non-player camera with no walk bob, hurt tilt, model, collision, or network presence. */
final class PossessionCameraEntity extends Marker {
    PossessionCameraEntity(Level level) { super(EntityType.MARKER, level); }

    @Override public float getViewYRot(float partialTick) { return getYRot(); }
    @Override public float getViewXRot(float partialTick) { return getXRot(); }
}

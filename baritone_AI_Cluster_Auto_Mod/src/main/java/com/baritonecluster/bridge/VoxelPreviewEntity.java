package com.baritonecluster.bridge;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Client-only carrier for a complete multi-block model rendered inside one GUI picture-in-picture pass. */
public final class VoxelPreviewEntity extends Marker {
    private List<Voxel> voxels = List.of();

    public VoxelPreviewEntity(EntityType<?> type, Level level) { super(type, level); }
    public List<Voxel> voxels() { return voxels; }
    public void setVoxels(List<Voxel> voxels) { this.voxels = List.copyOf(voxels); }

    public record Voxel(BlockState state, float x, float y, float z) { }
}

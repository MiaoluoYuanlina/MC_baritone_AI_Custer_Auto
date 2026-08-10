package com.baritonecluster.bridge;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

import java.util.List;

public final class VoxelPreviewRenderState extends EntityRenderState {
    public List<VoxelModel> voxels = List.of();
    public record VoxelModel(BlockModelRenderState model, float x, float y, float z) { }
}

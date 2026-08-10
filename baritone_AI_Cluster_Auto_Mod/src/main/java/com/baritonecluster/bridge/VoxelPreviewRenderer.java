package com.baritonecluster.bridge;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;

import java.util.ArrayList;
import java.util.List;

/** Submits every nearby block model into one depth-buffered GUI 3D scene. */
public final class VoxelPreviewRenderer extends EntityRenderer<VoxelPreviewEntity, VoxelPreviewRenderState> {
    private final BlockModelResolver blockModels;

    public VoxelPreviewRenderer(EntityRendererProvider.Context context) {
        super(context); blockModels = context.getBlockModelResolver(); shadowRadius = 0.0f; shadowStrength = 0.0f;
    }

    @Override public VoxelPreviewRenderState createRenderState() { return new VoxelPreviewRenderState(); }

    @Override public void extractRenderState(VoxelPreviewEntity entity, VoxelPreviewRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        List<VoxelPreviewRenderState.VoxelModel> rendered = new ArrayList<>(entity.voxels().size());
        for (VoxelPreviewEntity.Voxel voxel : entity.voxels()) {
            BlockModelRenderState model = new BlockModelRenderState();
            blockModels.update(model, voxel.state(), DisplayRenderer.BLOCK_DISPLAY_CONTEXT);
            if (!model.isEmpty()) rendered.add(new VoxelPreviewRenderState.VoxelModel(model, voxel.x(), voxel.y(), voxel.z()));
        }
        state.voxels = List.copyOf(rendered); state.boundingBoxWidth = 18.0f; state.boundingBoxHeight = 18.0f;
    }

    @Override public void submit(VoxelPreviewRenderState state, PoseStack poses, SubmitNodeCollector output, CameraRenderState camera) {
        for (VoxelPreviewRenderState.VoxelModel voxel : state.voxels) {
            poses.pushPose(); poses.translate(voxel.x(), voxel.y(), voxel.z());
            voxel.model().submit(poses, output, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, VoxelPreviewRenderState.NO_OUTLINE);
            poses.popPose();
        }
    }
}

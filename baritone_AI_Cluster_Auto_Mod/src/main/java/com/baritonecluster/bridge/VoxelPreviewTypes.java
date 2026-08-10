package com.baritonecluster.bridge;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

@SuppressWarnings("deprecation")
public final class VoxelPreviewTypes {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("baritone_ai_cluster_auto", "voxel_preview");
    private static final ResourceKey<EntityType<?>> KEY = ResourceKey.create(Registries.ENTITY_TYPE, ID);
    public static final EntityType<VoxelPreviewEntity> TYPE = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            ID,
            EntityType.Builder.<VoxelPreviewEntity>of(VoxelPreviewEntity::new, MobCategory.MISC)
                    .sized(0.0f, 0.0f)
                    .noSave()
                    .noSummon()
                    .build(KEY));

    private VoxelPreviewTypes() { }
    public static void registerRenderer() { EntityRendererRegistry.register(TYPE, VoxelPreviewRenderer::new); }
}

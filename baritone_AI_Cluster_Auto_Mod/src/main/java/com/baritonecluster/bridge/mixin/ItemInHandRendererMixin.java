package com.baritonecluster.bridge.mixin;

import com.baritonecluster.bridge.ClusterBridgeClient;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @Shadow private ItemStack mainHandItem;
    @Shadow private ItemStack offHandItem;

    @Inject(method = "tick", at = @At("TAIL"))
    private void baritoneCluster$syncPossessedHands(CallbackInfo callback) {
        ItemStack mainHand = ClusterBridgeClient.possessionMainHandItem();
        ItemStack offHand = ClusterBridgeClient.possessionOffhandItem();
        if (mainHand != null && offHand != null) { mainHandItem = mainHand; offHandItem = offHand; }
    }
}

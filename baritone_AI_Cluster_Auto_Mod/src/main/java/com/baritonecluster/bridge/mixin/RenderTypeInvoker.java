package com.baritonecluster.bridge.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderType.class)
public interface RenderTypeInvoker {
    @Invoker("create")
    static RenderType baritoneCluster$create(String name, RenderSetup setup) {
        throw new AssertionError("Mixin invoker was not applied");
    }
}

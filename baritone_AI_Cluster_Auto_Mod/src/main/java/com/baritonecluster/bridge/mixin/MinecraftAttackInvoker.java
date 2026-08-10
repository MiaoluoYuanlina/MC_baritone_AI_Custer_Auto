package com.baritonecluster.bridge.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAttackInvoker {
    @Invoker("startAttack")
    boolean baritoneCluster$startAttack();

    @Invoker("startUseItem")
    void baritoneCluster$startUseItem();
}

package com.baritonecluster.bridge.mixin;

import com.baritonecluster.bridge.ClusterBridgeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public abstract class MinecraftInputMixin {
    @Redirect(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
    private boolean baritoneCluster$allowBackgroundContinuousAttack(MouseHandler mouseHandler) {
        return mouseHandler.isMouseGrabbed() || ClusterBridgeClient.shouldContinueRemoteAttackWithoutLocalMouse();
    }
}

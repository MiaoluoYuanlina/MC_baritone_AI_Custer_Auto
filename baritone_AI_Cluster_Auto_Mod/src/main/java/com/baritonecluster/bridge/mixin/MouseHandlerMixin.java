package com.baritonecluster.bridge.mixin;

import com.baritonecluster.bridge.ClusterBridgeClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void baritoneCluster$routeCameraTurn(LocalPlayer player, double yawDelta, double pitchDelta) {
        ClusterBridgeClient.routeCameraTurn(player, yawDelta, pitchDelta);
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void baritoneCluster$routeHotbarScroll(long window, double horizontal, double vertical, CallbackInfo callback) {
        if (ClusterBridgeClient.routeHotbarScroll(horizontal, vertical)) callback.cancel();
    }
}

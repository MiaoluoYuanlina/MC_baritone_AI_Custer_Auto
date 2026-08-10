package com.baritonecluster.bridge;

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import com.baritonecluster.bridge.mixin.RenderTypeInvoker;

import java.util.Optional;

/** See-through player boxes and remote Baritone path nodes for the primary client. */
public final class ClusterOverlayRenderer {
    private static final RenderPipeline XRAY_LINE_PIPELINE = createXrayLinePipeline();
    private static final RenderType XRAY_LINES = RenderTypeInvoker.baritoneCluster$create(
            "baritone_cluster_xray_lines", RenderSetup.builder(XRAY_LINE_PIPELINE).createRenderSetup());
    private final ClusterBridgeClient bridge;

    private ClusterOverlayRenderer(ClusterBridgeClient bridge) { this.bridge = bridge; }

    public static void register(ClusterBridgeClient bridge) {
        ClusterOverlayRenderer renderer = new ClusterOverlayRenderer(bridge);
        LevelRenderEvents.COLLECT_SUBMITS.register(renderer::render);
    }

    private void render(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (!bridge.isPrimaryInstance() || client.level == null || client.player == null) return;
        ClusterDisplaySettings settings = bridge.displaySettings();
        if (!settings.showControllablePlayerBoxes() && !settings.showBaritoneRoutes()) return;
        String dimension = client.level.dimension().identifier().toString();
        Vec3 camera = context.gameRenderer().getMainCamera().position();
        var poses = context.poseStack(); var lines = context.bufferSource().getBuffer(XRAY_LINES);
        poses.pushPose(); poses.translate(-camera.x, -camera.y, -camera.z);
        try {
            if (settings.showControllablePlayerBoxes()) for (RemoteClusterPlayer player : bridge.overlayPlayers()) {
                if (!player.controllable() || !player.positionAvailable() || !dimension.equals(player.dimension()) || bridge.localInstanceId().equals(player.instanceId())) continue;
                AABB box = new AABB(player.x() - 0.36, player.y(), player.z() - 0.36, player.x() + 0.36, player.y() + 1.9, player.z() + 0.36);
                ShapeRenderer.renderShape(poses, lines, Shapes.create(box), 0, 0, 0, 0xFF00E5FF, 3.0f);
            }
            if (settings.showBaritoneRoutes()) for (RemoteBaritoneRoute route : bridge.overlayRoutes()) {
                if (!dimension.equals(route.dimension()) || bridge.localInstanceId().equals(route.instanceId()) || route.points().isEmpty()) continue;
                int color = routeColor(route.instanceId()), step = Math.max(1, route.points().size() / 128);
                for (int index = 0; index < route.points().size(); index += step) {
                    RemotePathPoint point = route.points().get(index);
                    AABB node = new AABB(point.x() + 0.18, point.y() + 0.06, point.z() + 0.18, point.x() + 0.82, point.y() + 0.18, point.z() + 0.82);
                    ShapeRenderer.renderShape(poses, lines, Shapes.create(node), 0, 0, 0, color, 2.0f);
                }
            }
        } finally { poses.popPose(); }
    }

    private static int routeColor(String instanceId) {
        int hash = instanceId == null ? 0 : instanceId.hashCode();
        int red = 96 + Math.floorMod(hash, 160), green = 96 + Math.floorMod(hash >> 8, 160), blue = 96 + Math.floorMod(hash >> 16, 160);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static RenderPipeline createXrayLinePipeline() {
        RenderPipeline base = RenderPipelines.LINES_TRANSLUCENT;
        RenderPipeline.Snippet copied = new RenderPipeline.Snippet(
                Optional.of(base.getVertexShader()), Optional.of(base.getFragmentShader()), Optional.of(base.getShaderDefines()),
                Optional.of(base.getSamplers()), Optional.of(base.getUniforms()), Optional.of(base.getColorTargetState()),
                Optional.of(new DepthStencilState(CompareOp.ALWAYS_PASS, false)), Optional.of(base.getPolygonMode()),
                Optional.of(base.isCull()), Optional.of(base.getVertexFormat()), Optional.of(base.getVertexFormatMode()));
        return RenderPipeline.builder(copied)
                .withLocation(Identifier.fromNamespaceAndPath("baritone_ai_cluster_auto", "pipeline/xray_lines"))
                .build();
    }

    private ClusterOverlayRenderer() { throw new UnsupportedOperationException(); }
}

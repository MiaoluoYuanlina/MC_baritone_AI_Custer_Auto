package com.baritonecluster.bridge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Standalone software-rendered voxel viewport. It never changes Minecraft's camera or client level. */
public final class RemoteBlockPreviewScreen extends Screen {
    private static final Direction[] FACES = Direction.values();
    private static final int[][] BOX_EDGES = { {0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7} };
    private final ClusterBridgeClient bridge;
    private final String targetInstanceId;
    private volatile RemoteUiState state;
    private final Map<BlockPos, RemotePreviewBlock> blocks = new HashMap<>();
    private VoxelPreviewEntity sceneEntity;
    private EntityRenderState sceneRenderState;
    private Level sceneLevel;
    private boolean sceneDirty = true;
    private Integer layerFilter;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private float cameraYaw;
    private float cameraPitch;
    private double zoom = 1.0;
    private boolean forward;
    private boolean back;
    private boolean left;
    private boolean right;
    private boolean up;
    private boolean down;
    private boolean mining;
    private boolean middlePressed;
    private boolean middleDragged;
    private double middleDownX;
    private double middleDownY;
    private double pointerX;
    private double pointerY;
    private int previewRadius;
    private EditBox radiusBox;

    public RemoteBlockPreviewScreen(ClusterBridgeClient bridge, RemoteUiState initial) {
        super(Component.literal("远程 3D 方块交互"));
        this.bridge = bridge;
        this.targetInstanceId = initial.sourceInstanceId();
        this.state = initial;
        this.cameraX = initial.x(); this.cameraY = initial.y() + initial.eyeHeight(); this.cameraZ = initial.z();
        this.cameraYaw = initial.yaw(); this.cameraPitch = initial.pitch();
        this.previewRadius = Math.max(2, Math.min(16, initial.previewRadius()));
        replaceBlocks(initial.previewBlocks());
    }

    public String targetInstanceId() { return targetInstanceId; }

    public void update(RemoteUiState updated) {
        RemoteUiState previous = state;
        double dx = updated.x() - previous.x(), dy = updated.y() - previous.y(), dz = updated.z() - previous.z();
        cameraX += dx; cameraY += dy; cameraZ += dz;
        state = updated;
        previewRadius = Math.max(2, Math.min(16, updated.previewRadius()));
        if (radiusBox != null && !radiusBox.isFocused()) radiusBox.setValue(Integer.toString(previewRadius));
        replaceBlocks(updated.previewBlocks());
    }

    private synchronized void replaceBlocks(List<RemotePreviewBlock> updated) {
        blocks.clear();
        for (RemotePreviewBlock block : updated) blocks.put(new BlockPos(block.x(), block.y(), block.z()), block);
        sceneDirty = true;
    }

    @Override protected void init() {
        int y = 52, buttonWidth = 72, gap = 5, radiusWidth = 42, applyWidth = 68;
        int total = buttonWidth * 4 + radiusWidth + applyWidth + gap * 5, leftX = Math.max(4, (width - total) / 2);
        addRenderableWidget(Button.builder(Component.literal("上一层"), button -> changeLayer(-1)).bounds(leftX, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("玩家所在层"), button -> setLayer((int)Math.floor(state.y()))).bounds(leftX + buttonWidth + gap, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("下一层"), button -> changeLayer(1)).bounds(leftX + (buttonWidth + gap) * 2, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("显示全部层"), button -> setLayer(null)).bounds(leftX + (buttonWidth + gap) * 3, y, buttonWidth, 20).build());
        int radiusX = leftX + (buttonWidth + gap) * 4;
        radiusBox = new EditBox(font, radiusX, y, radiusWidth, 20, Component.literal("显示半径")); radiusBox.setMaxLength(2); radiusBox.setValue(Integer.toString(previewRadius)); radiusBox.setHint(Component.literal("2-16")); addRenderableWidget(radiusBox);
        addRenderableWidget(Button.builder(Component.literal("应用半径"), button -> applyRadius()).bounds(radiusX + radiusWidth + gap, y, applyWidth, 20).build());
    }

    private void changeLayer(int amount) { setLayer((layerFilter == null ? (int)Math.floor(state.y()) : layerFilter) + amount); }
    private void setLayer(Integer layer) { layerFilter = layer; sceneDirty = true; bridge.previewStopMining(); mining = false; }
    private void applyRadius() {
        int radius;
        try { radius = Integer.parseInt(radiusBox.getValue().trim()); } catch (NumberFormatException ignored) { radius = previewRadius; }
        previewRadius = Math.max(2, Math.min(16, radius)); radiusBox.setValue(Integer.toString(previewRadius)); sceneDirty = true; bridge.previewSetRadius(previewRadius);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        pointerX = mouseX; pointerY = mouseY;
        Viewport viewport = viewport();
        graphics.fill(8, 8, width - 8, height - 8, 0xE0080B10);
        graphics.fillGradient(viewport.left, viewport.top, viewport.right, viewport.bottom, 0xFF182334, 0xFF07090D);
        graphics.enableScissor(viewport.left, viewport.top, viewport.right, viewport.bottom);
        BlockHitResult selected = viewport.contains(mouseX, mouseY) ? raycast(mouseX, mouseY) : null;
        renderVoxelScene(graphics, viewport);
        if (selected != null) outlineBlock(graphics, selected.getBlockPos(), viewport, 0xFFFFFF55);
        graphics.disableScissor();
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);

        RemoteUiState current = state;
        graphics.text(font, "目标玩家：" + (current.playerName().isBlank() ? targetInstanceId : current.playerName()) + "　方块快照：" + blocks.size(), 16, 14, 0xFFFFFFFF);
        graphics.text(font, "这是独立 3D UI，不会切换当前玩家摄像机或区块", 16, 29, 0xFF76E6A5);
        graphics.text(font, "WASD/空格/Shift：移动 UI 相机　中键拖动：旋转　滚轮：缩放　右键：使用　按住左键：挖掘　Esc：返回", 16, height - 25, 0xFFB8D8FF);
        graphics.text(font, "鼠标直接指向方块；中键单击：让目标玩家通过 Baritone 移动到方块上方；右键容器会显示在控制端", 16, 42, 0xFFFFD479);
        graphics.centeredText(font, Component.literal((layerFilter == null ? "当前：显示全部层" : "当前：只显示 Y=" + layerFilter + " 层") + "　显示半径=" + previewRadius), width / 2, 76, 0xFFFFFF80);
        String label = selectedLabel(selected); int cx = (viewport.left + viewport.right) / 2;
        int labelWidth = Math.min(viewport.right - viewport.left - 16, font.width(label) + 16);
        graphics.fill(cx - labelWidth / 2, viewport.bottom - 26, cx + labelWidth / 2, viewport.bottom - 7, 0xC0101010);
        graphics.centeredText(font, Component.literal(label), cx, viewport.bottom - 21, selected == null ? 0xFFFFAA55 : 0xFFFFFFFF);
        graphics.outline(viewport.left, viewport.top, viewport.right - viewport.left, viewport.bottom - viewport.top, 0xFF65758A);
    }

    private void renderVoxelScene(GuiGraphicsExtractor graphics, Viewport viewport) {
        Minecraft client = Minecraft.getInstance(); if (client.level == null) return;
        if (sceneLevel != client.level) { sceneLevel = client.level; sceneEntity = null; sceneRenderState = null; sceneDirty = true; }
        if (sceneEntity == null) sceneEntity = new VoxelPreviewEntity(VoxelPreviewTypes.TYPE, client.level);
        if (sceneDirty || sceneRenderState == null) rebuildVoxelScene(client);
        if (sceneRenderState == null) return;
        Quaternionf rotation = sceneRotation(); Vector3f translation = sceneTranslation(rotation); float scale = sceneScale(viewport);
        graphics.entity(sceneRenderState, scale, translation, rotation, null, viewport.left, viewport.top, viewport.right, viewport.bottom);
    }

    private Quaternionf sceneRotation() {
        return new Quaternionf()
                .rotateZ((float)Math.PI)
                .rotateX((float)Math.toRadians(cameraPitch))
                .rotateY((float)Math.toRadians(cameraYaw));
    }
    private float sceneScale(Viewport viewport) { return (float)(Math.min(viewport.width(), viewport.height()) / Math.max(6.0, previewRadius * 2.25) * zoom); }
    private Vector3f sceneTranslation(Quaternionf rotation) {
        RemoteUiState current = state;
        return rotation.transform(new Vector3f((float)(current.x() - cameraX), (float)(current.y() + current.eyeHeight() - cameraY), (float)(current.z() - cameraZ)));
    }

    private synchronized void rebuildVoxelScene(Minecraft client) {
        RemoteUiState current = state; double originY = current.y() + current.eyeHeight(); List<VoxelPreviewEntity.Voxel> voxels = new ArrayList<>();
        for (RemotePreviewBlock block : blocks.values()) {
            if (layerFilter != null && block.y() != layerFilter) continue;
            if (layerFilter == null) {
                BlockPos pos = new BlockPos(block.x(), block.y(), block.z()); boolean exposed = false;
                for (Direction direction : FACES) { RemotePreviewBlock neighbor = blocks.get(pos.relative(direction)); if (neighbor == null || !neighbor.occluding()) { exposed = true; break; } }
                if (!exposed) continue;
            }
            BlockState blockState = resolveBlockState(block); if (blockState == null || blockState.isAir()) continue;
            voxels.add(new VoxelPreviewEntity.Voxel(blockState, (float)(block.x() - current.x()), (float)(block.y() - originY), (float)(block.z() - current.z())));
        }
        sceneEntity.setVoxels(voxels); sceneRenderState = client.getEntityRenderDispatcher().extractEntity(sceneEntity, 1.0f); sceneDirty = false;
    }

    private static BlockState resolveBlockState(RemotePreviewBlock remote) {
        Identifier identifier = Identifier.tryParse(remote.block()); if (identifier == null) return null;
        var block = BuiltInRegistries.BLOCK.getValue(identifier); if (block == null) return null;
        BlockState state = block.defaultBlockState();
        for (Map.Entry<String, String> entry : remote.properties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey()); if (property != null) state = applyProperty(state, property, entry.getValue());
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
    }

    private synchronized List<Face> collectFaces(Viewport viewport, BlockPos selected) {
        List<Face> result = new ArrayList<>();
        for (Map.Entry<BlockPos, RemotePreviewBlock> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey(); RemotePreviewBlock block = entry.getValue();
            if (selected == null || !pos.equals(selected) || (layerFilter != null && pos.getY() != layerFilter)) continue;
            for (Direction direction : FACES) {
                RemotePreviewBlock neighbor = blocks.get(pos.relative(direction));
                if (neighbor != null && neighbor.occluding() && (layerFilter == null || pos.relative(direction).getY() == layerFilter)) continue;
                Vec3 normal = Vec3.atLowerCornerOf(direction.getUnitVec3i());
                Vec3 center = Vec3.atLowerCornerOf(pos).add(0.5, 0.5, 0.5).add(normal.scale(0.5));
                if (normal.dot(new Vec3(cameraX - center.x, cameraY - center.y, cameraZ - center.z)) <= 0.0) continue;
                Vec3[] vertices = faceVertices(pos, direction); ScreenPoint[] projected = new ScreenPoint[4]; double depth = 0.0; boolean visible = true;
                for (int index = 0; index < 4; index++) {
                    projected[index] = project(vertices[index], viewport); if (projected[index] == null) { visible = false; break; } depth += projected[index].depth;
                }
                if (!visible) continue;
                int color = shade(block.color(), direction, pos.equals(selected));
                result.add(new Face(projected, depth / 4.0, color, pos.equals(selected)));
            }
        }
        return result;
    }

    private ScreenPoint project(Vec3 point, Viewport viewport) {
        RemoteUiState current = state; Quaternionf rotation = sceneRotation(); Vector3f translation = sceneTranslation(rotation);
        Vector3f transformed = rotation.transform(new Vector3f((float)(point.x - current.x()), (float)(point.y - current.y() - current.eyeHeight()), (float)(point.z - current.z()))).add(translation);
        double scale = sceneScale(viewport);
        return new ScreenPoint((viewport.left + viewport.right) * 0.5 + transformed.x * scale, (viewport.top + viewport.bottom) * 0.5 + transformed.y * scale, transformed.z);
    }

    private BlockHitResult raycast(double mouseX, double mouseY) {
        Vec3 origin = rayOrigin(mouseX, mouseY, viewport()), direction = viewRay(mouseX, mouseY, viewport());
        double nearest = Double.POSITIVE_INFINITY; RemotePreviewBlock nearestBlock = null; Direction nearestFace = Direction.UP;
        synchronized (this) {
            for (RemotePreviewBlock block : blocks.values()) {
                if (layerFilter != null && block.y() != layerFilter) continue;
                double tx1 = axisNear(block.x(), block.x() + 1.0, origin.x, direction.x);
                double tx2 = axisFar(block.x(), block.x() + 1.0, origin.x, direction.x);
                double ty1 = axisNear(block.y(), block.y() + 1.0, origin.y, direction.y);
                double ty2 = axisFar(block.y(), block.y() + 1.0, origin.y, direction.y);
                double tz1 = axisNear(block.z(), block.z() + 1.0, origin.z, direction.z);
                double tz2 = axisFar(block.z(), block.z() + 1.0, origin.z, direction.z);
                double enter = Math.max(tx1, Math.max(ty1, tz1)), leave = Math.min(tx2, Math.min(ty2, tz2));
                if (leave < Math.max(0.0, enter) || enter < 0.0 || enter >= nearest) continue;
                nearest = enter; nearestBlock = block;
                if (enter == tx1) nearestFace = direction.x > 0 ? Direction.WEST : Direction.EAST;
                else if (enter == ty1) nearestFace = direction.y > 0 ? Direction.DOWN : Direction.UP;
                else nearestFace = direction.z > 0 ? Direction.NORTH : Direction.SOUTH;
            }
        }
        if (nearestBlock == null) return null;
        Vec3 hit = origin.add(direction.scale(nearest));
        return new BlockHitResult(hit, nearestFace, new BlockPos(nearestBlock.x(), nearestBlock.y(), nearestBlock.z()), false);
    }

    private static double axisNear(double min, double max, double origin, double direction) {
        if (Math.abs(direction) < 1.0E-8) return origin >= min && origin <= max ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        return Math.min((min - origin) / direction, (max - origin) / direction);
    }

    private static double axisFar(double min, double max, double origin, double direction) {
        if (Math.abs(direction) < 1.0E-8) return origin >= min && origin <= max ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        return Math.max((min - origin) / direction, (max - origin) / direction);
    }

    private Vec3 viewRay(double mouseX, double mouseY, Viewport viewport) {
        Vector3f forward = sceneRotation().conjugate(new Quaternionf()).transform(new Vector3f(0.0f, 0.0f, 1.0f)).normalize();
        return new Vec3(forward.x, forward.y, forward.z);
    }

    private Vec3 rayOrigin(double mouseX, double mouseY, Viewport viewport) {
        float horizontal = (float)((mouseX - (viewport.left + viewport.right) * 0.5) / sceneScale(viewport));
        float vertical = (float)((mouseY - (viewport.top + viewport.bottom) * 0.5) / sceneScale(viewport));
        Vector3f offset = sceneRotation().conjugate(new Quaternionf()).transform(new Vector3f(horizontal, vertical, 0.0f));
        return new Vec3(cameraX + offset.x, cameraY + offset.y, cameraZ + offset.z);
    }

    private String selectedLabel(BlockHitResult hit) {
        if (hit == null) return "鼠标未指向目标玩家可交互范围内的方块";
        RemotePreviewBlock block;
        synchronized (this) { block = blocks.get(hit.getBlockPos()); }
        if (block == null) return "方块快照正在刷新";
        BlockPos pos = hit.getBlockPos(); return block.block() + "  (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")  面=" + hit.getDirection().getName();
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!viewport().contains(event.x(), event.y())) return super.mouseClicked(event, doubleClick);
        pointerX = event.x(); pointerY = event.y(); BlockHitResult hit = raycast(pointerX, pointerY);
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) { bridge.previewUseBlock(hit, actionYaw(hit), actionPitch(hit)); return true; }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) { mining = hit != null; bridge.previewStartMining(hit, actionYaw(hit), actionPitch(hit)); return true; }
        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) { middlePressed = true; middleDragged = false; middleDownX = event.x(); middleDownY = event.y(); return true; }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        pointerX = event.x(); pointerY = event.y();
        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
            if (Math.abs(event.x() - middleDownX) > 2.0 || Math.abs(event.y() - middleDownY) > 2.0) middleDragged = true;
            if (middleDragged) { cameraYaw += (float)dragX * 0.28f; cameraPitch = Math.max(-89.0f, Math.min(89.0f, cameraPitch - (float)dragY * 0.28f)); }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override public void mouseMoved(double mouseX, double mouseY) { pointerX = mouseX; pointerY = mouseY; super.mouseMoved(mouseX, mouseY); }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) { mining = false; bridge.previewStopMining(); return true; }
        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
            pointerX = event.x(); pointerY = event.y();
            if (middlePressed && !middleDragged && viewport().contains(pointerX, pointerY)) bridge.previewGotoAbove(raycast(pointerX, pointerY));
            middlePressed = false; middleDragged = false; return true;
        }
        return super.mouseReleased(event);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (viewport().contains(mouseX, mouseY)) { zoom = Math.max(0.35, Math.min(3.0, zoom * Math.pow(1.12, vertical))); return true; }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) { mining = false; bridge.closeBlockPreviewAndReturn(); return true; }
        if (radiusBox != null && radiusBox.isFocused()) {
            if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) { applyRadius(); radiusBox.setFocused(false); return true; }
            return super.keyPressed(event);
        }
        setMovement(event.key(), true); return true;
    }

    @Override public boolean keyReleased(KeyEvent event) { if (radiusBox != null && radiusBox.isFocused()) return super.keyReleased(event); setMovement(event.key(), false); return true; }

    private void setMovement(int key, boolean pressed) {
        if (key == InputConstants.KEY_W) forward = pressed;
        else if (key == InputConstants.KEY_S) back = pressed;
        else if (key == InputConstants.KEY_A) left = pressed;
        else if (key == InputConstants.KEY_D) right = pressed;
        else if (key == InputConstants.KEY_SPACE) up = pressed;
        else if (key == InputConstants.KEY_LSHIFT || key == InputConstants.KEY_RSHIFT) down = pressed;
    }

    @Override public void tick() {
        double yaw = Math.toRadians(cameraYaw), speed = 0.16;
        double moveForward = (forward ? 1.0 : 0.0) - (back ? 1.0 : 0.0), moveRight = (right ? 1.0 : 0.0) - (left ? 1.0 : 0.0);
        cameraX += (-Math.sin(yaw) * moveForward + Math.cos(yaw) * moveRight) * speed;
        cameraZ += (Math.cos(yaw) * moveForward + Math.sin(yaw) * moveRight) * speed;
        cameraY += ((up ? 1.0 : 0.0) - (down ? 1.0 : 0.0)) * speed;
        RemoteUiState current = state; Vec3 targetEye = new Vec3(current.x(), current.y() + current.eyeHeight(), current.z()); Vec3 camera = new Vec3(cameraX, cameraY, cameraZ);
        Vec3 offset = camera.subtract(targetEye); if (offset.lengthSqr() > 144.0) { Vec3 clamped = targetEye.add(offset.normalize().scale(12.0)); cameraX = clamped.x; cameraY = clamped.y; cameraZ = clamped.z; }
        if (mining) {
            BlockHitResult hit = viewport().contains(pointerX, pointerY) ? raycast(pointerX, pointerY) : null;
            if (hit == null) { mining = false; bridge.previewStopMining(); }
            else bridge.previewContinueMining(hit, actionYaw(hit), actionPitch(hit));
        }
    }

    private float actionYaw(BlockHitResult hit) {
        if (hit == null) return state.yaw(); Vec3 eye = new Vec3(state.x(), state.y() + state.eyeHeight(), state.z()); Vec3 delta = hit.getLocation().subtract(eye);
        return (float)Math.toDegrees(Math.atan2(-delta.x, delta.z));
    }

    private float actionPitch(BlockHitResult hit) {
        if (hit == null) return state.pitch(); Vec3 eye = new Vec3(state.x(), state.y() + state.eyeHeight(), state.z()); Vec3 delta = hit.getLocation().subtract(eye);
        return (float)-Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)));
    }

    @Override public void onClose() { mining = false; bridge.closeBlockPreviewAndReturn(); }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean isPauseScreen() { return false; }

    private Viewport viewport() { return new Viewport(16, 90, width - 16, height - 40); }

    private static Vec3[] faceVertices(BlockPos pos, Direction face) {
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return switch (face) {
            case DOWN -> new Vec3[] { new Vec3(x, y, z), new Vec3(x + 1, y, z), new Vec3(x + 1, y, z + 1), new Vec3(x, y, z + 1) };
            case UP -> new Vec3[] { new Vec3(x, y + 1, z), new Vec3(x, y + 1, z + 1), new Vec3(x + 1, y + 1, z + 1), new Vec3(x + 1, y + 1, z) };
            case NORTH -> new Vec3[] { new Vec3(x, y, z), new Vec3(x, y + 1, z), new Vec3(x + 1, y + 1, z), new Vec3(x + 1, y, z) };
            case SOUTH -> new Vec3[] { new Vec3(x, y, z + 1), new Vec3(x + 1, y, z + 1), new Vec3(x + 1, y + 1, z + 1), new Vec3(x, y + 1, z + 1) };
            case WEST -> new Vec3[] { new Vec3(x, y, z), new Vec3(x, y, z + 1), new Vec3(x, y + 1, z + 1), new Vec3(x, y + 1, z) };
            case EAST -> new Vec3[] { new Vec3(x + 1, y, z), new Vec3(x + 1, y + 1, z), new Vec3(x + 1, y + 1, z + 1), new Vec3(x + 1, y, z + 1) };
        };
    }

    private void outlineBlock(GuiGraphicsExtractor graphics, BlockPos pos, Viewport viewport, int color) {
        double x = pos.getX(), y = pos.getY(), z = pos.getZ();
        Vec3[] corners = { new Vec3(x, y, z), new Vec3(x + 1, y, z), new Vec3(x, y + 1, z), new Vec3(x + 1, y + 1, z), new Vec3(x, y, z + 1), new Vec3(x + 1, y, z + 1), new Vec3(x, y + 1, z + 1), new Vec3(x + 1, y + 1, z + 1) };
        ScreenPoint[] projected = new ScreenPoint[corners.length]; for (int index = 0; index < corners.length; index++) projected[index] = project(corners[index], viewport);
        for (int[] edge : BOX_EDGES) drawLine(graphics, projected[edge[0]], projected[edge[1]], color);
    }

    private static int shade(int color, Direction direction, boolean selected) {
        double factor = switch (direction) { case UP -> 1.0; case DOWN -> 0.45; case NORTH, SOUTH -> 0.72; case EAST, WEST -> 0.60; };
        if (selected) factor = Math.min(1.4, factor * 1.35);
        int r = Math.min(255, (int)(((color >> 16) & 255) * factor)); int g = Math.min(255, (int)(((color >> 8) & 255) * factor)); int b = Math.min(255, (int)((color & 255) * factor));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static void fillPolygon(GuiGraphicsExtractor graphics, ScreenPoint[] points, int color) {
        int minY = Math.max(0, (int)Math.ceil(Math.min(Math.min(points[0].y, points[1].y), Math.min(points[2].y, points[3].y))));
        int maxY = Math.min(graphics.guiHeight() - 1, (int)Math.floor(Math.max(Math.max(points[0].y, points[1].y), Math.max(points[2].y, points[3].y))));
        for (int y = minY; y <= maxY; y++) {
            double scan = y + 0.5; double[] intersections = new double[4]; int count = 0;
            for (int edge = 0; edge < 4; edge++) {
                ScreenPoint a = points[edge], b = points[(edge + 1) & 3];
                if ((a.y <= scan && b.y > scan) || (b.y <= scan && a.y > scan)) intersections[count++] = a.x + (scan - a.y) * (b.x - a.x) / (b.y - a.y);
            }
            if (count >= 2) { java.util.Arrays.sort(intersections, 0, count); graphics.fill((int)Math.floor(intersections[0]), y, (int)Math.ceil(intersections[count - 1]), y + 1, color); }
        }
    }

    private static void outlinePolygon(GuiGraphicsExtractor graphics, ScreenPoint[] points, int color) {
        for (int edge = 0; edge < 4; edge++) drawLine(graphics, points[edge], points[(edge + 1) & 3], color);
    }

    private static void drawLine(GuiGraphicsExtractor graphics, ScreenPoint a, ScreenPoint b, int color) {
        int x0 = (int)Math.max(-1, Math.min(graphics.guiWidth(), Math.round(a.x))), y0 = (int)Math.max(-1, Math.min(graphics.guiHeight(), Math.round(a.y)));
        int x1 = (int)Math.max(-1, Math.min(graphics.guiWidth(), Math.round(b.x))), y1 = (int)Math.max(-1, Math.min(graphics.guiHeight(), Math.round(b.y)));
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1, dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1, error = dx + dy;
        while (true) { graphics.fill(x0, y0, x0 + 1, y0 + 1, color); if (x0 == x1 && y0 == y1) break; int e2 = error * 2; if (e2 >= dy) { error += dy; x0 += sx; } if (e2 <= dx) { error += dx; y0 += sy; } }
    }

    private record ScreenPoint(double x, double y, double depth) { }
    private record Face(ScreenPoint[] points, double depth, int color, boolean selected) { }
    private record Viewport(int left, int top, int right, int bottom) {
        int width() { return right - left; } int height() { return bottom - top; }
        boolean contains(double x, double y) { return x >= left && x < right && y >= top && y < bottom; }
    }
}

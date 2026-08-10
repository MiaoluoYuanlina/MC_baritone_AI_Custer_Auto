package com.baritonecluster.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import com.mojang.blaze3d.platform.NativeImage;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.baritonecluster.bridge.mixin.MinecraftAttackInvoker;
import com.baritonecluster.bridge.mixin.SpriteContentsAccessor;

/** Client-only bridge. It intentionally uses chat input so Baritone remains an optional runtime mod. */
public final class ClusterBridgeClient implements ClientModInitializer {
    private static volatile ClusterBridgeClient ACTIVE;
    private static final String HOST = System.getProperty("baritone.cluster.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("baritone.cluster.port", 25570);
    private static final String TOKEN = System.getProperty("baritone.cluster.token", "change-me");
    private static final String INSTANCE = System.getProperty("baritone.cluster.instance", UUID.randomUUID().toString());
    private static final int[] REMOTE_CHUNK_DX = { 0, -1, 0, 1, 1, 1, 0, -1, -1, -2, -1, 0, 1, 2, 2, 2, 2, 2, 1, 0, -1, -2, -2, -2, -2 };
    private static final int[] REMOTE_CHUNK_DZ = { 0, -1, -1, -1, 0, 1, 1, 1, 0, -2, -2, -2, -2, -2, -1, 0, 1, 2, 2, 2, 2, 2, 1, 0, -1 };
    private final ExecutorService network = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "baritone-cluster-bridge"); t.setDaemon(true); return t; });
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "baritone-cluster-sender"); t.setDaemon(true); return t; });
    private final ExecutorService realtimeSender = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "baritone-cluster-realtime"); t.setDaemon(true); return t; });
    private final ExecutorService criticalSender = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "baritone-cluster-critical"); t.setDaemon(true); return t; });
    private final ExecutorService chunkCodec = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "baritone-cluster-chunk-codec"); t.setDaemon(true); return t; });
    private final AtomicReference<String> pendingRealtimeMessage = new AtomicReference<>();
    private final AtomicBoolean realtimeDrainScheduled = new AtomicBoolean();
    private volatile BufferedWriter out;
    private int ticks;
    private JsonArray cachedFunctionalBlocks = new JsonArray();
    private JsonArray cachedFunctionalBlockContexts = new JsonArray();
    private final ArrayDeque<JsonObject> recentBlockChanges = new ArrayDeque<>();
    private Map<ObservedBlockPos, String> previousScannedBlocks = Map.of();
    private long blockChangeSequence;
    private long lastStatusErrorAt;
    private long lastBaritoneStatusErrorAt;
    private long lastBaritonePathErrorAt;
    private boolean previousBaritoneWorking;
    private long baritoneWorkSequence;
    private long baritoneWorkFinishedAt;
    private KeyMapping possessionKey;
    private volatile boolean primaryInstance;
    private volatile boolean possessionTarget;
    private volatile String selectedPossessionTarget;
    private volatile String pendingPossessionTarget;
    private volatile List<RemoteInstance> clusterInstances = List.of();
    private volatile List<RemoteClusterPlayer> clusterPlayers = List.of();
    private volatile List<RemoteBaritoneRoute> clusterRoutes = List.of();
    private volatile ClusterDisplaySettings clusterDisplaySettings = new ClusterDisplaySettings(true, false, false, false, true);
    private volatile ClusterTaskProgress clusterTaskProgress;
    private volatile BlockMiningRules blockMiningRules = new BlockMiningRules(List.of(), List.of(
            "minecraft:crafting_table", "minecraft:furnace", "minecraft:chest", "minecraft:trapped_chest"));
    private volatile RemoteInput remoteInput;
    private volatile long remoteInputReceivedAt;
    private boolean previousInventoryKey;
    private boolean previousSwapKey;
    private boolean previousDropKey;
    private boolean previousPrimaryLeftMouse;
    private boolean previousPrimaryRightMouse;
    private boolean suppressPrimaryUseUntilReleased;
    private ClientInput previousTargetInput;
    private RemoteClientInput injectedTargetInput;
    private LocalPlayer injectedTargetPlayer;
    private boolean previousRemoteLeftMouse;
    private boolean previousRemoteRightMouse;
    private boolean suppressRemoteUseUntilReleased;
    private volatile RemoteUiState remoteUiState;
    private volatile RemoteUiState inspectionUiState;
    private volatile String inspectionTargetId;
    private RemoteBlockPreviewScreen suspendedBlockPreviewScreen;
    private boolean inspectionReturningToPreview;
    private String inspectionRequesterId;
    private String inspectionOperation = "";
    private long inspectionExpiresAt;
    private long inspectionSequence;
    private JsonArray cachedPreviewBlocks = new JsonArray();
    private BlockPos lastPreviewScanCenter;
    private int inspectionPreviewRadius = 8;
    private PossessionCameraEntity remoteCamera;
    private boolean remoteCameraPositionInitialized;
    private String initializedViewTarget;
    private long remoteStateSequence;
    private long remoteStateReceivedAt;
    private long remoteChunkSequence;
    private int remoteChunkCursor;
    private int mapChunkCursor;
    private long mapTileSequence;
    private final Map<String, JsonObject> mapMaterialCache = new HashMap<>();
    private final Map<String, JsonObject> playerAvatarCache = new HashMap<>();
    private final java.util.Set<String> sentMapMaterials = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private int lastRemoteChunkCenterX = Integer.MIN_VALUE;
    private int lastRemoteChunkCenterZ = Integer.MIN_VALUE;
    private int appliedRemoteChunkCenterX = Integer.MIN_VALUE;
    private int appliedRemoteChunkCenterZ = Integer.MIN_VALUE;
    private volatile long lastChunkSentAt;
    private volatile long lastChunkReceivedAt;
    private volatile String remoteChunkError = "";
    private final Map<Long, ClientboundLevelChunkWithLightPacket> savedLocalChunks = new HashMap<>();
    private final Map<UUID, RemotePlayer> mirroredNearbyPlayers = new HashMap<>();
    private final Map<UUID, Entity> mirroredNearbyEntities = new HashMap<>();
    private int nextMirroredEntityId = -2_000_000;
    private int savedLocalCenterX;
    private int savedLocalCenterZ;
    private float remoteViewYaw;
    private float remoteViewPitch;
    private boolean inspectionPreviewActive;
    private BlockPos previewMiningBlock;
    private Direction previewMiningFace;
    private int remoteHotbarSlot;
    private long possessionSelectedAt;
    private long lastAutoRespawnAt;
    private PendingCraft pendingCraft;
    private String pendingUseItem;
    private int pendingUseAtTick;
    private PendingPlace pendingPlace;
    private static final EquipmentSlot[] PLAYER_EQUIPMENT = { EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD };

    @Override public void onInitializeClient() {
        ACTIVE = this;
        VoxelPreviewTypes.registerRenderer();
        ClusterOverlayRenderer.register(this);
        ClusterTaskHud.register(this);
        possessionKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.baritone_ai_cluster_auto.possession", InputConstants.KEY_F8, KeyMapping.Category.MULTIPLAYER));
        network.execute(this::connectLoop);
        ClientTickEvents.START_CLIENT_TICK.register(this::onStartTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
    }
    private void connectLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try (Socket socket = new Socket(HOST, PORT);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                out = writer; sentMapMaterials.clear(); write(hello());
                for (String line; (line = in.readLine()) != null;) handleCommand(line);
            } catch (IOException ignored) { try { Thread.sleep(3000); } catch (InterruptedException e) { return; } }
            finally { out = null; }
        }
    }
    private String hello() { JsonObject o = new JsonObject(); o.addProperty("type", "hello"); o.addProperty("instanceId", INSTANCE); o.addProperty("token", TOKEN); return o.toString(); }
    private void handleCommand(String line) {
        try { JsonObject command = com.google.gson.JsonParser.parseString(line).getAsJsonObject(); String type = command.get("type").getAsString();
            if ("command".equals(type)) { String text = command.get("command").getAsString(); Minecraft.getInstance().execute(() -> { LocalPlayer p = Minecraft.getInstance().player; if (p != null) { if (text.startsWith("/")) p.connection.sendCommand(text.substring(1)); else p.connection.sendChat(text); } }); }
            if ("action".equals(type)) Minecraft.getInstance().execute(() -> performAction(command));
            if ("cluster_state".equals(type)) updateClusterState(command);
            if ("remote_control".equals(type) && command.has("input")) updateRemoteInput(command.getAsJsonObject("input"));
            if ("remote_state".equals(type) && command.has("state")) updateRemoteState(command.get("sourceInstanceId").getAsString(), command.getAsJsonObject("state"));
            if ("remote_chunk".equals(type) && command.has("chunk")) updateRemoteChunk(command.get("sourceInstanceId").getAsString(), command.getAsJsonObject("chunk"));
            if ("remote_ui_click".equals(type)) {
                JsonObject click = command.has("click") ? command.getAsJsonObject("click") : command;
                if (command.has("sourceInstanceId")) click.addProperty("sourceInstanceId", command.get("sourceInstanceId").getAsString());
                Minecraft.getInstance().execute(() -> applyRemoteUiClick(click));
            }
            if ("remote_inspect".equals(type)) Minecraft.getInstance().execute(() -> handleRemoteInspect(command));
            if ("remote_inspect_state".equals(type) && command.has("state")) updateRemoteInspectionState(command.get("sourceInstanceId").getAsString(), stringValue(command, "operation"), command.getAsJsonObject("state"));
            if ("remote_world_action".equals(type)) {
                JsonObject action = command.has("action") ? command.getAsJsonObject("action") : command;
                if (command.has("sourceInstanceId")) action.addProperty("sourceInstanceId", command.get("sourceInstanceId").getAsString());
                Minecraft.getInstance().execute(() -> applyRemoteWorldAction(action));
            }
            if ("possession_select_ack".equals(type)) handlePossessionSelectAck(command);
            if ("cluster_user_command_ack".equals(type)) handleClusterUserCommandAck(command);
            if ("ai_chat_message".equals(type)) handleAiChatMessage(command);
        } catch (Exception ignored) { }
    }
    private void updateClusterState(JsonObject state) {
        long clusterStateVersion = longValue(state, "clusterStateVersion", 0);
        primaryInstance = state.has("isPrimary") && state.get("isPrimary").getAsBoolean();
        boolean wasPossessionTarget = possessionTarget;
        possessionTarget = state.has("isPossessionTarget") && state.get("isPossessionTarget").getAsBoolean();
        String confirmedTarget = state.has("possessionTargetId") && !state.get("possessionTargetId").isJsonNull() ? state.get("possessionTargetId").getAsString() : null;
        if (!java.util.Objects.equals(selectedPossessionTarget, confirmedTarget)) {
            selectedPossessionTarget = confirmedTarget;
            possessionSelectedAt = System.currentTimeMillis();
            remoteUiState = null; remoteStateReceivedAt = 0; initializedViewTarget = null;
            remoteChunkCursor = 0; lastRemoteChunkCenterX = Integer.MIN_VALUE; lastRemoteChunkCenterZ = Integer.MIN_VALUE;
            previousPrimaryLeftMouse = false; previousPrimaryRightMouse = false;
        }
        pendingPossessionTarget = null;
        List<RemoteInstance> updated = new ArrayList<>();
        if (state.has("instances")) for (var element : state.getAsJsonArray("instances")) { JsonObject item = element.getAsJsonObject(); updated.add(new RemoteInstance(item.get("instanceId").getAsString(), item.has("playerName") ? item.get("playerName").getAsString() : "", item.has("isPrimary") && item.get("isPrimary").getAsBoolean(), item.has("isPossessionTarget") && item.get("isPossessionTarget").getAsBoolean())); }
        clusterInstances = List.copyOf(updated);
        List<RemoteClusterPlayer> updatedPlayers = new ArrayList<>();
        if (state.has("players")) for (var element : state.getAsJsonArray("players")) {
            JsonObject item = element.getAsJsonObject();
            updatedPlayers.add(new RemoteClusterPlayer(stringValue(item, "uuid"), stringValue(item, "name"), stringValue(item, "dimension"), bool(item, "positionAvailable"),
                    doubleValue(item, "x", 0), doubleValue(item, "y", 0), doubleValue(item, "z", 0), bool(item, "isControllable"), stringValue(item, "instanceId")));
        }
        clusterPlayers = List.copyOf(updatedPlayers);
        List<RemoteBaritoneRoute> updatedRoutes = new ArrayList<>();
        if (state.has("routes")) for (var element : state.getAsJsonArray("routes")) {
            JsonObject item = element.getAsJsonObject(); List<RemotePathPoint> points = new ArrayList<>();
            if (item.has("points")) for (var pointElement : item.getAsJsonArray("points")) {
                JsonObject point = pointElement.getAsJsonObject(); points.add(new RemotePathPoint(intValue(point, "x", 0), intValue(point, "y", 0), intValue(point, "z", 0)));
                if (points.size() >= 256) break;
            }
            updatedRoutes.add(new RemoteBaritoneRoute(stringValue(item, "instanceId"), stringValue(item, "playerName"), stringValue(item, "dimension"), List.copyOf(points)));
        }
        clusterRoutes = List.copyOf(updatedRoutes);
        clusterDisplaySettings = new ClusterDisplaySettings(
                !state.has("allowBaritoneBreak") || bool(state, "allowBaritoneBreak"),
                bool(state, "showControllablePlayerBoxes"), bool(state, "showBaritoneRoutes"), bool(state, "showAiRepliesInChat"),
                !state.has("showTaskProgress") || bool(state, "showTaskProgress"));
        clusterTaskProgress = parseTaskProgress(state);
        blockMiningRules = new BlockMiningRules(stringArray(state, "blocksToDisallowBreaking"), stringArray(state, "blocksToAvoidBreaking"));
        if (wasPossessionTarget && !possessionTarget) Minecraft.getInstance().execute(() -> releaseRemoteKeys(Minecraft.getInstance()));
        JsonObject acknowledgement = new JsonObject();
        acknowledgement.addProperty("type", "cluster_state_ack");
        acknowledgement.addProperty("localInstanceId", INSTANCE);
        acknowledgement.addProperty("clusterStateVersion", clusterStateVersion);
        acknowledgement.addProperty("isPrimary", primaryInstance);
        acknowledgement.addProperty("isPossessionTarget", possessionTarget);
        sendObject(acknowledgement);
    }
    private ClusterTaskProgress parseTaskProgress(JsonObject state) {
        if (!state.has("taskProgress") || state.get("taskProgress").isJsonNull() || !state.get("taskProgress").isJsonObject()) return null;
        JsonObject task = state.getAsJsonObject("taskProgress");
        List<String> steps = stringArray(task, "steps");
        List<ClusterTaskProgress.TaskInstanceProgress> instances = new ArrayList<>();
        if (task.has("instances") && task.get("instances").isJsonArray()) for (var element : task.getAsJsonArray("instances")) {
            JsonObject item = element.getAsJsonObject();
            instances.add(new ClusterTaskProgress.TaskInstanceProgress(stringValue(item, "instanceId"), stringValue(item, "playerName"),
                    stringValue(item, "stage"), stringValue(item, "stageLabel"), intValue(item, "currentStep", 0),
                    intValue(item, "currentItemCount", 0), intValue(item, "requiredCount", 0), bool(item, "baritoneWorking")));
        }
        return new ClusterTaskProgress(bool(task, "active"), bool(task, "complete"), stringValue(task, "title"),
                intValue(task, "currentStep", 0), intValue(task, "totalSteps", steps.size()), steps, List.copyOf(instances));
    }
    private void handlePossessionSelectAck(JsonObject acknowledgement) {
        boolean accepted = bool(acknowledgement, "accepted");
        String target = acknowledgement.has("targetInstanceId") && !acknowledgement.get("targetInstanceId").isJsonNull() ? acknowledgement.get("targetInstanceId").getAsString() : null;
        if (!accepted) pendingPossessionTarget = null;
        Minecraft.getInstance().execute(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return;
            String message = accepted ? (target == null ? "C# 已确认结束附身。" : "C# 已确认附身目标，正在建立远程画面。") : "C# 拒绝了附身请求：" + (acknowledgement.has("error") ? acknowledgement.get("error").getAsString() : "未知原因");
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        });
    }
    private void handleClusterUserCommandAck(JsonObject acknowledgement) {
        Minecraft.getInstance().execute(() -> {
            LocalPlayer player = Minecraft.getInstance().player; if (player == null) return;
            boolean accepted = bool(acknowledgement, "accepted");
            String operation = stringValue(acknowledgement, "operation");
            String success = switch (operation) {
                case "follow_player" -> "C# 已下发跟随命令：" + stringValue(acknowledgement, "playerName");
                case "stop_baritone" -> "C# 已停止指定实例的 Baritone 动作。";
                case "set_cluster_settings" -> "集群设置已同步。";
                case "set_block_mining_rules" -> "方块挖掘规则已同步到全部实例。";
                default -> "C# 已执行实例操作。";
            };
            String message = accepted ? success : "C# 拒绝操作：" + stringValue(acknowledgement, "error");
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        });
    }
    private void handleAiChatMessage(JsonObject message) {
        if (!primaryInstance || !clusterDisplaySettings.showAiRepliesInChat()) return;
        String text = stringValue(message, "message");
        if (text.isBlank()) return;
        Minecraft.getInstance().execute(() -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[AI] " + text));
        });
    }
    private void updateRemoteInput(JsonObject input) {
        remoteInput = new RemoteInput(
                longValue(input, "sequence", 0), bool(input, "forward"), bool(input, "back"), bool(input, "left"), bool(input, "right"),
                bool(input, "jump"), bool(input, "sneak"), bool(input, "sprint"), bool(input, "attack"), bool(input, "use"), bool(input, "pick"),
                bool(input, "inventoryPulse"), bool(input, "swapPulse"), bool(input, "dropPulse"), intValue(input, "hotbarSlot", -1),
                floatValue(input, "yaw", 0), floatValue(input, "pitch", 0),
                floatValue(input, "mouseX", 0.5f), floatValue(input, "mouseY", 0.5f), bool(input, "leftClickPulse"), bool(input, "rightClickPulse"));
        remoteInputReceivedAt = System.currentTimeMillis();
    }
    private void onStartTick(Minecraft client) {
        if (primaryInstance && selectedPossessionTarget != null) captureAndRelayInput(client); else { previousInventoryKey = false; previousSwapKey = false; previousDropKey = false; }
        if (possessionTarget) applyRemoteInput(client); else if (remoteInput != null) releaseRemoteKeys(client);
    }
    private void onEndTick(Minecraft client) {
        if (possessionKey.consumeClick()) openPossessionScreen(client);
        ticks++;
        processPendingCraft(client);
        processPendingUse(client);
        processPendingPlace(client);
        autoRespawnPossessionTarget(client);
        maintainPrimaryPossessionView(client);
        if (possessionTarget) sendRemoteState(client);
        if (possessionTarget && ticks % 5 == 0) sendNextRemoteChunk(client);
        if (ticks % 5 == 0) sendNextMapTile(client);
        boolean inspectionContainerOpened = inspectionRequesterId != null && inspectionOperation.equals("block_preview") && client.screen instanceof AbstractContainerScreen<?>;
        if (inspectionContainerOpened) { inspectionOperation = "container"; sendRemoteInspectionState(client); }
        else if (inspectionRequesterId != null && ticks % (inspectionOperation.equals("block_preview") ? 10 : 4) == 0) sendRemoteInspectionState(client);
        if (ticks % 20 == 0) sendStatus(client);
    }
    private void autoRespawnPossessionTarget(Minecraft client) {
        LocalPlayer player = client.player;
        if (!possessionTarget || player == null || !player.isDeadOrDying()) return;
        long now = System.currentTimeMillis(); if (now - lastAutoRespawnAt < 1000) return;
        lastAutoRespawnAt = now; player.respawn();
        if (client.screen instanceof DeathScreen) client.setScreen(null);
    }
    private void openPossessionScreen(Minecraft client) {
        LocalPlayer player = client.player;
        if (!primaryInstance) { if (player != null) player.sendSystemMessage(net.minecraft.network.chat.Component.literal("只有在 C# 中设置的主要玩家才能使用 F8 附身。")); return; }
        client.setScreen(new PossessionScreen(clusterInstances, INSTANCE, selectedPossessionTarget, this::selectPossessionTarget, this::requestRemoteInventory, this::openNearbyFunctionalBlocks, clusterPlayers, this::requestFollowPlayer, this::requestStopBaritone, this::openClusterSettings));
    }
    private void selectPossessionTarget(String targetInstanceId) {
        pendingPossessionTarget = targetInstanceId;
        possessionSelectedAt = System.currentTimeMillis();
        JsonObject message = new JsonObject(); message.addProperty("type", "possession_select");
        if (targetInstanceId == null) message.add("targetInstanceId", com.google.gson.JsonNull.INSTANCE); else message.addProperty("targetInstanceId", targetInstanceId);
        sendCriticalObject(message);
    }
    private void requestRemoteInventory(String targetInstanceId) {
        requestRemoteInspection(targetInstanceId, "inventory");
    }
    private void openNearbyFunctionalBlocks(String targetInstanceId) {
        requestRemoteInspection(targetInstanceId, "block_preview");
    }
    private void requestFollowPlayer(String targetInstanceId, String playerName) {
        JsonObject request = new JsonObject(); request.addProperty("type", "cluster_user_command"); request.addProperty("operation", "follow_player");
        request.addProperty("targetInstanceId", targetInstanceId); request.addProperty("playerName", playerName); sendCriticalObject(request);
    }
    private void requestStopBaritone(String targetInstanceId) {
        JsonObject request = new JsonObject(); request.addProperty("type", "cluster_user_command"); request.addProperty("operation", "stop_baritone");
        request.addProperty("targetInstanceId", targetInstanceId); sendCriticalObject(request);
    }
    private void openClusterSettings() {
        Minecraft client = Minecraft.getInstance(); var parent = client.screen;
        client.setScreen(new ClusterSettingsScreen(parent, clusterDisplaySettings, this::requestClusterSettings, blockMiningRules, this::requestBlockMiningRules));
    }
    private void requestClusterSettings(ClusterDisplaySettings settings) {
        clusterDisplaySettings = settings;
        JsonObject request = new JsonObject(); request.addProperty("type", "cluster_user_command"); request.addProperty("operation", "set_cluster_settings");
        request.addProperty("allowBaritoneBreak", settings.allowBaritoneBreak());
        request.addProperty("showControllablePlayerBoxes", settings.showControllablePlayerBoxes());
        request.addProperty("showBaritoneRoutes", settings.showBaritoneRoutes());
        request.addProperty("showAiRepliesInChat", settings.showAiRepliesInChat());
        request.addProperty("showTaskProgress", settings.showTaskProgress()); sendCriticalObject(request);
    }
    private void requestBlockMiningRules(BlockMiningRules rules) {
        blockMiningRules = rules;
        JsonObject request = new JsonObject(); request.addProperty("type", "cluster_user_command"); request.addProperty("operation", "set_block_mining_rules");
        JsonArray hard = new JsonArray(); rules.disallowBreaking().forEach(hard::add); request.add("blocksToDisallowBreaking", hard);
        JsonArray soft = new JsonArray(); rules.avoidBreaking().forEach(soft::add); request.add("blocksToAvoidBreaking", soft);
        sendCriticalObject(request);
    }
    private void requestRemoteInspection(String targetInstanceId, String operation) {
        inspectionTargetId = targetInstanceId; inspectionUiState = null;
        if (operation.equals("block_preview")) { suspendedBlockPreviewScreen = null; inspectionReturningToPreview = false; }
        JsonObject request = new JsonObject(); request.addProperty("type", "remote_inspect"); request.addProperty("targetInstanceId", targetInstanceId); request.addProperty("operation", operation); sendCriticalObject(request);
    }
    private void requestOpenFunctionalBlock(String targetInstanceId, RemoteFunctionalBlock block) {
        JsonObject request = new JsonObject(); request.addProperty("type", "remote_ui_click"); request.addProperty("targetInstanceId", targetInstanceId); request.addProperty("operation", "open_block");
        request.addProperty("x", block.x()); request.addProperty("y", block.y()); request.addProperty("z", block.z()); sendCriticalObject(request);
    }
    private void captureAndRelayInput(Minecraft client) {
        LocalPlayer player = client.player; if (player == null || client.getWindow() == null) return;
        boolean inventory = rawKey(client, InputConstants.KEY_E), swap = rawKey(client, InputConstants.KEY_F), drop = rawKey(client, InputConstants.KEY_Q);
        boolean worldControl = client.screen == null;
        boolean physicalRightMouse = client.mouseHandler.isRightPressed();
        if (suppressPrimaryUseUntilReleased && !physicalRightMouse) suppressPrimaryUseUntilReleased = false;
        boolean leftMouse = worldControl && client.mouseHandler.isLeftPressed();
        boolean rightMouse = worldControl && physicalRightMouse && !suppressPrimaryUseUntilReleased;
        boolean suppressInitialClick = System.currentTimeMillis() - possessionSelectedAt < 350;
        float mouseX = (float)Math.max(0, Math.min(1, client.mouseHandler.getScaledXPos(client.getWindow()) / Math.max(1.0, client.getWindow().getGuiScaledWidth())));
        float mouseY = (float)Math.max(0, Math.min(1, client.mouseHandler.getScaledYPos(client.getWindow()) / Math.max(1.0, client.getWindow().getGuiScaledHeight())));
        JsonObject input = new JsonObject(); input.addProperty("type", "control_input"); input.addProperty("sequence", ticks);
        input.addProperty("forward", rawKey(client, InputConstants.KEY_W)); input.addProperty("back", rawKey(client, InputConstants.KEY_S)); input.addProperty("left", rawKey(client, InputConstants.KEY_A)); input.addProperty("right", rawKey(client, InputConstants.KEY_D));
        input.addProperty("jump", rawKey(client, InputConstants.KEY_SPACE)); input.addProperty("sneak", rawKey(client, InputConstants.KEY_LSHIFT)); input.addProperty("sprint", rawKey(client, InputConstants.KEY_LCONTROL));
        input.addProperty("attack", !suppressInitialClick && leftMouse); input.addProperty("use", !suppressInitialClick && rightMouse); input.addProperty("pick", client.mouseHandler.isMiddlePressed());
        input.addProperty("inventoryPulse", inventory && !previousInventoryKey); input.addProperty("swapPulse", swap && !previousSwapKey); input.addProperty("dropPulse", drop && !previousDropKey);
        for (int slot = 0; slot < 9; slot++) if (rawKey(client, InputConstants.KEY_1 + slot)) remoteHotbarSlot = slot;
        input.addProperty("hotbarSlot", remoteHotbarSlot); input.addProperty("yaw", remoteViewYaw); input.addProperty("pitch", remoteViewPitch);
        input.addProperty("mouseX", mouseX); input.addProperty("mouseY", mouseY);
        input.addProperty("leftClickPulse", !suppressInitialClick && leftMouse && !previousPrimaryLeftMouse);
        input.addProperty("rightClickPulse", !suppressInitialClick && rightMouse && !previousPrimaryRightMouse);
        previousInventoryKey = inventory; previousSwapKey = swap; previousDropKey = drop;
        previousPrimaryLeftMouse = leftMouse; previousPrimaryRightMouse = rightMouse;
        sendLatestRealtimeObject(input); suppressPrimaryControls(client);
    }
    private boolean rawKey(Minecraft client, int key) { return InputConstants.isKeyDown(client.getWindow(), key); }
    private void suppressPrimaryControls(Minecraft client) {
        client.options.keyUp.setDown(false); client.options.keyDown.setDown(false); client.options.keyLeft.setDown(false); client.options.keyRight.setDown(false); client.options.keyJump.setDown(false); client.options.keyShift.setDown(false); client.options.keySprint.setDown(false); client.options.keyAttack.setDown(false); client.options.keyUse.setDown(false); client.options.keyPickItem.setDown(false);
        while (client.options.keyAttack.consumeClick()) { } while (client.options.keyUse.consumeClick()) { } while (client.options.keyPickItem.consumeClick()) { }
        while (client.options.keyInventory.consumeClick()) { } while (client.options.keySwapOffhand.consumeClick()) { } while (client.options.keyDrop.consumeClick()) { }
        for (KeyMapping hotbar : client.options.keyHotbarSlots) { hotbar.setDown(false); while (hotbar.consumeClick()) { } }
    }
    private long lastAppliedRemoteSequence = Long.MIN_VALUE;
    private void applyRemoteInput(Minecraft client) {
        RemoteInput input = remoteInput; LocalPlayer player = client.player;
        if (input == null || player == null || System.currentTimeMillis() - remoteInputReceivedAt > 3000) { releaseRemoteKeys(client); return; }
        if (!input.use) suppressRemoteUseUntilReleased = false;
        boolean effectiveUse = input.use && !suppressRemoteUseUntilReleased;
        if (client.screen != null && !(client.screen instanceof AbstractContainerScreen<?>)) { client.setScreen(null); if (client.isWindowActive()) client.mouseHandler.grabMouse(); }
        ensureRemoteInputInjected(player, input);
        client.options.keyUp.setDown(input.forward); client.options.keyDown.setDown(input.back); client.options.keyLeft.setDown(input.left); client.options.keyRight.setDown(input.right); client.options.keyJump.setDown(input.jump); client.options.keyShift.setDown(input.sneak); client.options.keySprint.setDown(input.sprint); client.options.keyAttack.setDown(input.attack); client.options.keyUse.setDown(effectiveUse); client.options.keyPickItem.setDown(input.pick);
        player.setYRot(input.yaw); player.setYHeadRot(input.yaw); player.setXRot(Math.max(-90, Math.min(90, input.pitch)));
        if (input.hotbarSlot >= 0 && input.hotbarSlot < 9) player.getInventory().setSelectedSlot(input.hotbarSlot);
        if (input.sequence != lastAppliedRemoteSequence) {
            lastAppliedRemoteSequence = input.sequence;
            if (input.leftClickPulse && client.screen == null) ((MinecraftAttackInvoker)client).baritoneCluster$startAttack();
            if (input.rightClickPulse && effectiveUse && client.screen == null) ((MinecraftAttackInvoker)client).baritoneCluster$startUseItem();
            if (input.inventoryPulse) { if (client.screen instanceof InventoryScreen) player.closeContainer(); else { player.sendOpenInventory(); client.setScreen(new InventoryScreen(player)); } }
            if (input.swapPulse) player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            if (input.dropPulse) player.drop(false);
            applyRemoteScreenPointer(client, input, effectiveUse);
        }
    }
    private void ensureRemoteInputInjected(LocalPlayer player, RemoteInput input) {
        if (injectedTargetPlayer != player || injectedTargetInput == null) {
            restoreTargetInput();
            injectedTargetPlayer = player;
            previousTargetInput = player.input;
            injectedTargetInput = new RemoteClientInput();
            player.input = injectedTargetInput;
        }
        injectedTargetInput.update(input);
    }
    private void restoreTargetInput() {
        if (injectedTargetPlayer != null && injectedTargetInput != null && injectedTargetPlayer.input == injectedTargetInput && previousTargetInput != null)
            injectedTargetPlayer.input = previousTargetInput;
        injectedTargetPlayer = null;
        injectedTargetInput = null;
        previousTargetInput = null;
    }
    private void applyRemoteScreenPointer(Minecraft client, RemoteInput input, boolean effectiveUse) {
        if (client.screen == null) { previousRemoteLeftMouse = input.attack; previousRemoteRightMouse = effectiveUse; return; }
        double mouseX = input.mouseX * client.getWindow().getGuiScaledWidth();
        double mouseY = input.mouseY * client.getWindow().getGuiScaledHeight();
        client.screen.mouseMoved(mouseX, mouseY);
        dispatchRemoteMouseButton(client, InputConstants.MOUSE_BUTTON_LEFT, input.attack, previousRemoteLeftMouse, mouseX, mouseY);
        dispatchRemoteMouseButton(client, InputConstants.MOUSE_BUTTON_RIGHT, effectiveUse, previousRemoteRightMouse, mouseX, mouseY);
        previousRemoteLeftMouse = input.attack;
        previousRemoteRightMouse = effectiveUse;
    }
    private void dispatchRemoteMouseButton(Minecraft client, int button, boolean down, boolean previouslyDown, double mouseX, double mouseY) {
        if (client.screen == null || down == previouslyDown) return;
        MouseButtonEvent event = new MouseButtonEvent(mouseX, mouseY, new MouseButtonInfo(button, 0));
        if (down) client.screen.mouseClicked(event, false); else client.screen.mouseReleased(event);
    }
    private void releaseRemoteKeys(Minecraft client) {
        client.options.keyUp.setDown(false); client.options.keyDown.setDown(false); client.options.keyLeft.setDown(false); client.options.keyRight.setDown(false); client.options.keyJump.setDown(false); client.options.keyShift.setDown(false); client.options.keySprint.setDown(false); client.options.keyAttack.setDown(false); client.options.keyUse.setDown(false); client.options.keyPickItem.setDown(false);
        restoreTargetInput();
        previousRemoteLeftMouse = false; previousRemoteRightMouse = false;
        suppressRemoteUseUntilReleased = false;
        if (client.gameMode != null && client.player != null) client.gameMode.releaseUsingItem(client.player);
        remoteInput = null; lastAppliedRemoteSequence = Long.MIN_VALUE;
    }
    private void sendObject(JsonObject message) { String json = message.toString(); sender.execute(() -> { try { write(json); } catch (IOException ignored) { } }); }
    private void sendCriticalObject(JsonObject message) {
        String json = message.toString();
        criticalSender.execute(() -> { try { write(json); } catch (IOException ignored) { } });
    }
    private void sendLatestRealtimeObject(JsonObject message) {
        pendingRealtimeMessage.set(message.toString());
        if (realtimeDrainScheduled.compareAndSet(false, true)) realtimeSender.execute(this::drainRealtimeMessages);
    }
    private void drainRealtimeMessages() {
        try {
            while (true) {
                String message = pendingRealtimeMessage.getAndSet(null);
                if (message == null) break;
                try { write(message); } catch (IOException ignored) { }
            }
        } finally {
            realtimeDrainScheduled.set(false);
            if (pendingRealtimeMessage.get() != null && realtimeDrainScheduled.compareAndSet(false, true)) realtimeSender.execute(this::drainRealtimeMessages);
        }
    }
    private static boolean bool(JsonObject object, String name) { return object.has(name) && object.get(name).getAsBoolean(); }
    private static int intValue(JsonObject object, String name, int fallback) { return object.has(name) ? object.get(name).getAsInt() : fallback; }
    private static long longValue(JsonObject object, String name, long fallback) { return object.has(name) ? object.get(name).getAsLong() : fallback; }
    private static float floatValue(JsonObject object, String name, float fallback) { return object.has(name) ? object.get(name).getAsFloat() : fallback; }
    private static double doubleValue(JsonObject object, String name, double fallback) { return object.has(name) ? object.get(name).getAsDouble() : fallback; }
    private static String stringValue(JsonObject object, String name) { return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : ""; }
    private static List<String> stringArray(JsonObject object, String name) {
        if (!object.has(name) || !object.get(name).isJsonArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (var element : object.getAsJsonArray(name)) if (element.isJsonPrimitive()) values.add(element.getAsString());
        return List.copyOf(values);
    }
    private void sendRemoteState(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || out == null) return;
        JsonObject state = new JsonObject();
        state.addProperty("type", "remote_state"); state.addProperty("sequence", ++remoteStateSequence);
        state.addProperty("dimension", client.level.dimension().identifier().toString()); state.addProperty("playerName", player.getName().getString()); state.addProperty("playerUuid", player.getUUID().toString());
        state.addProperty("x", player.getX()); state.addProperty("y", player.getY()); state.addProperty("z", player.getZ());
        state.addProperty("yaw", player.getYRot()); state.addProperty("pitch", player.getXRot()); state.addProperty("eyeHeight", player.getEyeHeight());
        state.addProperty("health", player.getHealth()); state.addProperty("maxHealth", player.getMaxHealth());
        state.addProperty("food", player.getFoodData().getFoodLevel()); state.addProperty("saturation", player.getFoodData().getSaturationLevel());
        state.addProperty("selectedHotbar", player.getInventory().getSelectedSlot());
        JsonArray hotbar = new JsonArray();
        for (int slot = 0; slot < 9; slot++) hotbar.add(remoteSlot(player.getInventory().getItem(slot), slot, 0, 0));
        state.add("hotbar", hotbar); state.add("offhand", remoteSlot(player.getOffhandItem(), 40, 0, 0));
        AbstractContainerScreen<?> screen = client.screen instanceof AbstractContainerScreen<?> containerScreen ? containerScreen : null;
        state.addProperty("screenOpen", screen != null);
        state.addProperty("screenType", screen == null ? "" : screen.getClass().getSimpleName());
        state.addProperty("title", screen == null ? "" : screen.getTitle().getString());
        state.addProperty("containerId", player.containerMenu.containerId);
        JsonArray slots = new JsonArray();
        if (screen != null) for (int index = 0; index < screen.getMenu().slots.size(); index++) {
            var slot = screen.getMenu().slots.get(index); JsonObject item = remoteSlot(slot.getItem(), index, slot.x, slot.y); slots.add(item);
        }
        state.add("slots", slots); state.add("carried", remoteSlot(player.containerMenu.getCarried(), -1, 0, 0));
        if (ticks % 40 == 0 || (cachedFunctionalBlocks.isEmpty() && ticks % 20 == 0)) updateBlockTelemetry(client, player.blockPosition(), System.currentTimeMillis());
        if (ticks % 20 == 0) state.add("functionalBlocks", cachedFunctionalBlocks.deepCopy());
        JsonArray nearbyPlayers = new JsonArray();
        for (var nearbyPlayer : client.level.players()) {
            if (nearbyPlayer == player || nearbyPlayer.distanceToSqr(player) > 128.0 * 128.0 || nearbyPlayers.size() >= 64) continue;
            JsonObject other = new JsonObject(); other.addProperty("uuid", nearbyPlayer.getUUID().toString()); other.addProperty("name", nearbyPlayer.getName().getString());
            other.addProperty("x", nearbyPlayer.getX()); other.addProperty("y", nearbyPlayer.getY()); other.addProperty("z", nearbyPlayer.getZ());
            other.addProperty("yaw", nearbyPlayer.getYRot()); other.addProperty("pitch", nearbyPlayer.getXRot()); nearbyPlayers.add(other);
        }
        state.add("nearbyPlayers", nearbyPlayers);
        JsonArray nearbyEntities = new JsonArray();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof Player || entity.distanceToSqr(player) > 128.0 * 128.0 || nearbyEntities.size() >= 96) continue;
            Identifier typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (typeId == null) continue;
            JsonObject other = new JsonObject();
            other.addProperty("uuid", entity.getUUID().toString()); other.addProperty("type", typeId.toString());
            other.addProperty("x", entity.getX()); other.addProperty("y", entity.getY()); other.addProperty("z", entity.getZ());
            other.addProperty("yaw", entity.getYRot()); other.addProperty("pitch", entity.getXRot());
            Vec3 velocity = entity.getDeltaMovement(); other.addProperty("velocityX", velocity.x); other.addProperty("velocityY", velocity.y); other.addProperty("velocityZ", velocity.z);
            other.addProperty("invisible", entity.isInvisible()); other.addProperty("glowing", entity.isCurrentlyGlowing()); other.addProperty("onGround", entity.onGround());
            other.addProperty("customName", entity.hasCustomName() ? entity.getCustomName().getString() : "");
            if (entity instanceof ItemEntity itemEntity) {
                ItemStack stack = itemEntity.getItem();
                other.addProperty("item", stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                other.addProperty("itemCount", stack.getCount());
            }
            if (entity instanceof LivingEntity living) {
                JsonArray equipment = new JsonArray();
                for (EquipmentSlot slot : PLAYER_EQUIPMENT) {
                    ItemStack stack = living.getItemBySlot(slot); if (stack.isEmpty()) continue;
                    JsonObject equipped = new JsonObject(); equipped.addProperty("slot", slot.getName());
                    equipped.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); equipped.addProperty("count", stack.getCount()); equipment.add(equipped);
                }
                other.add("equipment", equipment);
            }
            nearbyEntities.add(other);
        }
        state.add("nearbyEntities", nearbyEntities);
        sendLatestRealtimeObject(state);
    }
    private JsonObject remoteSlot(ItemStack stack, int menuSlot, int x, int y) {
        JsonObject result = new JsonObject(); result.addProperty("menuSlot", menuSlot); result.addProperty("x", x); result.addProperty("y", y);
        result.addProperty("item", stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        result.addProperty("count", stack.getCount()); result.addProperty("maxCount", stack.isEmpty() ? 0 : stack.getMaxStackSize()); return result;
    }
    private void sendNextMapTile(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || out == null) return;
        int index = Math.floorMod(mapChunkCursor++, REMOTE_CHUNK_DX.length);
        int chunkX = (player.blockPosition().getX() >> 4) + REMOTE_CHUNK_DX[index];
        int chunkZ = (player.blockPosition().getZ() >> 4) + REMOTE_CHUNK_DZ[index];
        LevelChunk chunk = client.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) return;
        JsonObject tile = new JsonObject(); tile.addProperty("type", "map_tile"); tile.addProperty("sequence", ++mapTileSequence);
        tile.addProperty("dimension", client.level.dimension().identifier().toString()); tile.addProperty("chunkX", chunkX); tile.addProperty("chunkZ", chunkZ);
        JsonArray blocks = new JsonArray(); JsonArray materials = new JsonArray();
        int startX = chunkX << 4, startZ = chunkZ << 4;
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int x = startX + dx, z = startZ + dz;
            int y = Math.min(client.level.getMaxY() - 1, client.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1);
            if (y < client.level.getMinY()) continue;
            BlockPos pos = new BlockPos(x, y, z); var blockState = client.level.getBlockState(pos);
            if (blockState.isAir()) continue;
            String blockId = BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString();
            int color = blockState.getMapColor(client.level, pos).calculateARGBColor(MapColor.Brightness.NORMAL);
            if ((color >>> 24) == 0) color |= 0xFF000000;
            JsonObject materialAsset = mapTopMaterialAsset(client, blockState, pos, blockId);
            String materialId = materialAsset == null ? blockId : stringValue(materialAsset, "id");
            JsonObject block = new JsonObject(); block.addProperty("x", x); block.addProperty("y", y); block.addProperty("z", z);
            block.addProperty("block", blockId); block.addProperty("material", materialId); block.addProperty("color", color); blocks.add(block);
            if (materialAsset != null && sentMapMaterials.add(materialId)) materials.add(materialAsset.deepCopy());
        }
        tile.add("blocks", blocks); tile.add("materials", materials); sendObject(tile);
    }
    private JsonObject mapTopMaterialAsset(Minecraft client, net.minecraft.world.level.block.state.BlockState blockState, BlockPos pos, String blockId) {
        try {
            List<BlockStateModelPart> parts = new ArrayList<>();
            client.getModelManager().getBlockStateModelSet().get(blockState).collectParts(net.minecraft.util.RandomSource.create(blockState.getSeed(pos)), parts);
            List<MapTextureLayer> layers = new ArrayList<>(); StringBuilder fingerprint = new StringBuilder(blockId);
            for (BlockStateModelPart part : parts) {
                List<BakedQuad> topQuads = new ArrayList<>(part.getQuads(Direction.UP));
                for (BakedQuad quad : part.getQuads(null)) if (quad.direction() == Direction.UP) topQuads.add(quad);
                for (BakedQuad quad : topQuads) {
                    SpriteContents contents = quad.materialInfo().sprite().contents(); int tint = 0xFFFFFFFF;
                    if (quad.materialInfo().isTinted()) {
                        var tintSource = client.getBlockColors().getTintSource(blockState, quad.materialInfo().tintIndex());
                        if (tintSource != null) tint = tintSource.colorInWorld(blockState, client.level, pos) | 0xFF000000;
                    }
                    layers.add(new MapTextureLayer(contents, tint)); fingerprint.append('|').append(contents.name()).append('@').append(Integer.toHexString(tint));
                }
            }
            if (layers.isEmpty()) {
                SpriteContents contents = client.getModelManager().getBlockStateModelSet().get(blockState).particleMaterial().sprite().contents();
                layers.add(new MapTextureLayer(contents, 0xFFFFFFFF)); fingerprint.append("|particle:").append(contents.name());
            }
            String materialId = blockId + "@" + UUID.nameUUIDFromBytes(fingerprint.toString().getBytes(StandardCharsets.UTF_8));
            if (mapMaterialCache.containsKey(materialId)) return mapMaterialCache.get(materialId);
            int width = layers.stream().mapToInt(layer -> layer.contents.width()).max().orElse(16);
            int height = layers.stream().mapToInt(layer -> layer.contents.height()).max().orElse(16);
            width = Math.max(1, Math.min(64, width)); height = Math.max(1, Math.min(64, height));
            int[] composite = new int[width * height];
            for (MapTextureLayer layer : layers) {
                NativeImage image = ((SpriteContentsAccessor)(Object)layer.contents).baritoneCluster$getOriginalImage();
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int sourceX = Math.min(image.getWidth() - 1, x * layer.contents.width() / width);
                    int sourceY = Math.min(image.getHeight() - 1, y * layer.contents.height() / height);
                    int tinted = multiplyArgb(image.getPixel(sourceX, sourceY), layer.tint);
                    int offset = y * width + x; composite[offset] = blendArgb(composite[offset], tinted);
                }
            }
            byte[] bgra = new byte[width * height * 4];
            for (int index = 0; index < composite.length; index++) {
                int argb = composite[index], offset = index * 4;
                bgra[offset] = (byte)(argb & 0xFF); bgra[offset + 1] = (byte)((argb >>> 8) & 0xFF);
                bgra[offset + 2] = (byte)((argb >>> 16) & 0xFF); bgra[offset + 3] = (byte)((argb >>> 24) & 0xFF);
            }
            JsonObject material = new JsonObject(); material.addProperty("id", materialId); material.addProperty("width", width); material.addProperty("height", height);
            material.addProperty("pixels", Base64.getEncoder().encodeToString(bgra)); mapMaterialCache.put(materialId, material); return material;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
    private static int multiplyArgb(int pixel, int tint) {
        int alpha = (pixel >>> 24) * (tint >>> 24) / 255;
        int red = ((pixel >>> 16) & 0xFF) * ((tint >>> 16) & 0xFF) / 255;
        int green = ((pixel >>> 8) & 0xFF) * ((tint >>> 8) & 0xFF) / 255;
        int blue = (pixel & 0xFF) * (tint & 0xFF) / 255;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }
    private static int blendArgb(int background, int foreground) {
        int sourceAlpha = foreground >>> 24; if (sourceAlpha == 255) return foreground; if (sourceAlpha == 0) return background;
        int destinationAlpha = background >>> 24; int inverse = 255 - sourceAlpha;
        int outputAlpha = sourceAlpha + destinationAlpha * inverse / 255; if (outputAlpha == 0) return 0;
        int red = (((foreground >>> 16) & 0xFF) * sourceAlpha + ((background >>> 16) & 0xFF) * destinationAlpha * inverse / 255) / outputAlpha;
        int green = (((foreground >>> 8) & 0xFF) * sourceAlpha + ((background >>> 8) & 0xFF) * destinationAlpha * inverse / 255) / outputAlpha;
        int blue = ((foreground & 0xFF) * sourceAlpha + (background & 0xFF) * destinationAlpha * inverse / 255) / outputAlpha;
        return outputAlpha << 24 | red << 16 | green << 8 | blue;
    }
    private JsonArray buildPreviewBlocks(Minecraft client, BlockPos center) {
        JsonArray blocks = new JsonArray(); if (client.level == null || client.player == null) return blocks;
        Vec3 eye = client.player.getEyePosition();
        double radius = inspectionPreviewRadius;
        int minX = (int)Math.floor(eye.x - radius), maxX = (int)Math.floor(eye.x + radius);
        int minY = Math.max(client.level.getMinY(), (int)Math.floor(eye.y - radius));
        int maxY = Math.min(client.level.getMaxY() - 1, (int)Math.floor(eye.y + radius));
        int minZ = (int)Math.floor(eye.z - radius), maxZ = (int)Math.floor(eye.z + radius);
        for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
            double dx = x + 0.5 - eye.x, dy = y + 0.5 - eye.y, dz = z + 0.5 - eye.z;
            if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
            BlockPos pos = new BlockPos(x, y, z); var blockState = client.level.getBlockState(pos); if (blockState.isAir()) continue;
            String blockId = BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString();
            int color = blockState.getMapColor(client.level, pos).calculateARGBColor(net.minecraft.world.level.material.MapColor.Brightness.NORMAL);
            if ((color & 0x00FFFFFF) == 0) color = 0xFF000000 | (blockId.hashCode() & 0x007F7F7F) | 0x00303030;
            JsonObject block = new JsonObject(); block.addProperty("block", blockId); block.addProperty("x", x); block.addProperty("y", y); block.addProperty("z", z);
            block.addProperty("color", color); block.addProperty("occluding", blockState.canOcclude());
            JsonObject properties = new JsonObject(); blockState.getValues().forEach(value -> properties.addProperty(value.property().getName(), value.valueName())); block.add("properties", properties); blocks.add(block);
        }
        return blocks;
    }
    private void handleRemoteInspect(JsonObject command) {
        Minecraft client = Minecraft.getInstance(); LocalPlayer player = client.player;
        if (player == null || client.level == null) return;
        String requester = stringValue(command, "sourceInstanceId"), operation = stringValue(command, "operation");
        if (requester.isBlank() || !(operation.equals("inventory") || operation.equals("nearby_blocks") || operation.equals("block_preview"))) return;
        inspectionRequesterId = requester; inspectionOperation = operation; inspectionExpiresAt = System.currentTimeMillis() + 60_000;
        if (operation.equals("inventory") && player.containerMenu != player.inventoryMenu) player.closeContainer();
        if (operation.equals("nearby_blocks")) updateBlockTelemetry(client, player.blockPosition(), System.currentTimeMillis());
        if (operation.equals("block_preview")) {
            cachedPreviewBlocks = new JsonArray(); lastPreviewScanCenter = null;
        }
        sendRemoteInspectionState(client);
    }
    private void sendRemoteInspectionState(Minecraft client) {
        LocalPlayer player = client.player; String requester = inspectionRequesterId, operation = inspectionOperation;
        if (player == null || client.level == null || requester == null || System.currentTimeMillis() > inspectionExpiresAt) { if (client.gameMode != null) client.gameMode.stopDestroyBlock(); inspectionRequesterId = null; inspectionOperation = ""; return; }
        JsonObject state = new JsonObject(); state.addProperty("sequence", ++inspectionSequence); state.addProperty("dimension", client.level.dimension().identifier().toString());
        state.addProperty("playerName", player.getName().getString()); state.addProperty("playerUuid", player.getUUID().toString());
        state.addProperty("x", player.getX()); state.addProperty("y", player.getY()); state.addProperty("z", player.getZ()); state.addProperty("yaw", player.getYRot()); state.addProperty("pitch", player.getXRot()); state.addProperty("eyeHeight", player.getEyeHeight());
        state.addProperty("health", player.getHealth()); state.addProperty("maxHealth", player.getMaxHealth()); state.addProperty("food", player.getFoodData().getFoodLevel()); state.addProperty("saturation", player.getFoodData().getSaturationLevel()); state.addProperty("selectedHotbar", player.getInventory().getSelectedSlot());
        JsonArray hotbar = new JsonArray(); for (int slot = 0; slot < 9; slot++) hotbar.add(remoteSlot(player.getInventory().getItem(slot), slot, 0, 0));
        state.add("hotbar", hotbar); state.add("offhand", remoteSlot(player.getOffhandItem(), 40, 0, 0)); state.addProperty("previewRadius", inspectionPreviewRadius);
        AbstractContainerMenu menu = null; String screenType = "", title = "";
        if (operation.equals("inventory")) { if (player.containerMenu != player.inventoryMenu) player.closeContainer(); menu = player.inventoryMenu; screenType = "InventoryMenu"; title = "目标玩家背包"; }
        else if (operation.equals("container") && client.screen instanceof AbstractContainerScreen<?> containerScreen) { menu = containerScreen.getMenu(); screenType = containerScreen.getClass().getSimpleName(); title = containerScreen.getTitle().getString(); }
        state.addProperty("screenOpen", menu != null); state.addProperty("screenType", screenType); state.addProperty("title", title); state.addProperty("containerId", menu == null ? -1 : menu.containerId);
        JsonArray slots = new JsonArray();
        if (menu != null) for (int index = 0; index < menu.slots.size(); index++) { var slot = menu.slots.get(index); slots.add(remoteSlot(slot.getItem(), index, slot.x, slot.y)); }
        state.add("slots", slots); state.add("carried", remoteSlot(menu == null ? ItemStack.EMPTY : menu.getCarried(), -1, 0, 0));
        if (operation.equals("nearby_blocks") || operation.equals("block_preview")) state.add("functionalBlocks", cachedFunctionalBlocks.deepCopy());
        if (operation.equals("block_preview")) {
            BlockPos center = player.blockPosition();
            if (cachedPreviewBlocks.isEmpty() || lastPreviewScanCenter == null || !lastPreviewScanCenter.equals(center) || ticks % 10 == 0) {
                cachedPreviewBlocks = buildPreviewBlocks(client, center); lastPreviewScanCenter = center.immutable();
            }
            state.add("previewBlocks", cachedPreviewBlocks.deepCopy());
        }
        JsonObject response = new JsonObject(); response.addProperty("type", "remote_inspect_state"); response.addProperty("requesterInstanceId", requester); response.addProperty("operation", operation); response.add("state", state); sendObject(response);
        if (operation.equals("nearby_blocks")) { inspectionRequesterId = null; inspectionOperation = ""; }
    }
    private void updateRemoteInspectionState(String sourceInstanceId, String operation, JsonObject state) {
        if (!primaryInstance || inspectionTargetId == null || !inspectionTargetId.equals(sourceInstanceId)) return;
        RemoteUiState parsed = parseRemoteUiState(sourceInstanceId, state); inspectionUiState = parsed;
        Minecraft.getInstance().execute(() -> {
            Minecraft client = Minecraft.getInstance();
            if (operation.equals("block_preview")) {
                inspectionReturningToPreview = false;
                if (client.screen instanceof RemoteBlockPreviewScreen screen && sourceInstanceId.equals(screen.targetInstanceId())) {
                    screen.update(parsed); suspendedBlockPreviewScreen = screen;
                } else if (suspendedBlockPreviewScreen != null && sourceInstanceId.equals(suspendedBlockPreviewScreen.targetInstanceId())) {
                    suspendedBlockPreviewScreen.update(parsed); inspectionPreviewActive = true; client.setScreen(suspendedBlockPreviewScreen);
                } else {
                    inspectionPreviewActive = true; suspendedBlockPreviewScreen = new RemoteBlockPreviewScreen(this, parsed); client.setScreen(suspendedBlockPreviewScreen);
                }
            } else if (operation.equals("nearby_blocks")) {
                List<RemoteFunctionalBlock> blocks = parsed.functionalBlocks().stream().filter(block -> block.distanceSquared() <= 49.0).sorted(java.util.Comparator.comparingDouble(RemoteFunctionalBlock::distanceSquared)).toList();
                client.setScreen(new NearbyFunctionalBlocksScreen(blocks, block -> requestOpenFunctionalBlock(sourceInstanceId, block), () -> openPossessionScreen(client)));
            } else if (parsed.screenOpen()) {
                if (inspectionReturningToPreview && inspectionPreviewActive) return;
                if (inspectionPreviewActive && client.screen instanceof RemoteBlockPreviewScreen preview) {
                    suspendedBlockPreviewScreen = preview; previewStopMining();
                }
                if (client.screen instanceof RemoteContainerMirrorScreen mirror && !mirror.isPossessionMirror() && sourceInstanceId.equals(mirror.sourceInstanceId())) mirror.update(parsed);
                else client.setScreen(new RemoteContainerMirrorScreen(parsed, (slot, button, quickMove) -> sendInspectionUiClick(sourceInstanceId, slot, button, quickMove), () -> closeInspectionContainer(sourceInstanceId), false));
            } else if (operation.equals("container") && client.screen instanceof RemoteContainerMirrorScreen mirror && !mirror.isPossessionMirror() && sourceInstanceId.equals(mirror.sourceInstanceId())) {
                closeInspectionContainer(sourceInstanceId);
            }
        });
    }
    private void sendInspectionUiClick(String targetInstanceId, int menuSlot, int button, boolean quickMove) {
        RemoteUiState state = inspectionUiState; if (state == null || !targetInstanceId.equals(state.sourceInstanceId())) return;
        JsonObject click = new JsonObject(); click.addProperty("type", "remote_ui_click"); click.addProperty("targetInstanceId", targetInstanceId); click.addProperty("operation", "click");
        click.addProperty("containerId", state.containerId()); click.addProperty("slot", menuSlot); click.addProperty("button", button); click.addProperty("clickType", quickMove ? "QUICK_MOVE" : "PICKUP"); sendCriticalObject(click);
    }
    private void closeInspection(String targetInstanceId) {
        if (inspectionPreviewActive && targetInstanceId.equals(inspectionTargetId)) endBlockPreview(false, false);
        JsonObject close = new JsonObject(); close.addProperty("type", "remote_ui_click"); close.addProperty("targetInstanceId", targetInstanceId); close.addProperty("operation", "inspection_close"); sendCriticalObject(close);
        inspectionTargetId = null; inspectionUiState = null; suspendedBlockPreviewScreen = null; inspectionReturningToPreview = false;
        Minecraft client = Minecraft.getInstance(); if (client.screen instanceof RemoteContainerMirrorScreen mirror && !mirror.isPossessionMirror()) { client.setScreen(null); if (client.isWindowActive()) client.mouseHandler.grabMouse(); }
    }
    private void closeInspectionContainer(String targetInstanceId) {
        if (!inspectionPreviewActive || inspectionTargetId == null || !inspectionTargetId.equals(targetInstanceId)) {
            closeInspection(targetInstanceId); return;
        }
        inspectionReturningToPreview = true;
        JsonObject resume = new JsonObject(); resume.addProperty("type", "remote_ui_click"); resume.addProperty("targetInstanceId", targetInstanceId); resume.addProperty("operation", "inspection_resume_preview"); sendCriticalObject(resume);
        Minecraft client = Minecraft.getInstance();
        if (suspendedBlockPreviewScreen != null && targetInstanceId.equals(suspendedBlockPreviewScreen.targetInstanceId())) client.setScreen(suspendedBlockPreviewScreen);
    }
    private void applyRemoteWorldAction(JsonObject action) {
        Minecraft client = Minecraft.getInstance(); LocalPlayer player = client.player;
        String requester = stringValue(action, "sourceInstanceId"), operation = stringValue(action, "operation");
        if (player == null || client.level == null || client.gameMode == null || inspectionRequesterId == null || !inspectionRequesterId.equals(requester) || !inspectionOperation.equals("block_preview")) return;
        inspectionExpiresAt = System.currentTimeMillis() + 30 * 60_000L;
        if (operation.equals("preview_radius")) {
            inspectionPreviewRadius = Math.max(2, Math.min(16, intValue(action, "radius", inspectionPreviewRadius)));
            cachedPreviewBlocks = new JsonArray(); lastPreviewScanCenter = null; sendRemoteInspectionState(client); return;
        }
        player.setYRot(floatValue(action, "yaw", player.getYRot())); player.setYHeadRot(player.getYRot()); player.setXRot(Math.max(-90.0f, Math.min(90.0f, floatValue(action, "pitch", player.getXRot()))));
        if (operation.equals("attack_stop")) { client.gameMode.stopDestroyBlock(); return; }
        BlockPos pos = new BlockPos(intValue(action, "x", 0), intValue(action, "y", 0), intValue(action, "z", 0));
        if (operation.equals("baritone_goto_above")) {
            client.gameMode.stopDestroyBlock(); player.closeContainer();
            player.connection.sendChat("#stop"); player.connection.sendChat("#goto " + pos.getX() + " " + (pos.getY() + 1) + " " + pos.getZ()); return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > 64.0) { client.gameMode.stopDestroyBlock(); return; }
        Direction face;
        try { face = Direction.valueOf(stringValue(action, "face").toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ex) { face = Direction.UP; }
        if (client.screen != null) { player.closeContainer(); client.setScreen(null); }
        if (operation.equals("use")) {
            client.gameMode.stopDestroyBlock();
            Vec3 hit = new Vec3(doubleValue(action, "hitX", pos.getX() + 0.5), doubleValue(action, "hitY", pos.getY() + 0.5), doubleValue(action, "hitZ", pos.getZ() + 0.5));
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false)); player.swing(InteractionHand.MAIN_HAND);
            if (client.screen instanceof AbstractContainerScreen<?>) { inspectionOperation = "container"; sendRemoteInspectionState(client); }
        } else if (operation.equals("attack_start")) {
            if (client.gameMode.startDestroyBlock(pos, face)) player.swing(InteractionHand.MAIN_HAND);
        } else if (operation.equals("attack_continue")) {
            if (client.gameMode.continueDestroyBlock(pos, face)) player.swing(InteractionHand.MAIN_HAND);
        }
    }
    void closeBlockPreview() { endBlockPreview(true, true); }
    void closeBlockPreviewAndReturn() {
        endBlockPreview(true, false);
        Minecraft client = Minecraft.getInstance(); if (primaryInstance) openPossessionScreen(client); else client.setScreen(null);
    }
    private void endBlockPreview(boolean notifyTarget, boolean closeScreen) {
        if (!inspectionPreviewActive) return;
        String target = inspectionTargetId;
        previewStopMining(); inspectionPreviewActive = false; inspectionReturningToPreview = false;
        Minecraft client = Minecraft.getInstance();
        if (closeScreen && client.screen instanceof RemoteBlockPreviewScreen) client.setScreen(null);
        if (notifyTarget && target != null) {
            JsonObject close = new JsonObject(); close.addProperty("type", "remote_ui_click"); close.addProperty("targetInstanceId", target); close.addProperty("operation", "inspection_close"); sendCriticalObject(close);
            inspectionTargetId = null; inspectionUiState = null;
        }
        suspendedBlockPreviewScreen = null;
    }
    void previewUseBlock(BlockHitResult hit, float yaw, float pitch) {
        if (hit != null) sendPreviewWorldAction("use", hit, yaw, pitch, true);
    }
    void previewStartMining(BlockHitResult hit, float yaw, float pitch) {
        if (hit == null) return;
        previewMiningBlock = hit.getBlockPos(); previewMiningFace = hit.getDirection(); sendPreviewWorldAction("attack_start", hit, yaw, pitch, true);
    }
    void previewContinueMining(BlockHitResult hit, float yaw, float pitch) {
        if (hit == null) { previewStopMining(); return; }
        if (previewMiningBlock == null || !previewMiningBlock.equals(hit.getBlockPos())) { previewStopMining(); previewStartMining(hit, yaw, pitch); return; }
        previewMiningFace = hit.getDirection(); sendPreviewWorldAction("attack_continue", hit, yaw, pitch, false);
    }
    void previewStopMining() {
        if (previewMiningBlock == null || inspectionTargetId == null) return;
        JsonObject action = basePreviewWorldAction("attack_stop"); sendCriticalObject(action); previewMiningBlock = null; previewMiningFace = null;
    }
    void previewGotoAbove(BlockHitResult hit) {
        if (hit == null) return;
        JsonObject action = basePreviewWorldAction("baritone_goto_above"); BlockPos pos = hit.getBlockPos();
        action.addProperty("x", pos.getX()); action.addProperty("y", pos.getY()); action.addProperty("z", pos.getZ()); sendCriticalObject(action);
    }
    void previewSetRadius(int radius) {
        JsonObject action = basePreviewWorldAction("preview_radius"); action.addProperty("radius", Math.max(2, Math.min(16, radius))); sendCriticalObject(action);
    }
    private void sendPreviewWorldAction(String operation, BlockHitResult hit, float yaw, float pitch, boolean critical) {
        JsonObject action = basePreviewWorldAction(operation); BlockPos pos = hit.getBlockPos(); Vec3 location = hit.getLocation();
        action.addProperty("x", pos.getX()); action.addProperty("y", pos.getY()); action.addProperty("z", pos.getZ()); action.addProperty("face", hit.getDirection().getName());
        action.addProperty("hitX", location.x); action.addProperty("hitY", location.y); action.addProperty("hitZ", location.z);
        action.addProperty("yaw", yaw); action.addProperty("pitch", pitch);
        if (critical) sendCriticalObject(action); else sendObject(action);
    }
    private JsonObject basePreviewWorldAction(String operation) {
        JsonObject action = new JsonObject(); action.addProperty("type", "remote_world_action"); action.addProperty("targetInstanceId", inspectionTargetId); action.addProperty("operation", operation);
        return action;
    }
    private void sendNextRemoteChunk(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.getConnection() == null || out == null) return;
        int centerX = player.blockPosition().getX() >> 4, centerZ = player.blockPosition().getZ() >> 4;
        if (centerX != lastRemoteChunkCenterX || centerZ != lastRemoteChunkCenterZ) { lastRemoteChunkCenterX = centerX; lastRemoteChunkCenterZ = centerZ; remoteChunkCursor = 0; }
        int cycle = remoteChunkCursor++;
        int index = cycle % 5 == 0 ? 0 : 1 + Math.floorMod(cycle - 1 - cycle / 5, 24);
        int chunkX = centerX + REMOTE_CHUNK_DX[index], chunkZ = centerZ + REMOTE_CHUNK_DZ[index];
        LevelChunk chunk = client.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) return;
        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk, client.level.getChunkSource().getLightEngine(), null, null);
        long sequence = ++remoteChunkSequence;
        String dimension = client.level.dimension().identifier().toString();
        var registryAccess = client.getConnection().registryAccess();
        chunkCodec.execute(() -> {
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registryAccess);
            try {
                ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, packet);
                byte[] encoded = new byte[buffer.readableBytes()]; buffer.getBytes(buffer.readerIndex(), encoded);
                JsonObject message = new JsonObject(); message.addProperty("type", "remote_chunk"); message.addProperty("sequence", sequence);
                message.addProperty("dimension", dimension); message.addProperty("centerX", centerX); message.addProperty("centerZ", centerZ);
                message.addProperty("chunkX", chunkX); message.addProperty("chunkZ", chunkZ); message.addProperty("data", Base64.getEncoder().encodeToString(encoded));
                lastChunkSentAt = System.currentTimeMillis(); remoteChunkError = ""; sendObject(message);
            } catch (Exception ex) { remoteChunkError = transportError(ex); } finally { buffer.release(); }
        });
    }
    private void updateRemoteState(String sourceInstanceId, JsonObject state) {
        if (!primaryInstance || !sourceInstanceId.equals(selectedPossessionTarget)) return;
        RemoteUiState parsed = parseRemoteUiState(sourceInstanceId, state);
        Minecraft.getInstance().execute(() -> installRemoteState(parsed));
    }
    private RemoteUiState parseRemoteUiState(String sourceInstanceId, JsonObject state) {
        List<RemoteSlot> slots = new ArrayList<>();
        if (state.has("slots")) for (var element : state.getAsJsonArray("slots")) slots.add(parseRemoteSlot(element.getAsJsonObject()));
        RemoteSlot carried = state.has("carried") && state.get("carried").isJsonObject() ? parseRemoteSlot(state.getAsJsonObject("carried")) : null;
        List<RemoteSlot> hotbar = new ArrayList<>();
        if (state.has("hotbar")) for (var element : state.getAsJsonArray("hotbar")) hotbar.add(parseRemoteSlot(element.getAsJsonObject()));
        RemoteSlot offhand = state.has("offhand") && state.get("offhand").isJsonObject() ? parseRemoteSlot(state.getAsJsonObject("offhand")) : null;
        double playerX = doubleValue(state, "x", 0), playerY = doubleValue(state, "y", 0), playerZ = doubleValue(state, "z", 0);
        List<RemoteFunctionalBlock> functionalBlocks = new ArrayList<>();
        if (state.has("functionalBlocks")) for (var element : state.getAsJsonArray("functionalBlocks")) {
            JsonObject block = element.getAsJsonObject(); int x = intValue(block, "x", 0), y = intValue(block, "y", 0), z = intValue(block, "z", 0);
            double dx = x + 0.5 - playerX, dy = y + 0.5 - (playerY + 1.0), dz = z + 0.5 - playerZ;
            functionalBlocks.add(new RemoteFunctionalBlock(stringValue(block, "block"), x, y, z, dx * dx + dy * dy + dz * dz));
        } else {
            RemoteUiState previous = remoteUiState;
            if (previous != null && sourceInstanceId.equals(previous.sourceInstanceId())) functionalBlocks.addAll(previous.functionalBlocks());
        }
        List<RemotePlayerSnapshot> nearbyPlayers = new ArrayList<>();
        if (state.has("nearbyPlayers")) for (var element : state.getAsJsonArray("nearbyPlayers")) {
            JsonObject other = element.getAsJsonObject(); nearbyPlayers.add(new RemotePlayerSnapshot(stringValue(other, "uuid"), stringValue(other, "name"),
                    doubleValue(other, "x", 0), doubleValue(other, "y", 0), doubleValue(other, "z", 0), floatValue(other, "yaw", 0), floatValue(other, "pitch", 0)));
        }
        List<RemoteEntitySnapshot> nearbyEntities = new ArrayList<>();
        if (state.has("nearbyEntities")) for (var element : state.getAsJsonArray("nearbyEntities")) {
            JsonObject other = element.getAsJsonObject(); List<RemoteEntityEquipment> equipment = new ArrayList<>();
            if (other.has("equipment")) for (var equippedElement : other.getAsJsonArray("equipment")) {
                JsonObject equipped = equippedElement.getAsJsonObject();
                equipment.add(new RemoteEntityEquipment(stringValue(equipped, "slot"), stringValue(equipped, "item"), intValue(equipped, "count", 1)));
            }
            nearbyEntities.add(new RemoteEntitySnapshot(stringValue(other, "uuid"), stringValue(other, "type"),
                    doubleValue(other, "x", 0), doubleValue(other, "y", 0), doubleValue(other, "z", 0),
                    floatValue(other, "yaw", 0), floatValue(other, "pitch", 0),
                    doubleValue(other, "velocityX", 0), doubleValue(other, "velocityY", 0), doubleValue(other, "velocityZ", 0),
                    bool(other, "invisible"), bool(other, "glowing"), bool(other, "onGround"),
                    stringValue(other, "customName"), stringValue(other, "item"), intValue(other, "itemCount", 0), List.copyOf(equipment)));
        }
        List<RemotePreviewBlock> previewBlocks = new ArrayList<>();
        if (state.has("previewBlocks")) for (var element : state.getAsJsonArray("previewBlocks")) {
            JsonObject block = element.getAsJsonObject(); Map<String, String> properties = new HashMap<>();
            if (block.has("properties")) for (var property : block.getAsJsonObject("properties").entrySet()) properties.put(property.getKey(), property.getValue().getAsString());
            previewBlocks.add(new RemotePreviewBlock(stringValue(block, "block"), intValue(block, "x", 0), intValue(block, "y", 0), intValue(block, "z", 0), intValue(block, "color", 0xFF777777), bool(block, "occluding"), Map.copyOf(properties)));
        } else {
            RemoteUiState previous = inspectionUiState;
            if (previous != null && sourceInstanceId.equals(previous.sourceInstanceId())) previewBlocks.addAll(previous.previewBlocks());
        }
        return new RemoteUiState(sourceInstanceId, longValue(state, "sequence", 0), stringValue(state, "dimension"), stringValue(state, "playerName"), stringValue(state, "playerUuid"),
                 playerX, playerY, playerZ, floatValue(state, "yaw", 0), floatValue(state, "pitch", 0),
                 floatValue(state, "eyeHeight", 1.62f),
                 floatValue(state, "health", 0), floatValue(state, "maxHealth", 0), intValue(state, "food", 0), floatValue(state, "saturation", 0), intValue(state, "selectedHotbar", 0),
                 List.copyOf(hotbar), offhand,
                 bool(state, "screenOpen"), stringValue(state, "screenType"), stringValue(state, "title"), intValue(state, "containerId", -1), List.copyOf(slots), carried, List.copyOf(functionalBlocks), List.copyOf(nearbyPlayers), List.copyOf(nearbyEntities), List.copyOf(previewBlocks), intValue(state, "previewRadius", 8));
    }
    private RemoteSlot parseRemoteSlot(JsonObject slot) {
        return new RemoteSlot(intValue(slot, "menuSlot", -1), intValue(slot, "x", 0), intValue(slot, "y", 0), stringValue(slot, "item"), intValue(slot, "count", 0), intValue(slot, "maxCount", 0));
    }
    private void installRemoteState(RemoteUiState state) {
        Minecraft client = Minecraft.getInstance();
        if (!primaryInstance || !state.sourceInstanceId().equals(selectedPossessionTarget) || client.level == null || client.player == null) return;
        if (savedLocalChunks.isEmpty()) saveLocalChunks(client);
        remoteUiState = state; remoteStateReceivedAt = System.currentTimeMillis();
        syncMirroredNearbyPlayers(client, state);
        syncMirroredNearbyEntities(client, state);
        if (!state.sourceInstanceId().equals(initializedViewTarget)) {
            remoteViewYaw = state.yaw(); remoteViewPitch = state.pitch(); remoteHotbarSlot = state.selectedHotbar(); initializedViewTarget = state.sourceInstanceId();
        }
        if (remoteCamera == null || remoteCamera.level() != client.level) {
            remoteCamera = new PossessionCameraEntity(client.level); remoteCameraPositionInitialized = false;
        }
        double cameraY = state.y() + state.eyeHeight();
        if (!remoteCameraPositionInitialized) {
            remoteCamera.setPos(state.x(), cameraY, state.z()); remoteCamera.setOldPosAndRot(new Vec3(state.x(), cameraY, state.z()), remoteViewYaw, remoteViewPitch); remoteCameraPositionInitialized = true;
        }
        updateRemoteCameraRotation();
        client.setCameraEntity(remoteCamera);
        int chunkX = ((int)Math.floor(state.x())) >> 4, chunkZ = ((int)Math.floor(state.z())) >> 4;
        if (chunkX != appliedRemoteChunkCenterX || chunkZ != appliedRemoteChunkCenterZ) {
            appliedRemoteChunkCenterX = chunkX; appliedRemoteChunkCenterZ = chunkZ; client.level.getChunkSource().updateViewCenter(chunkX, chunkZ); client.levelRenderer.allChanged();
        }
        syncRemoteScreen(client, state);
    }
    private void updateRemoteChunk(String sourceInstanceId, JsonObject chunk) {
        if (!primaryInstance || !sourceInstanceId.equals(selectedPossessionTarget) || !chunk.has("data")) return;
        byte[] encoded;
        try { encoded = Base64.getDecoder().decode(chunk.get("data").getAsString()); } catch (IllegalArgumentException ex) { remoteChunkError = transportError(ex); return; }
        String dimension = stringValue(chunk, "dimension");
        chunkCodec.execute(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client.getConnection() == null) return;
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(encoded), client.getConnection().registryAccess());
            try {
                ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buffer);
                client.execute(() -> installRemoteChunk(sourceInstanceId, dimension, packet));
            } catch (Exception ex) { remoteChunkError = transportError(ex); } finally { buffer.release(); }
        });
    }
    private void installRemoteChunk(String sourceInstanceId, String dimension, ClientboundLevelChunkWithLightPacket packet) {
        Minecraft client = Minecraft.getInstance(); RemoteUiState state = remoteUiState;
        if (!primaryInstance || !sourceInstanceId.equals(selectedPossessionTarget) || state == null || client.level == null || client.getConnection() == null) return;
        if (!dimension.equals(state.dimension())) return;
        int centerX = ((int)Math.floor(state.x())) >> 4, centerZ = ((int)Math.floor(state.z())) >> 4;
        client.level.getChunkSource().updateViewCenter(centerX, centerZ); packet.handle(client.getConnection());
        lastChunkReceivedAt = System.currentTimeMillis(); remoteChunkError = "";
    }
    private void maintainPrimaryPossessionView(Minecraft client) {
        if (inspectionPreviewActive) return;
        if (!primaryInstance || selectedPossessionTarget == null) { clearPrimaryPossessionView(client); return; }
        if (client.screen instanceof PossessionScreen) return;
        RemoteUiState state = remoteUiState;
        if (state == null || !selectedPossessionTarget.equals(state.sourceInstanceId()) || System.currentTimeMillis() - remoteStateReceivedAt > 3000) return;
        smoothRemoteCameraPosition(state); if (remoteCamera != null) client.setCameraEntity(remoteCamera); syncRemoteScreen(client, state);
    }
    private void smoothRemoteCameraPosition(RemoteUiState state) {
        if (remoteCamera == null) return;
        remoteCamera.setOldPosAndRot();
        double targetY = state.y() + state.eyeHeight();
        double dx = state.x() - remoteCamera.getX(), dy = targetY - remoteCamera.getY(), dz = state.z() - remoteCamera.getZ();
        if (dx * dx + dy * dy + dz * dz > 64.0) remoteCamera.setPos(state.x(), targetY, state.z());
        else remoteCamera.setPos(remoteCamera.getX() + dx * 0.55, remoteCamera.getY() + dy * 0.55, remoteCamera.getZ() + dz * 0.55);
        updateRemoteCameraRotation();
    }
    private void updateRemoteCameraRotation() {
        if (remoteCamera == null) return;
        remoteCamera.setYRot(remoteViewYaw); remoteCamera.setYHeadRot(remoteViewYaw); remoteCamera.setXRot(remoteViewPitch);
    }
    private void syncMirroredNearbyPlayers(Minecraft client, RemoteUiState state) {
        if (client.level == null || client.player == null) return;
        java.util.Set<UUID> received = new java.util.HashSet<>();
        for (RemotePlayerSnapshot snapshot : state.nearbyPlayers()) {
            UUID uuid; try { uuid = UUID.fromString(snapshot.uuid()); } catch (IllegalArgumentException ignored) { continue; }
            if (uuid.equals(client.player.getUUID())) continue;
            received.add(uuid); RemotePlayer mirrored = mirroredNearbyPlayers.get(uuid);
            if (mirrored != null && mirrored.level() != client.level) { mirroredNearbyPlayers.remove(uuid); mirrored = null; }
            RemotePlayer finalMirrored = mirrored;
            boolean naturallyTracked = client.level.players().stream().anyMatch(player -> player != finalMirrored && uuid.equals(player.getUUID()));
            if (naturallyTracked) { if (mirrored != null) removeMirroredPlayer(client, uuid, mirrored); continue; }
            if (mirrored == null) {
                mirrored = new RemotePlayer(client.level, new GameProfile(uuid, snapshot.name().isBlank() ? "RemotePlayer" : snapshot.name()));
                mirrored.setId(nextMirroredEntityId--); client.level.addEntity(mirrored); mirroredNearbyPlayers.put(uuid, mirrored);
                mirrored.setPos(snapshot.x(), snapshot.y(), snapshot.z()); mirrored.setOldPosAndRot(new Vec3(snapshot.x(), snapshot.y(), snapshot.z()), snapshot.yaw(), snapshot.pitch());
            } else { mirrored.setOldPosAndRot(); mirrored.setPos(snapshot.x(), snapshot.y(), snapshot.z()); }
            mirrored.setYRot(snapshot.yaw()); mirrored.setYHeadRot(snapshot.yaw()); mirrored.setXRot(snapshot.pitch());
        }
        new ArrayList<>(mirroredNearbyPlayers.entrySet()).stream().filter(entry -> !received.contains(entry.getKey())).forEach(entry -> removeMirroredPlayer(client, entry.getKey(), entry.getValue()));
    }
    private void removeMirroredPlayer(Minecraft client, UUID uuid, RemotePlayer player) {
        mirroredNearbyPlayers.remove(uuid); if (client.level != null && client.level.getEntity(player.getId()) == player) client.level.removeEntity(player.getId(), Entity.RemovalReason.DISCARDED);
    }
    private void clearMirroredPlayers(Minecraft client) {
        new ArrayList<>(mirroredNearbyPlayers.entrySet()).forEach(entry -> removeMirroredPlayer(client, entry.getKey(), entry.getValue())); mirroredNearbyPlayers.clear();
    }
    private void syncMirroredNearbyEntities(Minecraft client, RemoteUiState state) {
        if (client.level == null) return;
        java.util.Set<UUID> received = new java.util.HashSet<>();
        for (RemoteEntitySnapshot snapshot : state.nearbyEntities()) {
            UUID uuid; try { uuid = UUID.fromString(snapshot.uuid()); } catch (IllegalArgumentException ignored) { continue; }
            Identifier typeId = Identifier.tryParse(snapshot.type()); if (typeId == null) continue;
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(typeId).orElse(null);
            if (type == null || type == EntityType.PLAYER) continue;
            Entity mirrored = mirroredNearbyEntities.get(uuid);
            if (mirrored != null && (mirrored.level() != client.level || mirrored.getType() != type)) {
                removeMirroredEntity(client, uuid, mirrored); mirrored = null;
            }
            Entity naturallyTracked = client.level.getEntity(uuid);
            if (naturallyTracked != null && naturallyTracked != mirrored) {
                if (mirrored != null) removeMirroredEntity(client, uuid, mirrored);
                continue;
            }
            received.add(uuid);
            if (mirrored == null) {
                try { mirrored = type.create(client.level, EntitySpawnReason.LOAD); } catch (RuntimeException ignored) { continue; }
                if (mirrored == null) continue;
                mirrored.setUUID(uuid); mirrored.setId(nextMirroredEntityId--);
                mirrored.setPos(snapshot.x(), snapshot.y(), snapshot.z());
                mirrored.setOldPosAndRot(new Vec3(snapshot.x(), snapshot.y(), snapshot.z()), snapshot.yaw(), snapshot.pitch());
                client.level.addEntity(mirrored); mirroredNearbyEntities.put(uuid, mirrored);
            } else {
                mirrored.setOldPosAndRot(); mirrored.setPos(snapshot.x(), snapshot.y(), snapshot.z());
            }
            mirrored.setYRot(snapshot.yaw()); mirrored.setYHeadRot(snapshot.yaw()); mirrored.setXRot(snapshot.pitch());
            mirrored.setDeltaMovement(snapshot.velocityX(), snapshot.velocityY(), snapshot.velocityZ()); mirrored.setOnGround(snapshot.onGround());
            mirrored.setInvisible(snapshot.invisible()); mirrored.setGlowingTag(snapshot.glowing());
            mirrored.setCustomName(snapshot.customName().isBlank() ? null : net.minecraft.network.chat.Component.literal(snapshot.customName()));
            if (mirrored instanceof ItemEntity itemEntity) itemEntity.setItem(remoteEntityItem(snapshot.item(), snapshot.itemCount()));
            if (mirrored instanceof LivingEntity living) {
                for (EquipmentSlot slot : PLAYER_EQUIPMENT) living.setItemSlot(slot, ItemStack.EMPTY);
                for (RemoteEntityEquipment equipped : snapshot.equipment()) {
                    try { living.setItemSlot(EquipmentSlot.byName(equipped.slot()), remoteEntityItem(equipped.item(), equipped.count())); }
                    catch (IllegalArgumentException ignored) { }
                }
            }
        }
        new ArrayList<>(mirroredNearbyEntities.entrySet()).stream().filter(entry -> !received.contains(entry.getKey())).forEach(entry -> removeMirroredEntity(client, entry.getKey(), entry.getValue()));
    }
    private ItemStack remoteEntityItem(String itemId, int count) {
        Identifier id = Identifier.tryParse(itemId); if (id == null || count <= 0) return ItemStack.EMPTY;
        var item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, count);
    }
    private void removeMirroredEntity(Minecraft client, UUID uuid, Entity entity) {
        mirroredNearbyEntities.remove(uuid);
        if (client.level != null && client.level.getEntity(entity.getId()) == entity) client.level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
    }
    private void clearMirroredEntities(Minecraft client) {
        new ArrayList<>(mirroredNearbyEntities.entrySet()).forEach(entry -> removeMirroredEntity(client, entry.getKey(), entry.getValue())); mirroredNearbyEntities.clear();
    }
    private void syncRemoteScreen(Minecraft client, RemoteUiState state) {
        if (client.screen instanceof PossessionScreen || client.screen instanceof RemoteBlockPreviewScreen) return;
        if (client.screen instanceof RemoteContainerMirrorScreen mirror && !mirror.isPossessionMirror()) return;
        if (state.screenOpen()) {
            if (client.screen instanceof RemoteContainerMirrorScreen mirror) mirror.update(state);
            else client.setScreen(new RemoteContainerMirrorScreen(state, this::sendRemoteUiClick, this::sendRemoteUiClose));
        } else if (client.screen instanceof RemoteContainerMirrorScreen mirror && mirror.isPossessionMirror()) {
            client.setScreen(null); if (client.isWindowActive()) client.mouseHandler.grabMouse();
        }
    }
    private void sendRemoteUiClick(int menuSlot, int button, boolean quickMove) {
        RemoteUiState state = remoteUiState; if (state == null) return;
        JsonObject click = new JsonObject(); click.addProperty("type", "remote_ui_click"); click.addProperty("operation", "click");
        click.addProperty("containerId", state.containerId()); click.addProperty("slot", menuSlot); click.addProperty("button", button);
        click.addProperty("clickType", quickMove ? "QUICK_MOVE" : "PICKUP"); sendCriticalObject(click);
    }
    private void sendRemoteUiClose() {
        suppressPrimaryUseUntilReleased = true;
        previousPrimaryRightMouse = false;
        JsonObject click = new JsonObject(); click.addProperty("type", "remote_ui_click"); click.addProperty("operation", "close"); sendCriticalObject(click);
    }
    private void suppressRemoteUseUntilPhysicalRelease(Minecraft client, LocalPlayer player) {
        suppressRemoteUseUntilReleased = true;
        previousRemoteRightMouse = false;
        client.options.keyUse.setDown(false);
        if (client.gameMode != null) client.gameMode.releaseUsingItem(player);
    }
    private void applyRemoteUiClick(JsonObject click) {
        Minecraft client = Minecraft.getInstance(); LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) return;
        String operation = stringValue(click, "operation");
        if ("inspection_close".equals(operation)) { suppressRemoteUseUntilPhysicalRelease(client, player); client.gameMode.stopDestroyBlock(); inspectionRequesterId = null; inspectionOperation = ""; return; }
        if ("inspection_resume_preview".equals(operation)) {
            String requester = stringValue(click, "sourceInstanceId");
            if (requester.isBlank()) return;
            suppressRemoteUseUntilPhysicalRelease(client, player); client.gameMode.stopDestroyBlock(); player.closeContainer(); inspectionRequesterId = requester; inspectionOperation = "block_preview";
            inspectionExpiresAt = System.currentTimeMillis() + 30 * 60_000L; cachedPreviewBlocks = new JsonArray(); lastPreviewScanCenter = null; sendRemoteInspectionState(client); return;
        }
        if ("close".equals(operation)) { suppressRemoteUseUntilPhysicalRelease(client, player); player.closeContainer(); return; }
        if ("open_inventory".equals(operation)) { player.closeContainer(); player.sendOpenInventory(); client.setScreen(new InventoryScreen(player)); return; }
        if ("open_block".equals(operation)) {
            inspectionRequesterId = stringValue(click, "sourceInstanceId"); inspectionOperation = "container"; inspectionExpiresAt = System.currentTimeMillis() + 60_000;
            player.closeContainer(); BlockPos pos = new BlockPos(intValue(click, "x", 0), intValue(click, "y", 0), intValue(click, "z", 0));
            if (player.distanceToSqr(Vec3.atCenterOf(pos)) <= 49.0) { client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)); player.swing(InteractionHand.MAIN_HAND); }
            return;
        }
        if (!"click".equals(operation) || intValue(click, "containerId", -1) != player.containerMenu.containerId) return;
        try { client.gameMode.handleContainerInput(player.containerMenu.containerId, intValue(click, "slot", -999), intValue(click, "button", 0), ContainerInput.valueOf(stringValue(click, "clickType")), player); if (inspectionRequesterId != null) sendRemoteInspectionState(client); }
        catch (IllegalArgumentException ignored) { }
    }
    private void saveLocalChunks(Minecraft client) {
        if (client.level == null || client.player == null) return;
        savedLocalCenterX = client.player.blockPosition().getX() >> 4; savedLocalCenterZ = client.player.blockPosition().getZ() >> 4;
        for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
            int x = savedLocalCenterX + dx, z = savedLocalCenterZ + dz;
            LevelChunk chunk = client.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk != null) savedLocalChunks.put(chunkKey(x, z), new ClientboundLevelChunkWithLightPacket(chunk, client.level.getChunkSource().getLightEngine(), null, null));
        }
    }
    private void clearPrimaryPossessionView(Minecraft client) {
        if (client.player != null && client.getCameraEntity() != client.player) client.setCameraEntity(client.player);
        if (client.screen instanceof RemoteContainerMirrorScreen mirror && mirror.isPossessionMirror()) { client.setScreen(null); if (client.isWindowActive()) client.mouseHandler.grabMouse(); }
        if (client.level != null && client.getConnection() != null && !savedLocalChunks.isEmpty()) {
            client.level.getChunkSource().updateViewCenter(savedLocalCenterX, savedLocalCenterZ);
            savedLocalChunks.values().forEach(packet -> packet.handle(client.getConnection())); client.levelRenderer.allChanged();
        }
        clearMirroredPlayers(client); clearMirroredEntities(client); savedLocalChunks.clear(); remoteCamera = null; remoteCameraPositionInitialized = false; remoteUiState = null; initializedViewTarget = null;
        suppressPrimaryUseUntilReleased = false;
        appliedRemoteChunkCenterX = Integer.MIN_VALUE; appliedRemoteChunkCenterZ = Integer.MIN_VALUE;
    }
    private static long chunkKey(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    private static String transportError(Throwable throwable) { String message = throwable.getMessage(); return throwable.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message); }
    public static void routeCameraTurn(LocalPlayer player, double yawDelta, double pitchDelta) {
        ClusterBridgeClient active = ACTIVE;
        if (active == null || !active.primaryInstance || active.selectedPossessionTarget == null) { player.turn(yawDelta, pitchDelta); return; }
        active.remoteViewYaw += (float)yawDelta * 0.15f;
        active.remoteViewPitch = Math.max(-90.0f, Math.min(90.0f, active.remoteViewPitch + (float)pitchDelta * 0.15f));
        active.updateRemoteCameraRotation();
    }
    public static boolean routeHotbarScroll(double horizontal, double vertical) {
        ClusterBridgeClient active = ACTIVE;
        if (active == null || !active.primaryInstance || active.selectedPossessionTarget == null || Minecraft.getInstance().screen != null) return false;
        double amount = vertical == 0 ? -horizontal : vertical;
        if (amount != 0) active.remoteHotbarSlot = Math.floorMod(active.remoteHotbarSlot - (int)Math.signum(amount), 9);
        return true;
    }
    public static boolean shouldContinueRemoteAttackWithoutLocalMouse() {
        ClusterBridgeClient active = ACTIVE; RemoteInput input = active == null ? null : active.remoteInput;
        return active != null && active.possessionTarget && input != null && input.attack && System.currentTimeMillis() - active.remoteInputReceivedAt <= 3000;
    }
    public static boolean extractPossessionHotbar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        ClusterBridgeClient active = ACTIVE; RemoteUiState state = active == null ? null : active.remoteUiState;
        if (active == null || !active.primaryInstance || active.selectedPossessionTarget == null || state == null || !active.selectedPossessionTarget.equals(state.sourceInstanceId()) || System.currentTimeMillis() - active.remoteStateReceivedAt > 3000) return false;
        int center = graphics.guiWidth() / 2, bottom = graphics.guiHeight();
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace("hud/hotbar"), center - 91, bottom - 22, 182, 22);
        int selected = Math.max(0, Math.min(8, active.remoteHotbarSlot));
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace("hud/hotbar_selection"), center - 92 + selected * 20, bottom - 23, 24, 23);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = slot < state.hotbar().size() ? remoteStack(state.hotbar().get(slot)) : ItemStack.EMPTY;
            if (stack.isEmpty()) continue;
            int x = center - 88 + slot * 20, y = bottom - 19; graphics.item(stack, x, y); graphics.itemDecorations(Minecraft.getInstance().font, stack, x, y);
        }
        ItemStack offhand = remoteStack(state.offhand());
        if (!offhand.isEmpty()) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace("hud/hotbar_offhand_left"), center - 120, bottom - 23, 29, 24);
            int x = center - 117, y = bottom - 19; graphics.item(offhand, x, y); graphics.itemDecorations(Minecraft.getInstance().font, offhand, x, y);
        }
        return true;
    }
    public static ItemStack possessionMainHandItem() {
        ClusterBridgeClient active = ACTIVE; RemoteUiState state = active == null ? null : active.remoteUiState;
        if (active == null || !active.primaryInstance || active.selectedPossessionTarget == null || state == null || !active.selectedPossessionTarget.equals(state.sourceInstanceId())) return null;
        int selected = Math.max(0, Math.min(8, active.remoteHotbarSlot)); return selected < state.hotbar().size() ? remoteStack(state.hotbar().get(selected)) : ItemStack.EMPTY;
    }
    public static ItemStack possessionOffhandItem() {
        ClusterBridgeClient active = ACTIVE; RemoteUiState state = active == null ? null : active.remoteUiState;
        if (active == null || !active.primaryInstance || active.selectedPossessionTarget == null || state == null || !active.selectedPossessionTarget.equals(state.sourceInstanceId())) return null;
        return remoteStack(state.offhand());
    }
    private static ItemStack remoteStack(RemoteSlot slot) {
        if (slot == null || slot.item() == null || slot.item().isBlank() || slot.count() <= 0) return ItemStack.EMPTY;
        Identifier id = Identifier.tryParse(slot.item()); if (id == null) return ItemStack.EMPTY;
        var item = BuiltInRegistries.ITEM.getValue(id); return item == null ? ItemStack.EMPTY : new ItemStack(item, slot.count());
    }
    public static boolean shouldHidePossessedEntity(Entity entity) {
        ClusterBridgeClient active = ACTIVE; RemoteUiState state = active == null ? null : active.remoteUiState;
        if (active == null || !active.primaryInstance || active.selectedPossessionTarget == null || state == null || entity == active.remoteCamera) return false;
        String uuid = state.playerUuid();
        if (!uuid.isBlank() && uuid.equalsIgnoreCase(entity.getUUID().toString())) return true;
        return uuid.isBlank() && entity != Minecraft.getInstance().player && state.playerName().equals(entity.getName().getString());
    }
    private void performAction(JsonObject action) { Minecraft mc = Minecraft.getInstance(); LocalPlayer p = mc.player; if (p == null || mc.gameMode == null) return; String op = action.get("operation").getAsString();
        if ("open_block".equals(op)) { BlockPos pos = new BlockPos(action.get("x").getAsInt(), action.get("y").getAsInt(), action.get("z").getAsInt()); mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)); p.swing(InteractionHand.MAIN_HAND); }
        if ("click_slot".equals(op)) mc.gameMode.handleContainerInput(p.containerMenu.containerId, action.get("slot").getAsInt(), action.has("button") ? action.get("button").getAsInt() : 0, ContainerInput.valueOf(action.has("clickType") ? action.get("clickType").getAsString() : "PICKUP"), p);
        if ("deposit_item".equals(op) && p.containerMenu != p.inventoryMenu) { String wanted = action.get("item").getAsString(); for (int slot = 0; slot < p.containerMenu.slots.size(); slot++) { var menuSlot = p.containerMenu.slots.get(slot); ItemStack stack = menuSlot.getItem(); if (menuSlot.container == p.getInventory() && !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(wanted)) mc.gameMode.handleContainerInput(p.containerMenu.containerId, slot, 0, ContainerInput.QUICK_MOVE, p); } }
        if ("withdraw_item".equals(op) && p.containerMenu != p.inventoryMenu) { String wanted = action.get("item").getAsString(); for (int slot = 0; slot < p.containerMenu.slots.size(); slot++) { var menuSlot = p.containerMenu.slots.get(slot); ItemStack stack = menuSlot.getItem(); if (menuSlot.container != p.getInventory() && !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(wanted)) mc.gameMode.handleContainerInput(p.containerMenu.containerId, slot, 0, ContainerInput.QUICK_MOVE, p); } }
        if ("discard_item".equals(op)) discardItem(mc, p, action.get("item").getAsString(), action.has("count") ? action.get("count").getAsInt() : -1);
        if ("craft_item".equals(op)) beginCraft(mc, p, action.get("item").getAsString(), Math.max(1, action.has("count") ? action.get("count").getAsInt() : 1));
        if ("use_item".equals(op)) useInventoryItem(mc, p, action.get("item").getAsString());
        if ("place_block_nearby".equals(op)) beginPlaceBlockNearby(mc, p, action.get("item").getAsString());
        if ("close_screen".equals(op)) p.closeContainer();
    }
    private void discardItem(Minecraft mc, LocalPlayer player, String wanted, int requestedCount) {
        int remaining = requestedCount < 0 ? Integer.MAX_VALUE : requestedCount;
        for (int slot = 0; slot < player.containerMenu.slots.size() && remaining > 0; slot++) {
            var menuSlot = player.containerMenu.slots.get(slot); ItemStack stack = menuSlot.getItem();
            if (menuSlot.container != player.getInventory() || stack.isEmpty() || !itemId(stack).equals(wanted)) continue;
            if (remaining >= stack.getCount()) {
                mc.gameMode.handleContainerInput(player.containerMenu.containerId, slot, 1, ContainerInput.THROW, player);
                if (remaining != Integer.MAX_VALUE) remaining -= stack.getCount();
            } else {
                for (int amount = 0; amount < remaining; amount++) mc.gameMode.handleContainerInput(player.containerMenu.containerId, slot, 0, ContainerInput.THROW, player);
                remaining = 0;
            }
        }
    }
    private void beginCraft(Minecraft mc, LocalPlayer player, String wanted, int requestedCount) {
        pendingCraft = null;
        if (!(player.containerMenu instanceof AbstractCraftingMenu)) player.closeContainer();
        if (!(player.containerMenu instanceof AbstractCraftingMenu craftingMenu) || mc.level == null) return;
        var contents = new StackedItemContents(); player.getInventory().fillStackedContents(contents);
        var context = SlotDisplayContext.fromLevel(mc.level);
        RecipeDisplayEntry selected = null; int outputCount = 1;
        outer: for (var collection : player.getRecipeBook().getCollections()) for (var recipe : collection.getRecipes()) {
            if (!recipe.canCraft(contents) || !fitsCraftingGrid(recipe, craftingMenu)) continue;
            for (ItemStack result : recipe.resultItems(context)) if (!result.isEmpty() && itemId(result).equals(wanted)) {
                selected = recipe; outputCount = Math.max(1, result.getCount()); break outer;
            }
        }
        if (selected == null) return;
        int crafts = Math.max(1, (requestedCount + outputCount - 1) / outputCount);
        pendingCraft = new PendingCraft(wanted, selected.id(), craftingMenu.containerId, crafts, ticks, false, ticks + 30);
    }
    private boolean fitsCraftingGrid(RecipeDisplayEntry recipe, AbstractCraftingMenu menu) {
        if (recipe.display() instanceof ShapedCraftingRecipeDisplay shaped) return shaped.width() <= menu.getGridWidth() && shaped.height() <= menu.getGridHeight();
        if (recipe.display() instanceof ShapelessCraftingRecipeDisplay shapeless) return shapeless.ingredients().size() <= menu.getGridWidth() * menu.getGridHeight();
        return false;
    }
    private void processPendingCraft(Minecraft mc) {
        PendingCraft craft = pendingCraft; LocalPlayer player = mc.player;
        if (craft == null || player == null || mc.gameMode == null) return;
        if (!(player.containerMenu instanceof AbstractCraftingMenu menu) || menu.containerId != craft.containerId || craft.craftsRemaining <= 0 || ticks > craft.abortAtTick) { pendingCraft = null; return; }
        if (!craft.waitingForResult) {
            if (ticks < craft.nextActionTick) return;
            var contents = new StackedItemContents(); player.getInventory().fillStackedContents(contents);
            RecipeDisplayEntry current = null;
            outer: for (var collection : player.getRecipeBook().getCollections()) for (var recipe : collection.getRecipes()) if (recipe.id().equals(craft.recipeId)) { current = recipe; break outer; }
            if (current == null || !current.canCraft(contents)) { pendingCraft = null; return; }
            mc.gameMode.handlePlaceRecipe(menu.containerId, craft.recipeId, false);
            craft.waitingForResult = true; craft.nextActionTick = ticks + 2; craft.abortAtTick = ticks + 30; return;
        }
        if (ticks < craft.nextActionTick) return;
        ItemStack result = menu.getResultSlot().getItem();
        if (result.isEmpty() || !itemId(result).equals(craft.itemId)) return;
        int resultSlot = menu.slots.indexOf(menu.getResultSlot());
        if (resultSlot < 0) { pendingCraft = null; return; }
        mc.gameMode.handleContainerInput(menu.containerId, resultSlot, 0, ContainerInput.QUICK_MOVE, player);
        craft.craftsRemaining--; craft.waitingForResult = false; craft.nextActionTick = ticks + 3; craft.abortAtTick = ticks + 30;
        if (craft.craftsRemaining <= 0) pendingCraft = null;
    }
    private void useInventoryItem(Minecraft mc, LocalPlayer player, String wanted) {
        if (player.containerMenu != player.inventoryMenu) player.closeContainer();
        int hotbarSlot = -1, inventorySlot = -1;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot); if (stack.isEmpty() || !itemId(stack).equals(wanted)) continue;
            if (net.minecraft.world.entity.player.Inventory.isHotbarSlot(slot)) { hotbarSlot = slot; break; }
            if (inventorySlot < 0) inventorySlot = slot;
        }
        if (hotbarSlot >= 0) {
            player.getInventory().setSelectedSlot(hotbarSlot); mc.gameMode.useItem(player, InteractionHand.MAIN_HAND); player.swing(InteractionHand.MAIN_HAND); return;
        }
        if (inventorySlot < 0) return;
        int selected = player.getInventory().getSelectedSlot();
        for (int menuSlot = 0; menuSlot < player.inventoryMenu.slots.size(); menuSlot++) {
            var slot = player.inventoryMenu.slots.get(menuSlot);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventorySlot) {
                mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, selected, ContainerInput.SWAP, player);
                pendingUseItem = wanted; pendingUseAtTick = ticks + 3; return;
            }
        }
    }
    private void processPendingUse(Minecraft mc) {
        if (pendingUseItem == null || ticks < pendingUseAtTick || mc.player == null || mc.gameMode == null) return;
        String wanted = pendingUseItem; pendingUseItem = null;
        ItemStack selected = mc.player.getInventory().getSelectedItem();
        if (selected.isEmpty() || !itemId(selected).equals(wanted)) return;
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); mc.player.swing(InteractionHand.MAIN_HAND);
    }
    private void beginPlaceBlockNearby(Minecraft mc, LocalPlayer player, String wanted) {
        pendingPlace = null;
        if (player.containerMenu != player.inventoryMenu) player.closeContainer();
        int hotbarSlot = -1, inventorySlot = -1;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !itemId(stack).equals(wanted) || !(stack.getItem() instanceof BlockItem)) continue;
            if (net.minecraft.world.entity.player.Inventory.isHotbarSlot(slot)) { hotbarSlot = slot; break; }
            if (inventorySlot < 0) inventorySlot = slot;
        }
        if (hotbarSlot >= 0) {
            player.getInventory().setSelectedSlot(hotbarSlot);
            pendingPlace = new PendingPlace(wanted, ticks + 1);
            return;
        }
        if (inventorySlot < 0) return;
        int selected = player.getInventory().getSelectedSlot();
        for (int menuSlot = 0; menuSlot < player.inventoryMenu.slots.size(); menuSlot++) {
            var slot = player.inventoryMenu.slots.get(menuSlot);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventorySlot) {
                mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, selected, ContainerInput.SWAP, player);
                pendingPlace = new PendingPlace(wanted, ticks + 3);
                return;
            }
        }
    }
    private void processPendingPlace(Minecraft mc) {
        PendingPlace place = pendingPlace;
        if (place == null || ticks < place.atTick || mc.player == null || mc.level == null || mc.gameMode == null) return;
        pendingPlace = null;
        LocalPlayer player = mc.player;
        ItemStack selected = player.getInventory().getSelectedItem();
        if (selected.isEmpty() || !itemId(selected).equals(place.itemId) || !(selected.getItem() instanceof BlockItem)) return;
        BlockPos target = findNearbyPlacement(mc, player);
        if (target == null) return;
        BlockPos support = target.below();
        Vec3 hit = Vec3.atCenterOf(support).add(0, 0.5, 0);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, support, false));
        player.swing(InteractionHand.MAIN_HAND);
    }
    private BlockPos findNearbyPlacement(Minecraft mc, LocalPlayer player) {
        BlockPos center = player.blockPosition();
        for (int radius = 1; radius <= 3; radius++) {
            for (int dy = 0; dy <= 1; dy++) for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                BlockPos target = center.offset(dx, dy, dz), support = target.below();
                if (!mc.level.getBlockState(target).canBeReplaced()) continue;
                if (mc.level.getBlockState(support).isAir() || mc.level.getBlockState(support).canBeReplaced()) continue;
                if (player.getBoundingBox().intersects(new AABB(target))) continue;
                if (player.distanceToSqr(Vec3.atCenterOf(target)) > 25.0) continue;
                return target;
            }
        }
        return null;
    }
    private String itemId(ItemStack stack) { return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    private static final class PendingCraft {
        private final String itemId; private final RecipeDisplayId recipeId; private final int containerId;
        private int craftsRemaining; private int nextActionTick; private boolean waitingForResult; private int abortAtTick;
        private PendingCraft(String itemId, RecipeDisplayId recipeId, int containerId, int craftsRemaining, int nextActionTick, boolean waitingForResult, int abortAtTick) {
            this.itemId = itemId; this.recipeId = recipeId; this.containerId = containerId; this.craftsRemaining = craftsRemaining;
            this.nextActionTick = nextActionTick; this.waitingForResult = waitingForResult; this.abortAtTick = abortAtTick;
        }
    }
    private record PendingPlace(String itemId, int atTick) { }
    private void sendStatus(Minecraft client) { LocalPlayer p = client.player; if (p == null || out == null || client.level == null) return; try {
        long snapshotTime = System.currentTimeMillis(); JsonObject o = new JsonObject(); o.addProperty("type", "status"); o.addProperty("snapshotTime", snapshotTime); o.addProperty("playerName", p.getName().getString()); o.addProperty("playerUuid", p.getUUID().toString()); o.addProperty("dimension", client.level.dimension().identifier().toString()); o.addProperty("health", p.getHealth()); o.addProperty("maxHealth", p.getMaxHealth()); o.addProperty("food", p.getFoodData().getFoodLevel()); o.addProperty("saturation", p.getFoodData().getSaturationLevel()); o.addProperty("air", p.getAirSupply());
        BaritoneState baritone = baritoneState(); if (baritone.available && previousBaritoneWorking && !baritone.working) { baritoneWorkSequence++; baritoneWorkFinishedAt = snapshotTime; } if (baritone.available) previousBaritoneWorking = baritone.working; o.addProperty("baritoneLoaded", baritone.loaded); o.addProperty("baritoneStatusAvailable", baritone.available); o.addProperty("baritoneStatusError", baritone.error); o.addProperty("baritoneWorking", baritone.working); o.add("baritoneProcesses", baritone.activeProcesses); o.add("baritonePath", currentBaritonePath(p)); o.addProperty("baritoneWorkSequence", baritoneWorkSequence); o.addProperty("baritoneWorkFinishedAt", baritoneWorkFinishedAt);
        BlockPos pos = p.blockPosition(); o.addProperty("x", pos.getX()); o.addProperty("y", pos.getY()); o.addProperty("z", pos.getZ()); JsonArray inv = new JsonArray();
        for (int slot = 0; slot < p.getInventory().getContainerSize(); slot++) { ItemStack stack = p.getInventory().getItem(slot); if (!stack.isEmpty()) inv.add(item(stack, slot)); } o.add("inventory", inv);
        JsonArray equipment = new JsonArray(); for (EquipmentSlot slot : PLAYER_EQUIPMENT) { ItemStack stack = p.getItemBySlot(slot); if (!stack.isEmpty()) { JsonObject i = item(stack, slot.getIndex()); i.addProperty("equipmentSlot", slot.getName()); equipment.add(i); } } o.add("equipment", equipment);
        o.addProperty("openContainer", p.containerMenu.getClass().getSimpleName()); if (p.containerMenu instanceof AbstractFurnaceMenu furnaceMenu) { o.addProperty("furnaceLit", furnaceMenu.isLit()); o.addProperty("furnaceBurnProgress", furnaceMenu.getBurnProgress()); o.addProperty("furnaceLitProgress", furnaceMenu.getLitProgress()); } else { o.addProperty("furnaceLit", false); o.addProperty("furnaceBurnProgress", 0); o.addProperty("furnaceLitProgress", 0); } JsonArray container = new JsonArray(); for (int slot = 0; slot < p.containerMenu.slots.size(); slot++) { var menuSlot = p.containerMenu.slots.get(slot); ItemStack stack = menuSlot.getItem(); JsonObject i = item(stack, slot); i.addProperty("containerSlot", menuSlot.getContainerSlot()); i.addProperty("section", menuSlot.container == p.getInventory() ? "player" : "block_container"); container.add(i); } o.add("containerSlots", container);
        if (ticks % 40 == 0 || cachedFunctionalBlocks.isEmpty()) updateBlockTelemetry(client, pos, snapshotTime); o.add("functionalBlocks", cachedFunctionalBlocks.deepCopy()); o.add("functionalBlockContexts", cachedFunctionalBlockContexts.deepCopy()); JsonArray changes = new JsonArray(); recentBlockChanges.forEach(change -> changes.add(change.deepCopy())); o.add("blockChanges", changes); o.addProperty("blockChangeSequence", blockChangeSequence); o.add("nearbyBlocks", nearby(client, pos));
        JsonArray players = new JsonArray();
        if (client.getConnection() != null) for (var info : client.getConnection().getOnlinePlayers()) {
            var profile = info.getProfile(); var tracked = client.level.players().stream().filter(other -> other.getUUID().equals(profile.id())).findFirst().orElse(null);
            JsonObject observed = new JsonObject(); observed.addProperty("uuid", profile.id().toString()); observed.addProperty("name", profile.name());
            observed.addProperty("latency", info.getLatency()); observed.addProperty("gameMode", info.getGameMode().getName()); observed.addProperty("positionAvailable", tracked != null);
            observed.addProperty("dimension", client.level.dimension().identifier().toString());
            JsonObject avatar = playerSkinAvatar(client, info);
            if (avatar != null) {
                observed.addProperty("skinId", stringValue(avatar, "skinId")); observed.addProperty("skinWidth", intValue(avatar, "skinWidth", 8)); observed.addProperty("skinHeight", intValue(avatar, "skinHeight", 8)); observed.addProperty("skinPixels", stringValue(avatar, "skinPixels"));
            }
            if (tracked != null) {
                observed.addProperty("x", tracked.getX()); observed.addProperty("y", tracked.getY()); observed.addProperty("z", tracked.getZ());
                observed.addProperty("yaw", tracked.getYRot()); observed.addProperty("pitch", tracked.getXRot()); observed.addProperty("health", tracked.getHealth()); observed.addProperty("maxHealth", tracked.getMaxHealth());
            }
            players.add(observed);
        }
        o.add("players", players);
        o.addProperty("clusterPrimary", primaryInstance); o.addProperty("clusterPossessionTarget", possessionTarget); if (selectedPossessionTarget == null) o.add("selectedPossessionTarget", com.google.gson.JsonNull.INSTANCE); else o.addProperty("selectedPossessionTarget", selectedPossessionTarget); o.addProperty("remoteStateSequence", remoteStateSequence); o.addProperty("remoteChunkSequence", remoteChunkSequence); o.addProperty("remoteChunkSentAt", lastChunkSentAt); o.addProperty("remoteChunkReceivedAt", lastChunkReceivedAt); o.addProperty("remoteChunkError", remoteChunkError);
        String snapshot = o.toString(); sender.execute(() -> { try { write(snapshot); } catch (IOException ignored) { } });
    } catch (Exception ex) { long now = System.currentTimeMillis(); if (now - lastStatusErrorAt >= 5000) { lastStatusErrorAt = now; System.err.println("[Baritone AI Cluster] Failed to build status snapshot"); ex.printStackTrace(); } } }
    private JsonObject playerSkinAvatar(Minecraft client, net.minecraft.client.multiplayer.PlayerInfo info) {
        NativeImage image = null;
        boolean closeImage = false;
        try {
            Identifier texturePath = info.getSkin().body().texturePath(); String skinId = texturePath.toString();
            if (playerAvatarCache.containsKey(skinId)) return playerAvatarCache.get(skinId);
            var texture = client.getTextureManager().getTexture(texturePath);
            if (texture instanceof DynamicTexture dynamicTexture) {
                image = dynamicTexture.getPixels();
            } else {
                var resource = client.getResourceManager().getResource(texturePath);
                if (resource.isEmpty()) return null;
                try (InputStream stream = resource.get().open()) { image = NativeImage.read(stream); }
                closeImage = true;
            }
            if (image == null || image.getWidth() < 48 || image.getHeight() < 16) return null;
            byte[] bgra = new byte[8 * 8 * 4];
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
                int face = image.getPixel(8 + x, 8 + y); int hat = image.getPixel(40 + x, 8 + y);
                int argb = blendArgb(face, hat), offset = (y * 8 + x) * 4;
                bgra[offset] = (byte)(argb & 0xFF); bgra[offset + 1] = (byte)((argb >>> 8) & 0xFF);
                bgra[offset + 2] = (byte)((argb >>> 16) & 0xFF); bgra[offset + 3] = (byte)((argb >>> 24) & 0xFF);
            }
            JsonObject avatar = new JsonObject(); avatar.addProperty("skinId", skinId); avatar.addProperty("skinWidth", 8); avatar.addProperty("skinHeight", 8);
            avatar.addProperty("skinPixels", Base64.getEncoder().encodeToString(bgra)); playerAvatarCache.put(skinId, avatar); return avatar;
        } catch (IOException | RuntimeException ignored) { return null; }
        finally { if (closeImage && image != null) image.close(); }
    }
    private JsonObject item(ItemStack stack, int slot) { JsonObject i = new JsonObject(); i.addProperty("slot", slot); i.addProperty("item", stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); i.addProperty("count", stack.getCount()); i.addProperty("maxCount", stack.isEmpty() ? 0 : stack.getMaxStackSize()); return i; }
    private BaritoneState baritoneState() {
        boolean loaded = FabricLoader.getInstance().isModLoaded("baritone"); JsonArray active = new JsonArray(); if (!loaded) return new BaritoneState(false, false, false, active, "Fabric Loader 未发现 mod id: baritone");
        try {
            try { Class.forName("baritone.api.BaritoneAPI"); return standardBaritoneState(active); }
            catch (ClassNotFoundException ignored) { return optimizedBaritoneState(active); }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            Throwable root = ex;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            String message = root.getMessage();
            String error = root.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message);
            long now = System.currentTimeMillis();
            if (now - lastBaritoneStatusErrorAt >= 5000) { lastBaritoneStatusErrorAt = now; System.err.println("[Baritone AI Cluster] Baritone status unavailable: " + error); ex.printStackTrace(); }
            return new BaritoneState(true, false, previousBaritoneWorking, active, error);
        }
    }
    private BaritoneState standardBaritoneState(JsonArray active) throws ReflectiveOperationException {
        Class<?> api = Class.forName("baritone.api.BaritoneAPI");
        Object provider = api.getMethod("getProvider").invoke(null);
        Class<?> providerApi = Class.forName("baritone.api.IBaritoneProvider");
        Object baritone = providerApi.getMethod("getPrimaryBaritone").invoke(provider);
        if (baritone == null) throw new IllegalStateException("Baritone primary instance is null");
        Class<?> baritoneApi = Class.forName("baritone.api.IBaritone");
        Class<?> processApi = Class.forName("baritone.api.process.IBaritoneProcess");
        Method isActive = processApi.getMethod("isActive");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String[] processes = { "getMineProcess", "getCustomGoalProcess", "getGetToBlockProcess", "getBuilderProcess", "getExploreProcess", "getFarmProcess", "getFollowProcess" };
        for (String methodName : processes) {
            Object process = baritoneApi.getMethod(methodName).invoke(baritone);
            if (Boolean.TRUE.equals(isActive.invoke(process))) names.add(methodName.substring(3, methodName.length() - 7));
        }
        Object pathing = baritoneApi.getMethod("getPathingBehavior").invoke(baritone);
        if (Boolean.TRUE.equals(Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("isPathing").invoke(pathing))) names.add("Pathing");
        names.forEach(active::add);
        return new BaritoneState(true, true, !names.isEmpty(), active, "");
    }
    private BaritoneState optimizedBaritoneState(JsonArray active) throws ReflectiveOperationException {
        // The optimized standalone jar keeps IBaritoneProvider but renames the rest of the public API.
        Class<?> providerApi = Class.forName("baritone.api.IBaritoneProvider");
        Class<?> apiHolder = Class.forName("baritone.c");
        Method getProvider = Arrays.stream(apiHolder.getDeclaredMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0 && m.getReturnType() == providerApi)
                .findFirst().orElseThrow(() -> new NoSuchMethodException("Optimized Baritone provider accessor not found"));
        Object provider = getProvider.invoke(null);
        Method getPrimary = Arrays.stream(providerApi.getMethods())
                .filter(m -> m.getParameterCount() == 0 && m.getReturnType().getName().equals("baritone.d"))
                .findFirst().orElseThrow(() -> new NoSuchMethodException("Optimized primary Baritone accessor not found"));
        Object baritone = getPrimary.invoke(provider);
        if (baritone == null) throw new IllegalStateException("Optimized Baritone primary instance is null");

        Class<?> baritoneApi = Class.forName("baritone.d");
        Class<?> processApi = Class.forName("baritone.co");
        Method isActive = Arrays.stream(processApi.getMethods())
                .filter(m -> m.getName().equals("a") && m.getParameterCount() == 0 && m.getReturnType() == boolean.class)
                .findFirst().orElseThrow(() -> new NoSuchMethodException("Optimized process isActive method not found"));
        Map<String, String> processNames = Map.of(
                "baritone.kb", "Mine", "baritone.jq", "CustomGoal", "baritone.jy", "GetToBlock",
                "baritone.jj", "Builder", "baritone.jr", "Explore", "baritone.jt", "Farm", "baritone.jx", "Follow");
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Method method : baritoneApi.getMethods()) {
            if (method.getParameterCount() != 0 || !processApi.isAssignableFrom(method.getReturnType())) continue;
            Object process = method.invoke(baritone);
            if (process != null && Boolean.TRUE.equals(isActive.invoke(process))) names.add(processNames.getOrDefault(process.getClass().getName(), "Process:" + process.getClass().getSimpleName()));
        }

        Class<?> pathingApi = Class.forName("baritone.h");
        Method getPathing = Arrays.stream(baritoneApi.getMethods())
                .filter(m -> m.getParameterCount() == 0 && pathingApi.isAssignableFrom(m.getReturnType()))
                .findFirst().orElseThrow(() -> new NoSuchMethodException("Optimized pathing behavior accessor not found"));
        Object pathing = getPathing.invoke(baritone);
        Method isPathing = Arrays.stream(pathingApi.getMethods())
                .filter(m -> m.getName().equals("a") && m.getParameterCount() == 0 && m.getReturnType() == boolean.class)
                .findFirst().orElseThrow(() -> new NoSuchMethodException("Optimized isPathing method not found"));
        if (pathing != null && Boolean.TRUE.equals(isPathing.invoke(pathing))) names.add("Pathing");
        names.forEach(active::add);
        return new BaritoneState(true, true, !names.isEmpty(), active, "");
    }
    private JsonArray currentBaritonePath(LocalPlayer player) {
        JsonArray result = new JsonArray();
        if (!FabricLoader.getInstance().isModLoaded("baritone")) return result;
        try {
            Object positions;
            try { Class.forName("baritone.api.BaritoneAPI"); positions = standardBaritonePathPositions(); }
            catch (ClassNotFoundException ignored) { positions = optimizedBaritonePathPositions(); }
            if (!(positions instanceof List<?> list) || list.isEmpty()) return result;
            BlockPos playerPos = player.blockPosition(); int nearest = 0; long nearestDistance = Long.MAX_VALUE;
            int inspected = Math.min(list.size(), 8192);
            for (int index = 0; index < inspected; index++) {
                BlockPos point = pathPoint(list.get(index)); if (point == null) continue;
                long dx = point.getX() - playerPos.getX(), dy = point.getY() - playerPos.getY(), dz = point.getZ() - playerPos.getZ();
                long distance = dx * dx + dy * dy + dz * dz;
                if (distance < nearestDistance) { nearestDistance = distance; nearest = index; }
            }
            for (int index = Math.max(0, nearest - 1); index < list.size() && result.size() < 256; index++) {
                BlockPos point = pathPoint(list.get(index)); if (point == null) continue;
                JsonObject node = new JsonObject(); node.addProperty("x", point.getX()); node.addProperty("y", point.getY()); node.addProperty("z", point.getZ()); result.add(node);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            long now = System.currentTimeMillis();
            if (now - lastBaritonePathErrorAt >= 5000) {
                lastBaritonePathErrorAt = now;
                System.err.println("[Baritone AI Cluster] Failed to read Baritone route: " + ex);
            }
        }
        return result;
    }
    private BlockPos pathPoint(Object value) throws ReflectiveOperationException {
        if (value instanceof BlockPos point) return point;
        if (value == null) return null;
        for (Method method : value.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && BlockPos.class.isAssignableFrom(method.getReturnType())) {
                Object result = method.invoke(value);
                if (result instanceof BlockPos point) return point;
            }
        }
        return null;
    }
    private Object standardBaritonePathPositions() throws ReflectiveOperationException {
        Class<?> api = Class.forName("baritone.api.BaritoneAPI");
        Object provider = api.getMethod("getProvider").invoke(null);
        Object baritone = Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
        if (baritone == null) return List.of();
        Object pathing = Class.forName("baritone.api.IBaritone").getMethod("getPathingBehavior").invoke(baritone);
        Object executor = Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("getCurrent").invoke(pathing);
        if (executor instanceof java.util.Optional<?> optional) executor = optional.orElse(null);
        if (executor == null) return List.of();
        Object path = Class.forName("baritone.api.pathing.path.IPathExecutor").getMethod("getPath").invoke(executor);
        if (path == null) return List.of();
        return Class.forName("baritone.api.pathing.calc.IPath").getMethod("positions").invoke(path);
    }
    private Object optimizedBaritonePathPositions() throws ReflectiveOperationException {
        Class<?> providerApi = Class.forName("baritone.api.IBaritoneProvider"); Class<?> apiHolder = Class.forName("baritone.c");
        Method getProvider = Arrays.stream(apiHolder.getDeclaredMethods()).filter(m -> Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0 && m.getReturnType() == providerApi).findFirst().orElseThrow();
        Object provider = getProvider.invoke(null);
        Method getPrimary = Arrays.stream(providerApi.getMethods()).filter(m -> m.getParameterCount() == 0 && m.getReturnType().getName().equals("baritone.d")).findFirst().orElseThrow();
        Object baritone = getPrimary.invoke(provider); if (baritone == null) return List.of();
        Class<?> pathingApi = Class.forName("baritone.h");
        Method getPathing = Arrays.stream(Class.forName("baritone.d").getMethods()).filter(m -> m.getParameterCount() == 0 && pathingApi.isAssignableFrom(m.getReturnType())).findFirst().orElseThrow();
        Object pathing = getPathing.invoke(baritone); if (pathing == null) return List.of();
        Class<?> executorApi = Class.forName("baritone.jc");
        Method getCurrent = Arrays.stream(pathingApi.getMethods())
                .filter(m -> m.getParameterCount() == 0 && m.getReturnType() == executorApi)
                .findFirst().orElseThrow(() -> new NoSuchMethodException("Optimized current path accessor not found"));
        Object executor = getCurrent.invoke(pathing);
        if (executor == null) return List.of();
        Class<?> pathApi = Class.forName("baritone.bw");
        Method getPath = Arrays.stream(executorApi.getMethods())
                .filter(m -> m.getParameterCount() == 0 && m.getReturnType() == pathApi)
                .findFirst().orElseThrow(() -> new NoSuchMethodException("Optimized path accessor not found"));
        Object path = getPath.invoke(executor);
        if (path == null) return List.of();
        Method positions = Arrays.stream(pathApi.getMethods())
                .filter(m -> m.getName().equals("b") && m.getParameterCount() == 0 && List.class.isAssignableFrom(m.getReturnType()))
                .findFirst().orElseThrow(() -> new NoSuchMethodException("Optimized path positions accessor not found"));
        return positions.invoke(path);
    }
    private JsonArray nearby(Minecraft client, BlockPos center) { JsonArray blocks = new JsonArray(); for (BlockPos p : BlockPos.betweenClosed(center.offset(-4,-2,-4), center.offset(4,2,4))) if (!client.level.getBlockState(p).isAir()) { JsonObject b = block(client, p); blocks.add(b); if (blocks.size() >= 64) break; } return blocks; }
    private void updateBlockTelemetry(Minecraft client, BlockPos center, long timestamp) {
        Map<ObservedBlockPos, String> current = new HashMap<>(); JsonArray functional = new JsonArray(); List<BlockPos> functionalPositions = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-12,-6,-12), center.offset(12,6,12))) {
            String id = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(p).getBlock()).toString(); current.put(new ObservedBlockPos(p.getX(), p.getY(), p.getZ()), id);
            if ((client.level.getBlockEntity(p) != null || isFunctional(id)) && functional.size() < 256) { functional.add(block(client, p)); functionalPositions.add(p.immutable()); }
        }
        if (!previousScannedBlocks.isEmpty()) for (var entry : current.entrySet()) { String before = previousScannedBlocks.get(entry.getKey()); if (before != null && !before.equals(entry.getValue())) recordBlockChange(entry.getKey(), before, entry.getValue(), timestamp); }
        previousScannedBlocks = current; cachedFunctionalBlocks = functional;
        JsonArray contexts = new JsonArray();
        for (BlockPos centerBlock : functionalPositions) {
            JsonObject context = block(client, centerBlock); JsonArray adjacent = new JsonArray();
            for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos neighborPos = centerBlock.offset(dx, dy, dz); JsonObject neighbor = block(client, neighborPos); neighbor.addProperty("dx", dx); neighbor.addProperty("dy", dy); neighbor.addProperty("dz", dz); adjacent.add(neighbor);
            }
            context.add("adjacentBlocks", adjacent); contexts.add(context);
        }
        cachedFunctionalBlockContexts = contexts;
    }
    private void recordBlockChange(ObservedBlockPos pos, String before, String after, long timestamp) {
        JsonObject change = new JsonObject(); change.addProperty("sequence", ++blockChangeSequence); change.addProperty("timestamp", timestamp); change.addProperty("x", pos.x); change.addProperty("y", pos.y); change.addProperty("z", pos.z); change.addProperty("before", before); change.addProperty("after", after); recentBlockChanges.addLast(change); while (recentBlockChanges.size() > 128) recentBlockChanges.removeFirst();
    }
    private boolean isFunctional(String id) { return id.contains("crafting_table") || id.contains("furnace") || id.contains("chest") || id.contains("barrel") || id.contains("anvil") || id.contains("bed") || id.contains("brewing_stand") || id.contains("enchanting_table") || id.contains("grindstone") || id.contains("stonecutter") || id.contains("smithing_table") || id.contains("loom") || id.contains("cartography_table") || id.contains("composter") || id.contains("beacon") || id.contains("portal"); }
    private JsonObject block(Minecraft client, BlockPos p) { JsonObject b = new JsonObject(); b.addProperty("block", BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(p).getBlock()).toString()); b.addProperty("x",p.getX()); b.addProperty("y",p.getY()); b.addProperty("z",p.getZ()); return b; }
    private synchronized void write(String line) throws IOException { if (out != null) { out.write(line); out.newLine(); out.flush(); } }
    private record RemoteInput(long sequence, boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint, boolean attack, boolean use, boolean pick, boolean inventoryPulse, boolean swapPulse, boolean dropPulse, int hotbarSlot, float yaw, float pitch, float mouseX, float mouseY, boolean leftClickPulse, boolean rightClickPulse) { }
    private static final class RemoteClientInput extends ClientInput {
        private volatile RemoteInput state;
        void update(RemoteInput input) { state = input; tick(); }
        @Override public void tick() {
            RemoteInput input = state;
            if (input == null) { keyPresses = Input.EMPTY; moveVector = Vec2.ZERO; return; }
            keyPresses = new Input(input.forward, input.back, input.left, input.right, input.jump, input.sneak, input.sprint);
            float forward = impulse(input.forward, input.back), strafe = impulse(input.left, input.right);
            moveVector = new Vec2(strafe, forward).normalized();
        }
        private static float impulse(boolean positive, boolean negative) { return positive == negative ? 0.0f : positive ? 1.0f : -1.0f; }
    }
    private record ObservedBlockPos(int x, int y, int z) { }
    private record MapTextureLayer(SpriteContents contents, int tint) { }
    private record BaritoneState(boolean loaded, boolean available, boolean working, JsonArray activeProcesses, String error) { }

    boolean isPrimaryInstance() { return primaryInstance; }
    ClusterDisplaySettings displaySettings() { return clusterDisplaySettings; }
    ClusterTaskProgress taskProgress() { return clusterTaskProgress; }
    List<RemoteClusterPlayer> overlayPlayers() { return clusterPlayers; }
    List<RemoteBaritoneRoute> overlayRoutes() { return clusterRoutes; }
    String localInstanceId() { return INSTANCE; }
}

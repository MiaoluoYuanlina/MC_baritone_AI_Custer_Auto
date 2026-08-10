using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace BaritoneClusterServer;
public sealed class ClusterHub : IAsyncDisposable
{
    private readonly ConcurrentDictionary<string, ClientConnection> clients = new(); private readonly string token = Environment.GetEnvironmentVariable("BARITONE_CLUSTER_TOKEN") ?? "change-me"; private readonly TcpListener listener = new(IPAddress.Any, 25570); private CancellationTokenSource? cancel;
    private readonly object clusterStateGate = new();
    private readonly object mapGate = new();
    private readonly Dictionary<MapCellKey, MapBlockCell> mapBlocks = [];
    private readonly Dictionary<string, MapMaterial> mapMaterials = [];
    private string? primaryInstanceId;
    private bool primaryAiControlAllowed;
    private string? possessionTargetId;
    private long clusterStateVersion = 1;
    private bool allowBaritoneBreak = true;
    private bool showControllablePlayerBoxes;
    private bool showBaritoneRoutes;
    private bool showAiRepliesInChat;
    private bool showTaskProgress = true;
    private List<string> blocksToDisallowBreaking = [];
    private List<string> blocksToAvoidBreaking = ["minecraft:crafting_table", "minecraft:furnace", "minecraft:chest", "minecraft:trapped_chest"];
    private string? preferredPrimaryInstanceId;
    private string? preferredPrimaryPlayerUuid;
    private string? preferredPrimaryPlayerName;
    private CollectionTaskPlan? activeCollectionPlan;
    private AgentExecutionPlan? activeAgentPlan;
    private static readonly Regex BlockIdPattern = new("^[a-z0-9_.-]+:[a-z0-9_./-]+$", RegexOptions.Compiled | RegexOptions.CultureInvariant);
    public event Action? Changed;
    public event Action<BaritoneWorkFinishedNotice>? BaritoneWorkFinished;
    public ClusterHub()
    {
        var saved = AppSettingsStore.Load().Cluster;
        preferredPrimaryInstanceId = saved.PrimaryInstanceId;
        preferredPrimaryPlayerUuid = saved.PrimaryPlayerUuid;
        preferredPrimaryPlayerName = saved.PrimaryPlayerName;
        primaryAiControlAllowed = saved.PrimaryAiControlAllowed;
        allowBaritoneBreak = saved.AllowBaritoneBreak;
        showControllablePlayerBoxes = saved.ShowControllablePlayerBoxes;
        showBaritoneRoutes = saved.ShowBaritoneRoutes;
        showAiRepliesInChat = saved.ShowAiRepliesInChat;
        showTaskProgress = saved.ShowTaskProgress;
        blocksToDisallowBreaking = [.. saved.BlocksToDisallowBreaking];
        blocksToAvoidBreaking = [.. saved.BlocksToAvoidBreaking];
    }
    private void SavePersistentSettings()
    {
        SavedClusterSettings saved;
        lock (clusterStateGate)
        {
            saved = new()
            {
                PrimaryInstanceId = preferredPrimaryInstanceId,
                PrimaryPlayerUuid = preferredPrimaryPlayerUuid,
                PrimaryPlayerName = preferredPrimaryPlayerName,
                PrimaryAiControlAllowed = primaryAiControlAllowed,
                AllowBaritoneBreak = allowBaritoneBreak,
                ShowControllablePlayerBoxes = showControllablePlayerBoxes,
                ShowBaritoneRoutes = showBaritoneRoutes,
                ShowAiRepliesInChat = showAiRepliesInChat,
                ShowTaskProgress = showTaskProgress,
                BlocksToDisallowBreaking = [.. blocksToDisallowBreaking],
                BlocksToAvoidBreaking = [.. blocksToAvoidBreaking]
            };
        }
        AppSettingsStore.SaveCluster(saved);
    }
    public Task StartAsync() { cancel = new(); listener.Start(); _ = AcceptLoop(cancel.Token); return Task.CompletedTask; }
    public IReadOnlyList<ClientConnection> Snapshot() => clients.Values.OrderBy(x => x.InstanceId).ToList();
    public WorldMapSnapshot MapSnapshot(string? requestedDimension = null)
    {
        var connections = Snapshot();
        List<string> dimensions;
        List<MapBlockCell> blocks;
        List<MapMaterial> materials;
        string dimension;
        lock (mapGate)
        {
            dimensions = mapBlocks.Keys.Select(x => x.Dimension)
                .Concat(connections.Select(x => x.LastStatus?.Dimension).OfType<string>().Where(x => !string.IsNullOrWhiteSpace(x)))
                .Distinct(StringComparer.Ordinal).OrderBy(x => x).ToList();
            dimension = !string.IsNullOrWhiteSpace(requestedDimension) && dimensions.Contains(requestedDimension, StringComparer.Ordinal)
                ? requestedDimension
                : connections.Where(x => x.LastStatus is not null).OrderByDescending(x => x.LastStatusReceivedAt).Select(x => x.LastStatus!.Dimension).FirstOrDefault(x => !string.IsNullOrWhiteSpace(x))
                  ?? dimensions.FirstOrDefault() ?? "minecraft:overworld";
            blocks = mapBlocks.Where(x => x.Key.Dimension == dimension).Select(x => x.Value).ToList();
            var materialIds = blocks.Select(x => x.Material).ToHashSet(StringComparer.Ordinal);
            materials = mapMaterials.Where(x => materialIds.Contains(x.Key)).Select(x => x.Value).ToList();
        }
        var controllableById = connections.Where(x => !string.IsNullOrWhiteSpace(x.LastStatus?.PlayerUuid)).GroupBy(x => x.LastStatus!.PlayerUuid!, StringComparer.OrdinalIgnoreCase).ToDictionary(x => x.Key, x => x.First().InstanceId, StringComparer.OrdinalIgnoreCase);
        var controllableByName = connections.Where(x => !string.IsNullOrWhiteSpace(x.LastStatus?.PlayerName)).GroupBy(x => x.LastStatus!.PlayerName, StringComparer.OrdinalIgnoreCase).ToDictionary(x => x.Key, x => x.First().InstanceId, StringComparer.OrdinalIgnoreCase);
        var observed = new Dictionary<string, (MapPlayerMarker Player, DateTimeOffset Seen)>(StringComparer.OrdinalIgnoreCase);
        foreach (var connection in connections.Where(x => x.LastStatus is not null && DateTimeOffset.UtcNow - x.LastStatusReceivedAt < TimeSpan.FromSeconds(10)))
        {
            var status = connection.LastStatus!;
            var reports = (status.Players ?? []).ToList();
            if (!string.IsNullOrWhiteSpace(status.PlayerName))
            {
                var selfIndex = reports.FindIndex(report =>
                    (!string.IsNullOrWhiteSpace(status.PlayerUuid) && string.Equals(report.Uuid, status.PlayerUuid, StringComparison.OrdinalIgnoreCase)) ||
                    string.Equals(report.Name, status.PlayerName, StringComparison.OrdinalIgnoreCase));
                if (selfIndex >= 0)
                {
                    var self = reports[selfIndex];
                    reports[selfIndex] = self with
                    {
                        Uuid = string.IsNullOrWhiteSpace(self.Uuid) ? status.PlayerUuid ?? "" : self.Uuid,
                        Name = status.PlayerName,
                        Dimension = status.Dimension ?? self.Dimension ?? "minecraft:overworld",
                        PositionAvailable = true,
                        X = status.X, Y = status.Y, Z = status.Z,
                        Health = status.Health, MaxHealth = status.MaxHealth
                    };
                }
                else
                {
                    reports.Add(new(status.PlayerUuid ?? "", status.PlayerName, status.Dimension ?? "minecraft:overworld", true,
                        status.X, status.Y, status.Z, 0, 0, status.Health, status.MaxHealth, 0, "", null, 0, 0, null));
                }
            }
            foreach (var report in reports)
            {
                var key = string.IsNullOrWhiteSpace(report.Uuid) ? "name:" + report.Name : report.Uuid;
                var reportDimension = string.IsNullOrWhiteSpace(report.Dimension) ? status.Dimension ?? "minecraft:overworld" : report.Dimension;
                var instanceId = controllableById.GetValueOrDefault(report.Uuid) ?? controllableByName.GetValueOrDefault(report.Name);
                var marker = new MapPlayerMarker(report.Uuid, report.Name, reportDimension, report.PositionAvailable,
                    report.X, report.Y, report.Z, report.Yaw, report.Pitch, report.Health, report.MaxHealth, report.Latency, report.GameMode,
                    instanceId is not null, instanceId, report.SkinId, report.SkinWidth, report.SkinHeight, report.SkinPixels);
                if (!observed.TryGetValue(key, out var previous))
                {
                    observed[key] = (marker, connection.LastStatusReceivedAt);
                }
                else if ((!previous.Player.PositionAvailable && marker.PositionAvailable) || connection.LastStatusReceivedAt > previous.Seen)
                {
                    if ((string.IsNullOrWhiteSpace(marker.SkinId) || marker.SkinPixels is null) &&
                        !string.IsNullOrWhiteSpace(previous.Player.SkinId) && previous.Player.SkinPixels is not null)
                        marker = marker with { SkinId = previous.Player.SkinId, SkinWidth = previous.Player.SkinWidth, SkinHeight = previous.Player.SkinHeight, SkinPixels = previous.Player.SkinPixels };
                    observed[key] = (marker, connection.LastStatusReceivedAt);
                }
            }
        }
        return new WorldMapSnapshot(dimension, dimensions, blocks, materials,
            observed.Values.Select(x => x.Player).OrderByDescending(x => x.IsControllable).ThenBy(x => x.Name).ToList());
    }
    public IEnumerable<object> InstanceSummaries() => Snapshot().Select(x => new
    {
        x.InstanceId, x.IsPrimary, x.AiControlAllowed, x.IsPossessionTarget,
        x.ExpectedClusterStateVersion, x.ClusterStateAckVersion, x.ClientReportsPrimary,
        receivedAt = x.LastStatusReceivedAt,
        ageMilliseconds = x.LastStatusReceivedAt == default ? -1 : (long)(DateTimeOffset.UtcNow - x.LastStatusReceivedAt).TotalMilliseconds,
        playerName = x.LastStatus?.PlayerName, health = x.LastStatus?.Health, food = x.LastStatus?.Food,
        x = x.LastStatus?.X, y = x.LastStatus?.Y, z = x.LastStatus?.Z,
        baritoneLoaded = x.LastStatus?.BaritoneLoaded, baritoneStatusAvailable = x.LastStatus?.BaritoneStatusAvailable,
        baritoneWorking = x.LastStatus?.BaritoneWorking, baritoneProcesses = x.LastStatus?.BaritoneProcesses,
        blockChangeSequence = x.LastStatus?.BlockChangeSequence,
        clusterPrimary = x.LastStatus?.ClusterPrimary, clusterPossessionTarget = x.LastStatus?.ClusterPossessionTarget,
        remoteStateSequence = x.LastStatus?.RemoteStateSequence, remoteChunkSequence = x.LastStatus?.RemoteChunkSequence,
        remoteChunkSentAt = x.LastStatus?.RemoteChunkSentAt, remoteChunkReceivedAt = x.LastRemoteChunkReceivedAt,
        remoteChunkError = x.LastStatus?.RemoteChunkError
    });
    public object AgentContextSnapshot()
    {
        var now = DateTimeOffset.UtcNow;
        var snapshot = Snapshot();
        var instances = snapshot.Select(client =>
        {
            var status = client.LastStatus;
            var inventory = status?.Inventory?
                .Where(item => item.Count > 0 && !string.IsNullOrWhiteSpace(item.Item))
                .GroupBy(item => item.Item, StringComparer.Ordinal)
                .Select(group => new { item = group.Key, count = group.Sum(item => item.Count) })
                .OrderBy(item => item.item)
                .ToList();
            return new
            {
                client.InstanceId,
                client.IsPrimary,
                client.AiControlAllowed,
                client.IsPossessionTarget,
                ageMilliseconds = client.LastStatusReceivedAt == default ? -1 : (long)(now - client.LastStatusReceivedAt).TotalMilliseconds,
                playerName = status?.PlayerName,
                dimension = status?.Dimension,
                health = status?.Health,
                food = status?.Food,
                x = status?.X,
                y = status?.Y,
                z = status?.Z,
                inventory,
                taskStage = client.TaskStage,
                miningQuota = client.MiningQuotaSnapshot,
                lastMiningQuotaResult = client.LastMiningQuotaResult,
                baritoneLoaded = status?.BaritoneLoaded,
                baritoneStatusAvailable = status?.BaritoneStatusAvailable,
                baritoneWorking = status?.BaritoneWorking,
                baritoneProcesses = status?.BaritoneProcesses
            };
        }).ToList();
        var players = snapshot
            .Where(client => client.LastStatus is not null && now - client.LastStatusReceivedAt < TimeSpan.FromSeconds(10))
            .SelectMany(client => client.LastStatus!.Players ?? [])
            .Where(player => !string.IsNullOrWhiteSpace(player.Name))
            .GroupBy(player => player.Name, StringComparer.OrdinalIgnoreCase)
            .Select(group => group.First())
            .Select(player => new { player.Name, player.Dimension, player.PositionAvailable, player.X, player.Y, player.Z })
            .ToList();
        return new { capturedAt = now, primaryInstanceId, primaryAiControlAllowed, activeTask = ActiveTaskProgressSnapshot(), instances, observedPlayers = players };
    }
    public ActiveTaskProgress? ActiveTaskProgressSnapshot()
    {
        CollectionTaskPlan? plan;
        AgentExecutionPlan? agentPlan;
        lock (clusterStateGate) { plan = activeCollectionPlan; agentPlan = activeAgentPlan; }
        if (agentPlan is not null)
        {
            var agentConnections = Snapshot().Where(client => agentPlan.InstanceIds.Contains(client.InstanceId)).ToList();
            var agentInstances = agentConnections.Select(client => new ActiveTaskInstanceProgress(
                client.InstanceId, client.LastStatus?.PlayerName ?? client.InstanceId, "agent_plan", agentPlan.Status,
                agentPlan.CurrentStep, 0, 0, client.LastStatus?.BaritoneWorking == true)).ToList();
            return new ActiveTaskProgress(!agentPlan.Complete, agentPlan.Complete, agentPlan.Title, "", 0,
                0, 0, 0, agentPlan.CurrentStep, agentPlan.Steps.Count, agentPlan.Steps, agentInstances, agentPlan.CreatedAt);
        }
        if (plan is null) return null;
        var connections = Snapshot()
            .Where(client => plan.Target == "all" ? client.ActiveTaskKey == plan.TaskKey || plan.InstanceIds.Contains(client.InstanceId) : plan.InstanceIds.Contains(client.InstanceId))
            .ToList();
        var instances = connections.Select(client =>
        {
            var status = client.LastStatus;
            var count = status is null ? 0 : InventoryCount(status, plan.ItemId);
            var step = TaskStageStep(client.TaskStage, plan.Steps.Count);
            return new ActiveTaskInstanceProgress(client.InstanceId, status?.PlayerName ?? client.InstanceId, client.TaskStage,
                TaskStageLabel(client.TaskStage), step, count, plan.RequiredCount, status?.BaritoneWorking == true);
        }).ToList();
        var complete = instances.Count > 0 && instances.All(item => item.CurrentStep >= plan.Steps.Count);
        var currentStep = instances.Count == 0 ? 0 : instances.Min(item => item.CurrentStep);
        return new ActiveTaskProgress(!complete, complete, plan.Title, plan.ItemId, plan.RequiredCount,
            plan.HomeX, plan.HomeY, plan.HomeZ, currentStep, plan.Steps.Count, plan.Steps, instances, plan.CreatedAt);
    }
    public bool HasIncompleteAgentExecutionPlan
    {
        get { lock (clusterStateGate) return activeAgentPlan is { Complete: false }; }
    }
    public bool AnyBaritoneWorking => Snapshot().Any(client => client.LastStatus?.BaritoneWorking == true);
    public bool ActiveTaskTargetsWorking
    {
        get
        {
            IReadOnlyList<string> instanceIds;
            lock (clusterStateGate)
                instanceIds = activeAgentPlan?.InstanceIds ?? activeCollectionPlan?.InstanceIds ?? [];
            return instanceIds.Any(instanceId => clients.TryGetValue(instanceId, out var client) && client.LastStatus?.BaritoneWorking == true);
        }
    }
    public async Task<object> BeginAgentExecutionPlanAsync(string task, string planText)
    {
        var title = Regex.Match(planText, @"(?m)^\s*目标[：:]\s*(.+)$").Groups[1].Value.Trim();
        if (string.IsNullOrWhiteSpace(title)) title = task.Trim();
        var steps = Regex.Matches(planText, @"(?m)^\s*\d+\s*[\.、\)]\s*(.+?)\s*$")
            .Select(match => Regex.Replace(match.Groups[1].Value.Trim(), @"\s+", " "))
            .Where(step => !string.IsNullOrWhiteSpace(step))
            .Take(100)
            .ToList();
        if (steps.Count == 0) steps.Add(task.Trim());
        var instanceIds = ResolveAiTargets("all").Targets.Select(client => client.InstanceId).ToList();
        lock (clusterStateGate)
        {
            activeAgentPlan = new(Guid.NewGuid().ToString("N"), title, steps, 0, false, "已规划，准备执行", instanceIds, DateTimeOffset.UtcNow);
            activeCollectionPlan = null;
            clusterStateVersion++;
        }
        await BroadcastClusterStateAsync();
        return new { started = true, title, totalSteps = steps.Count, steps };
    }
    public async Task<object> UpdateAgentExecutionPlanAsync(int completedSteps, string status)
    {
        AgentExecutionPlan? updated;
        lock (clusterStateGate)
        {
            if (activeAgentPlan is null) return new { updated = false, error = "没有正在运行的通用 Agent 计划" };
            if (completedSteps >= activeAgentPlan.Steps.Count && ActiveTaskTargetsWorking)
                return new { updated = false, error = "Baritone 仍在工作，不能把最终步骤标记为完成" };
            var nextStep = Math.Clamp(completedSteps, activeAgentPlan.CurrentStep, activeAgentPlan.Steps.Count);
            var normalizedStatus = string.IsNullOrWhiteSpace(status) ? $"已完成 {nextStep}/{activeAgentPlan.Steps.Count} 步" : status.Trim();
            activeAgentPlan = activeAgentPlan with { CurrentStep = nextStep, Status = normalizedStatus };
            updated = activeAgentPlan;
            clusterStateVersion++;
        }
        await BroadcastClusterStateAsync();
        return new { updated = true, currentStep = updated.CurrentStep, totalSteps = updated.Steps.Count, updated.Status };
    }
    public async Task<object> FinishAgentExecutionPlanAsync(bool success, string summary)
    {
        if (success)
        {
            AgentExecutionPlan? verificationPlan;
            lock (clusterStateGate) verificationPlan = activeAgentPlan;
            if (verificationPlan is null) return new { updated = false, error = "没有正在运行的通用 Agent 计划" };
            if (verificationPlan.CurrentStep < verificationPlan.Steps.Count)
                return new { updated = false, error = $"计划只完成了 {verificationPlan.CurrentStep}/{verificationPlan.Steps.Count} 步，不能提前结束" };
            if (AnyBaritoneWorking)
                return new { updated = false, error = "仍有 Baritone 在工作，必须等待并验证最终结果" };
            var now = DateTimeOffset.UtcNow;
            var stale = Snapshot().Where(client => verificationPlan.InstanceIds.Contains(client.InstanceId))
                .Any(client => client.LastStatus is null || now - client.LastStatusReceivedAt > TimeSpan.FromSeconds(5));
            if (stale)
                return new { updated = false, error = "存在状态为空或超过 5 秒的目标实例，必须读取新状态后才能完成计划" };
        }
        AgentExecutionPlan? updated;
        lock (clusterStateGate)
        {
            if (activeAgentPlan is null) return new { updated = false, error = "没有正在运行的通用 Agent 计划" };
            var status = string.IsNullOrWhiteSpace(summary) ? (success ? "全部步骤已验证完成" : "任务未能完成") : summary.Trim();
            activeAgentPlan = activeAgentPlan with
            {
                CurrentStep = success ? activeAgentPlan.Steps.Count : activeAgentPlan.CurrentStep,
                Complete = true,
                Status = status
            };
            updated = activeAgentPlan;
            clusterStateVersion++;
        }
        await BroadcastClusterStateAsync();
        return new { updated = true, success, currentStep = updated.CurrentStep, totalSteps = updated.Steps.Count, updated.Status };
    }
    public bool HasActivePlannedTask { get { lock (clusterStateGate) return activeCollectionPlan is not null && ActiveTaskProgressSnapshot()?.Complete != true; } }
    public async Task<ActiveTaskAdvanceResult> AdvanceActivePlannedTaskAsync()
    {
        CollectionTaskPlan? plan;
        lock (clusterStateGate) plan = activeCollectionPlan;
        if (plan is null) return new(false, false, "没有正在运行的分阶段计划。", null, null);
        var details = await ProgressCollectionTaskAsync(plan.Target, plan.ItemId, plan.RequiredCount, plan.HomeX, plan.HomeY, plan.HomeZ);
        var progress = ActiveTaskProgressSnapshot();
        var summary = progress is null ? "计划状态不可用。" : progress.Complete
            ? $"计划完成：{progress.Title}"
            : $"计划进度：第 {Math.Min(progress.CurrentStep + 1, progress.TotalSteps)}/{progress.TotalSteps} 步 · {progress.Steps[Math.Min(progress.CurrentStep, progress.Steps.Count - 1)]}";
        await BroadcastClusterStateAsync();
        return new(true, progress?.Complete == true, summary, progress, details);
    }
    public async Task CancelActivePlannedTaskAsync()
    {
        lock (clusterStateGate) { activeCollectionPlan = null; activeAgentPlan = null; clusterStateVersion++; }
        await BroadcastClusterStateAsync();
    }
    public object Status(string id) => clients.TryGetValue(id, out var c) ? c.CachedStatus() : new { connected = false, error = "Unknown instance" };
    public object ControlState() { lock (clusterStateGate) return new { primaryInstanceId, primaryAiControlAllowed, possessionTargetId, clusterStateVersion }; }
    public async Task<object> SetPrimaryInstanceAsync(string instanceId)
    {
        if (!clients.TryGetValue(instanceId, out var selected)) return new { changed = false, error = "Unknown instance" };
        lock (clusterStateGate) { primaryInstanceId = instanceId; preferredPrimaryInstanceId = instanceId; preferredPrimaryPlayerUuid = selected.LastStatus?.PlayerUuid; preferredPrimaryPlayerName = selected.LastStatus?.PlayerName; primaryAiControlAllowed = false; possessionTargetId = null; clusterStateVersion++; ApplyRolesLocked(); }
        SavePersistentSettings();
        Changed?.Invoke(); await BroadcastClusterStateAsync();
        return new { changed = true, primaryInstanceId, primaryAiControlAllowed, message = "主要玩家已启用 AI 保护；只有用户明确授权后 AI 才能控制。" };
    }
    public async Task<object> SetPrimaryAiControlAllowedAsync(bool allowed)
    {
        lock (clusterStateGate) { if (primaryInstanceId is null) return new { changed = false, error = "尚未设置主要玩家" }; primaryAiControlAllowed = allowed; clusterStateVersion++; ApplyRolesLocked(); }
        SavePersistentSettings(); Changed?.Invoke(); await BroadcastClusterStateAsync();
        return new { changed = true, primaryInstanceId, primaryAiControlAllowed };
    }
    public async Task<bool> PublishAiReplyAsync(string message)
    {
        ClientConnection? primary = null;
        lock (clusterStateGate)
        {
            if (showAiRepliesInChat && primaryInstanceId is not null)
                clients.TryGetValue(primaryInstanceId, out primary);
        }
        if (primary is null || string.IsNullOrWhiteSpace(message)) return false;
        var normalized = message.Trim();
        if (normalized.Length > 4000) normalized = normalized[..4000] + "…";
        try
        {
            await primary.SendAsync(new { type = "ai_chat_message", message = normalized });
            return true;
        }
        catch (IOException) { return false; }
    }
    public async Task<object> StopPossessionAsync()
    {
        lock (clusterStateGate) { possessionTargetId = null; clusterStateVersion++; ApplyRolesLocked(); }
        Changed?.Invoke(); await BroadcastClusterStateAsync();
        return new { stopped = true };
    }
    public async Task<object> SendAsync(string target, string command)
    {
        if (!command.StartsWith('#') && !command.StartsWith('/')) return new { sent = false, error = "Only Baritone # commands or Minecraft / commands are permitted." };
        var selection = ResolveAiTargets(target); var eligible = selection.Targets.Where(x => x.TryBeginCommand(command)).ToList();
        foreach (var item in eligible) await item.SendAsync(new CommandMessage("command", command)); return new { sent = eligible.Count > 0, count = eligible.Count, skippedAlreadyRunning = selection.Targets.Count - eligible.Count, skippedPrimaryProtected = selection.SkippedPrimary };
    }
    public async Task<object> UserFollowAsync(string instanceId, string playerName)
    {
        if (!clients.TryGetValue(instanceId, out var client)) return new { sent = false, error = "目标实例不在线" };
        if (string.IsNullOrWhiteSpace(playerName) || playerName.Length > 32 || playerName.Any(ch => !(char.IsLetterOrDigit(ch) || ch is '_')))
            return new { sent = false, error = "玩家名称无效" };
        await client.SendCommandSequenceAsync("#stop", $"#follow player {playerName}");
        client.TaskStage = "user_follow";
        return new { sent = true, instanceId, playerName };
    }
    public async Task<object> UserMoveAsync(string instanceId, int x, int y, int z)
    {
        if (!clients.TryGetValue(instanceId, out var client)) return new { sent = false, error = "目标实例不在线" };
        await client.SendCommandSequenceAsync("#stop", $"#goto {x} {y} {z}");
        client.TaskStage = "user_move";
        return new { sent = true, instanceId, x, y, z };
    }
    public async Task<object> ActionAsync(string target, object action)
    {
        var selection = ResolveAiTargets(target);
        foreach (var item in selection.Targets) await item.SendAsync(action); return new { sent = selection.Targets.Count > 0, count = selection.Targets.Count, skippedPrimaryProtected = selection.SkippedPrimary };
    }
    public async Task<object> MoveToAsync(string target, int x, int y, int z)
    {
        var selection = ResolveAiTargets(target); var results = new List<object>();
        foreach (var client in selection.Targets)
        {
            await client.SendCommandSequenceAsync("#stop", $"#goto {x} {y} {z}");
            client.TaskStage = "agent_move";
            results.Add(new { client.InstanceId, command = $"#goto {x} {y} {z}", accepted = true });
        }
        return new { sent = results.Count > 0, skippedPrimaryProtected = selection.SkippedPrimary, results };
    }
    public async Task<object> MineItemAsync(string target, string itemId, int count)
    {
        itemId = NormalizeItemId(itemId); count = Math.Max(1, count);
        var selection = ResolveAiTargets(target); var results = new List<object>();
        foreach (var client in selection.Targets)
        {
            var status = client.LastStatus;
            if (status is null || DateTimeOffset.UtcNow - client.LastStatusReceivedAt > TimeSpan.FromSeconds(5))
            {
                results.Add(new { client.InstanceId, itemId, requestedCount = count, accepted = false, reason = "status_unavailable_or_stale" });
                continue;
            }
            var before = InventoryCount(status, itemId);
            var started = await StartMiningWithQuotaAsync(client, itemId, before, count, "agent_mining");
            results.Add(new { client.InstanceId, itemId, requestedCount = count, inventoryBefore = before,
                inventoryTarget = before + count, started.Command, accepted = true,
                maximumDurationMinutes = started.MaximumDuration.TotalMinutes, noProgressTimeoutMinutes = started.NoProgressTimeout.TotalMinutes });
        }
        return new { sent = results.Count > 0, skippedPrimaryProtected = selection.SkippedPrimary, results };
    }

    private async Task<(string Command, TimeSpan MaximumDuration, TimeSpan NoProgressTimeout)> StartMiningWithQuotaAsync(
        ClientConnection client, string itemId, int inventoryBefore, int additionalCount, string taskStage)
    {
        additionalCount = Math.Max(1, additionalCount);
        var command = $"#mine {inventoryBefore + additionalCount} {MineTargets(itemId)}";
        var maximumDuration = TimeSpan.FromMinutes(Math.Clamp(additionalCount * 5d, 30d, 240d));
        var noProgressTimeout = TimeSpan.FromMinutes(45);
        await client.SendCommandSequenceAsync("#stop", command);
        client.BeginMiningQuota(itemId, inventoryBefore, inventoryBefore + additionalCount, maximumDuration, noProgressTimeout);
        client.TaskStage = taskStage;
        return (command, maximumDuration, noProgressTimeout);
    }
    private void EnsureCollectionPlan(string target, string itemId, int requiredCount, int homeX, int homeY, int homeZ, string? displayItem = null, bool replaceAgentPlan = false)
    {
        var taskKey = $"{itemId}|{requiredCount}|{homeX}|{homeY}|{homeZ}";
        lock (clusterStateGate)
        {
            if (activeCollectionPlan?.TaskKey == taskKey) return;
            var instanceIds = ResolveAiTargets(target).Targets.Select(client => client.InstanceId).ToList();
            var shownItem = string.IsNullOrWhiteSpace(displayItem) ? itemId : displayItem.Trim();
            activeCollectionPlan = new(taskKey, target, itemId, requiredCount, homeX, homeY, homeZ,
                $"采集 {requiredCount} 个 {shownItem} 并放入 ({homeX}, {homeY}, {homeZ}) 附近容器",
                [$"挖掘 {requiredCount} 个 {shownItem}", $"移动到 ({homeX}, {homeY}, {homeZ}) 附近", "寻找、打开附近容器并放入物品"],
                instanceIds, DateTimeOffset.UtcNow);
            if (replaceAgentPlan) activeAgentPlan = null;
            clusterStateVersion++;
        }
    }
    public async Task<string?> TryStartFastIntentAsync(string prompt)
    {
        if (string.IsNullOrWhiteSpace(prompt) || Regex.IsMatch(prompt, "不要|别|禁止|停止")) return null;

        var mining = Regex.Match(prompt, @"(?:挖|采集|收集)\s*(\d+)\s*(组|个|块)?\s*([\p{L}\p{N}_:]+?)(?=然后|再|并|后|扔|丢|给|放|，|,|。|$)", RegexOptions.IgnoreCase);
        if (mining.Success && int.TryParse(mining.Groups[1].Value, out var requestedCount))
        {
            var unit = mining.Groups[2].Value;
            var displayItem = mining.Groups[3].Value;
            var itemId = FastItemId(displayItem);
            if (itemId is not null)
            {
                requestedCount = Math.Clamp(requestedCount * (unit == "组" ? 64 : 1), 1, 4096);
                var storageRequested = Regex.IsMatch(prompt, "放进|放入|放在|存进|存入") && Regex.IsMatch(prompt, "容器|箱子|木桶");
                var coordinates = Regex.Match(prompt, @"(-?\d+)\s*[,， ]+\s*(-?\d+)\s*[,， ]+\s*(-?\d+)");
                if (storageRequested && coordinates.Success)
                {
                    var homeX = int.Parse(coordinates.Groups[1].Value); var homeY = int.Parse(coordinates.Groups[2].Value); var homeZ = int.Parse(coordinates.Groups[3].Value);
                    EnsureCollectionPlan("all", itemId, requestedCount, homeX, homeY, homeZ, displayItem, replaceAgentPlan: true);
                    var plannedResult = await ProgressCollectionTaskAsync("all", itemId, requestedCount, homeX, homeY, homeZ);
                    var plan = ActiveTaskProgressSnapshot();
                    await BroadcastClusterStateAsync();
                    return JsonSerializer.Serialize(new { operation = "planned_collection", target = "all", itemId, requestedCount, homeX, homeY, homeZ, plan, result = plannedResult }, JsonProtocol.Options);
                }
                var result = await MineItemAsync("all", itemId, Math.Clamp(requestedCount, 1, 4096));
                return JsonSerializer.Serialize(new { operation = "mine_item", target = "all", itemId, requestedCount, result }, JsonProtocol.Options);
            }
        }

        var movement = Regex.Match(prompt, @"(?:移动到|前往|走到|去)\s*[（(]?\s*(-?\d+)\s*[,， ]+\s*(-?\d+)\s*[,， ]+\s*(-?\d+)\s*[）)]?", RegexOptions.IgnoreCase);
        if (movement.Success)
        {
            var x = int.Parse(movement.Groups[1].Value); var y = int.Parse(movement.Groups[2].Value); var z = int.Parse(movement.Groups[3].Value);
            var result = await MoveToAsync("all", x, y, z);
            return JsonSerializer.Serialize(new { operation = "move_to", target = "all", x, y, z, result }, JsonProtocol.Options);
        }

        var following = Regex.Match(prompt, @"跟随\s*(?:玩家)?\s*([A-Za-z0-9_]{1,32})", RegexOptions.IgnoreCase);
        if (following.Success)
        {
            var playerName = following.Groups[1].Value;
            var result = await FollowPlayerAsync("all", playerName);
            return JsonSerializer.Serialize(new { operation = "follow_player", target = "all", playerName, result }, JsonProtocol.Options);
        }
        return null;
    }
    public async Task<object> FollowPlayerAsync(string target, string playerName)
    {
        if (string.IsNullOrWhiteSpace(playerName) || playerName.Length > 32 || playerName.Any(ch => !(char.IsLetterOrDigit(ch) || ch is '_')))
            return new { sent = false, error = "玩家名称无效" };
        var selection = ResolveAiTargets(target); var results = new List<object>();
        foreach (var client in selection.Targets)
        {
            await client.SendCommandSequenceAsync("#stop", $"#follow player {playerName}");
            client.TaskStage = "agent_follow";
            results.Add(new { client.InstanceId, playerName, accepted = true });
        }
        return new { sent = results.Count > 0, skippedPrimaryProtected = selection.SkippedPrimary, results };
    }
    public async Task<object> DiscardItemAsync(string target, string itemId, int count, CancellationToken cancellationToken = default)
    {
        itemId = NormalizeItemId(itemId); count = count == 0 ? -1 : Math.Max(-1, count);
        var selection = ResolveAiTargets(target); var results = new List<object>();
        foreach (var client in selection.Targets)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var beforeStatus = client.LastStatus;
            if (beforeStatus is null || DateTimeOffset.UtcNow - client.LastStatusReceivedAt > TimeSpan.FromSeconds(5))
            {
                results.Add(new { client.InstanceId, itemId, success = false, reason = "status_unavailable_or_stale" });
                continue;
            }
            var before = InventoryCount(beforeStatus, itemId);
            if (before == 0) { results.Add(new { client.InstanceId, itemId, success = true, inventoryBefore = 0, inventoryAfter = 0, discarded = 0 }); continue; }
            var requested = count < 0 ? before : Math.Min(count, before);
            var expectedMaximum = before - requested;
            var sentAt = DateTimeOffset.UtcNow;
            await client.SendAsync(new { type = "action", operation = "discard_item", item = itemId, count });
            var updated = await client.WaitForFreshStatusAsync(sentAt, status => InventoryCount(status, itemId) <= expectedMaximum, TimeSpan.FromSeconds(8));
            var after = InventoryCount(updated ?? client.LastStatus ?? beforeStatus, itemId);
            results.Add(new { client.InstanceId, itemId, success = updated is not null, requested, inventoryBefore = before, inventoryAfter = after, discarded = Math.Max(0, before - after), reason = updated is null ? "未在超时前观察到期望的背包变化" : (string?)null });
        }
        return new { skippedPrimaryProtected = selection.SkippedPrimary, results };
    }
    public async Task<object> CraftItemAsync(string target, string itemId, int count, CancellationToken cancellationToken = default)
    {
        itemId = NormalizeItemId(itemId); count = Math.Max(1, count);
        var selection = ResolveAiTargets(target); var results = new List<object>();
        foreach (var client in selection.Targets)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var beforeStatus = client.LastStatus;
            if (beforeStatus is null || DateTimeOffset.UtcNow - client.LastStatusReceivedAt > TimeSpan.FromSeconds(5))
            {
                results.Add(new { client.InstanceId, itemId, success = false, reason = "status_unavailable_or_stale" });
                continue;
            }
            var before = InventoryCount(beforeStatus, itemId);
            var sentAt = DateTimeOffset.UtcNow;
            await client.SendAsync(new { type = "action", operation = "craft_item", item = itemId, count });
            var timeout = TimeSpan.FromSeconds(Math.Clamp(8 + count / 2, 8, 30));
            var updated = await client.WaitForFreshStatusAsync(sentAt, status => InventoryCount(status, itemId) >= before + count, timeout);
            var after = InventoryCount(updated ?? client.LastStatus ?? beforeStatus, itemId);
            results.Add(new { client.InstanceId, itemId, success = updated is not null, requested = count, inventoryBefore = before, inventoryAfter = after, craftedObserved = Math.Max(0, after - before), openContainer = (updated ?? client.LastStatus)?.OpenContainer, reason = updated is null ? "未观察到足够产物；可能缺少材料、配方未解锁，或3x3配方尚未打开工作台" : (string?)null });
        }
        return new { skippedPrimaryProtected = selection.SkippedPrimary, results };
    }
    public async Task<object> UseItemAsync(string target, string itemId, CancellationToken cancellationToken = default)
    {
        itemId = NormalizeItemId(itemId);
        var selection = ResolveAiTargets(target); var results = new List<object>();
        foreach (var client in selection.Targets)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var status = client.LastStatus;
            var before = status is null ? 0 : InventoryCount(status, itemId);
            if (status is null || before == 0) { results.Add(new { client.InstanceId, itemId, success = false, reason = status is null ? "status_unavailable" : "item_not_in_inventory" }); continue; }
            var sentAt = DateTimeOffset.UtcNow;
            await client.SendAsync(new { type = "action", operation = "use_item", item = itemId });
            var updated = await client.WaitForFreshStatusAsync(sentAt, _ => true, TimeSpan.FromSeconds(3));
            results.Add(new { client.InstanceId, itemId, success = true, actionSent = true, freshStatusObserved = updated is not null, inventoryBefore = before, inventoryAfter = updated is null ? (int?)null : InventoryCount(updated, itemId) });
        }
        return new { skippedPrimaryProtected = selection.SkippedPrimary, results };
    }
    public async Task<object> PlaceBlockNearbyAsync(string target, string itemId, CancellationToken cancellationToken = default)
    {
        itemId = NormalizeItemId(itemId);
        var selection = ResolveAiTargets(target); var results = new List<object>();
        foreach (var client in selection.Targets)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var beforeStatus = client.LastStatus;
            if (beforeStatus is null || DateTimeOffset.UtcNow - client.LastStatusReceivedAt > TimeSpan.FromSeconds(5))
            {
                results.Add(new { client.InstanceId, itemId, success = false, reason = "status_unavailable_or_stale" }); continue;
            }
            var beforeCount = InventoryCount(beforeStatus, itemId);
            if (beforeCount <= 0) { results.Add(new { client.InstanceId, itemId, success = false, reason = "block_not_in_inventory" }); continue; }
            var beforePositions = (beforeStatus.FunctionalBlocks ?? []).Where(block => block.Block == itemId).Select(block => (block.X, block.Y, block.Z)).ToHashSet();
            var sentAt = DateTimeOffset.UtcNow;
            await client.SendAsync(new { type = "action", operation = "place_block_nearby", item = itemId });
            var updated = await client.WaitForFreshStatusAsync(sentAt, status =>
                InventoryCount(status, itemId) < beforeCount ||
                (status.FunctionalBlocks ?? []).Any(block => block.Block == itemId && !beforePositions.Contains((block.X, block.Y, block.Z))), TimeSpan.FromSeconds(6));
            var latest = updated ?? client.LastStatus ?? beforeStatus;
            var placed = (latest.FunctionalBlocks ?? []).Where(block => block.Block == itemId && !beforePositions.Contains((block.X, block.Y, block.Z)))
                .OrderBy(block => DistanceSquared(latest.X, latest.Y, latest.Z, block.X, block.Y, block.Z)).FirstOrDefault();
            results.Add(new { client.InstanceId, itemId, success = updated is not null && (InventoryCount(latest, itemId) < beforeCount || placed is not null),
                inventoryBefore = beforeCount, inventoryAfter = InventoryCount(latest, itemId), placedBlock = placed,
                reason = updated is null ? "没有观察到方块放置；附近可能没有可放置的实体地面或方块超出交互距离" : (string?)null });
        }
        return new { skippedPrimaryProtected = selection.SkippedPrimary, results };
    }
    public async Task<object> OpenNearestFunctionalBlockAsync(string target, string blockId, CancellationToken cancellationToken = default)
    {
        blockId = NormalizeItemId(blockId);
        var selection = ResolveAiTargets(target); var results = new List<object>();
        foreach (var client in selection.Targets)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var status = client.LastStatus;
            if (status is null || DateTimeOffset.UtcNow - client.LastStatusReceivedAt > TimeSpan.FromSeconds(5))
            {
                results.Add(new { client.InstanceId, blockId, success = false, reason = "status_unavailable_or_stale" }); continue;
            }
            var block = (status.FunctionalBlocks ?? []).Where(item => item.Block == blockId)
                .OrderBy(item => DistanceSquared(status.X, status.Y, status.Z, item.X, item.Y, item.Z)).FirstOrDefault();
            if (block is null) { results.Add(new { client.InstanceId, blockId, success = false, reason = "functional_block_not_reported_near_player" }); continue; }
            if (DistanceSquared(status.X, status.Y, status.Z, block.X, block.Y, block.Z) > 36)
            {
                results.Add(new { client.InstanceId, blockId, success = false, block, reason = "functional_block_out_of_interaction_range" }); continue;
            }
            var sentAt = DateTimeOffset.UtcNow;
            await client.SendAsync(new { type = "action", operation = "open_block", x = block.X, y = block.Y, z = block.Z });
            var opened = await client.WaitForFreshStatusAsync(sentAt, status =>
                !string.IsNullOrWhiteSpace(status.OpenContainer) && status.OpenContainer != "InventoryMenu", TimeSpan.FromSeconds(5));
            results.Add(new { client.InstanceId, blockId, success = opened is not null, block, openContainer = opened?.OpenContainer,
                reason = opened is null ? "未观察到功能方块界面打开" : (string?)null });
        }
        return new { skippedPrimaryProtected = selection.SkippedPrimary, results };
    }
    public async Task<object> GiveItemToPlayerAsync(string target, string itemId, string playerName, int count = 1, CancellationToken cancellationToken = default)
    {
        itemId = NormalizeItemId(itemId); count = Math.Max(1, count);
        if (string.IsNullOrWhiteSpace(playerName) || playerName.Length > 32 || playerName.Any(ch => !(char.IsLetterOrDigit(ch) || ch is '_')))
            return new { success = false, error = "玩家名称无效" };
        var recipientClient = Snapshot().FirstOrDefault(client => string.Equals(client.LastStatus?.PlayerName, playerName, StringComparison.OrdinalIgnoreCase));
        var recipientBefore = recipientClient?.LastStatus is null ? (int?)null : InventoryCount(recipientClient.LastStatus, itemId);
        var selection = ResolveAiTargets(target); var results = new List<object>();
        foreach (var client in selection.Targets)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var status = client.LastStatus;
            var beforeCount = status is null ? 0 : InventoryCount(status, itemId);
            if (status is null || beforeCount <= 0) { results.Add(new { client.InstanceId, itemId, playerName, success = false, reason = status is null ? "status_unavailable" : "item_not_in_inventory" }); continue; }
            var recipient = status.Players?.FirstOrDefault(player => string.Equals(player.Name, playerName, StringComparison.OrdinalIgnoreCase) && player.PositionAvailable);
            if (recipient is null) { results.Add(new { client.InstanceId, itemId, playerName, success = false, reason = "recipient_position_unavailable" }); continue; }
            if (PlayerDistanceSquared(status, recipient) > 12.25)
            {
                var moveSentAt = DateTimeOffset.UtcNow;
                await client.SendCommandSequenceAsync("#stop", $"#goto {(int)Math.Floor(recipient.X)} {(int)Math.Floor(recipient.Y)} {(int)Math.Floor(recipient.Z)}");
                var reached = await client.WaitForFreshStatusAsync(moveSentAt, fresh =>
                {
                    var currentRecipient = fresh.Players?.FirstOrDefault(player => string.Equals(player.Name, playerName, StringComparison.OrdinalIgnoreCase) && player.PositionAvailable);
                    return currentRecipient is not null && PlayerDistanceSquared(fresh, currentRecipient) <= 12.25;
                }, TimeSpan.FromSeconds(30));
                if (reached is null) { results.Add(new { client.InstanceId, itemId, playerName, success = false, reason = "failed_to_reach_recipient" }); continue; }
            }
            await client.SendCommandDirectAsync("#stop");
            await client.SendAsync(new { type = "action", operation = "close_screen" });
            await Task.Delay(150, cancellationToken);
            var dropSentAt = DateTimeOffset.UtcNow;
            await client.SendAsync(new { type = "action", operation = "discard_item", item = itemId, count = Math.Min(count, beforeCount) });
            var senderUpdated = await client.WaitForFreshStatusAsync(dropSentAt, fresh => InventoryCount(fresh, itemId) < beforeCount, TimeSpan.FromSeconds(6));
            bool? recipientPickupObserved = null;
            if (recipientClient is not null && recipientBefore.HasValue)
            {
                var pickedUp = await recipientClient.WaitForFreshStatusAsync(dropSentAt, fresh => InventoryCount(fresh, itemId) > recipientBefore.Value, TimeSpan.FromSeconds(6));
                recipientPickupObserved = pickedUp is not null;
            }
            results.Add(new { client.InstanceId, itemId, playerName, success = senderUpdated is not null,
                dropped = senderUpdated is null ? 0 : beforeCount - InventoryCount(senderUpdated, itemId), recipientPickupObserved,
                reason = senderUpdated is null ? "未观察到物品从给予者背包移出" : (string?)null });
        }
        return new { skippedPrimaryProtected = selection.SkippedPrimary, results };
    }
    private static double PlayerDistanceSquared(StatusMessage status, ObservedPlayer player)
    {
        double dx = status.X + 0.5 - player.X, dy = status.Y - player.Y, dz = status.Z + 0.5 - player.Z;
        return dx * dx + dy * dy + dz * dz;
    }
    public async Task<object> WaitForInstanceStateAsync(string instanceId, int maxWaitSeconds, bool requireBaritoneIdle, CancellationToken cancellationToken = default)
    {
        if (!clients.TryGetValue(instanceId, out var client)) return new { connected = false, error = "Unknown instance" };
        maxWaitSeconds = Math.Clamp(maxWaitSeconds, 1, 30);
        var start = DateTimeOffset.UtcNow;
        var until = start + TimeSpan.FromSeconds(maxWaitSeconds);
        while (DateTimeOffset.UtcNow < until)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var status = client.LastStatus;
            if (status is not null && client.LastStatusReceivedAt > start && (!requireBaritoneIdle || !status.BaritoneWorking))
                return new { connected = true, waitedMilliseconds = (long)(DateTimeOffset.UtcNow - start).TotalMilliseconds, conditionMet = true, status = client.CachedStatus() };
            await Task.Delay(100, cancellationToken);
        }
        return new { connected = true, waitedMilliseconds = (long)(DateTimeOffset.UtcNow - start).TotalMilliseconds, conditionMet = false, status = client.CachedStatus() };
    }
    public async Task<object> StopBaritoneAsync(string target, bool bypassPrimaryProtection = false)
    {
        var selection = bypassPrimaryProtection ? ResolveAllTargets(target) : ResolveAiTargets(target);
        foreach (var item in selection.Targets) { await item.SendCommandDirectAsync("#stop"); item.TaskStage = "stopped"; }
        return new { stopped = selection.Targets.Count > 0, count = selection.Targets.Count, skippedPrimaryProtected = selection.SkippedPrimary };
    }
    public async Task<object> ProgressCollectionTaskAsync(string target, string itemId, int requiredCount, int homeX, int homeY, int homeZ)
    {
        itemId = NormalizeItemId(itemId);
        var taskKey = $"{itemId}|{requiredCount}|{homeX}|{homeY}|{homeZ}";
        EnsureCollectionPlan(target, itemId, requiredCount, homeX, homeY, homeZ);
        var selection = ResolveAiTargets(target); var targets = selection.Targets;
        var results = new List<object>();
        foreach (var client in targets)
        {
            var status = client.LastStatus;
            if (status is null || DateTimeOffset.UtcNow - client.LastStatusReceivedAt > TimeSpan.FromSeconds(5)) { results.Add(new { client.InstanceId, stage = "waiting_status" }); continue; }
            client.BeginTask(taskKey);
            var count = status.Inventory?.Where(x => x.Item == itemId).Sum(x => x.Count) ?? 0;
            if (client.TaskStage == "complete") { results.Add(new { client.InstanceId, stage = "complete", itemId, targetItemCount = count }); continue; }
            if (client.TaskStage == "depositing")
            {
                if (count == 0) { client.TaskStage = "complete"; results.Add(new { client.InstanceId, stage = "complete", itemId, targetItemCount = 0 }); continue; }
                client.TaskStage = "at_chest";
            }
            if (count < requiredCount && client.TaskStage is not ("to_home" or "to_chest" or "at_chest"))
            {
                if (client.TaskStage != "mining" || (!status.BaritoneWorking && client.ShouldSendNavigation($"mine:{taskKey}:{count}")))
                {
                    await StartMiningWithQuotaAsync(client, itemId, count, requiredCount - count, "mining");
                    client.TaskStage = "mining";
                }
                results.Add(new { client.InstanceId, stage = "mining", itemId, targetItemCount = count, requiredCount }); continue;
            }
            var homeHorizontalDistance = HorizontalDistanceSquared(status.X, status.Z, homeX, homeZ);
            var chest = status.FunctionalBlocks?
                .Where(x => x.Block is "minecraft:chest" or "minecraft:trapped_chest" or "minecraft:barrel")
                .Where(x => HorizontalDistanceSquared(x.X, x.Z, homeX, homeZ) <= 24L * 24L && Math.Abs(x.Y - homeY) <= 24)
                .OrderBy(x => DistanceSquared(status.X, status.Y, status.Z, x.X, x.Y, x.Z))
                .FirstOrDefault();
            if (chest is null && homeHorizontalDistance > 10L * 10L)
            {
                if (client.ShouldSendNavigation($"home:{homeX}:{homeY}:{homeZ}")) await client.SendCommandSequenceAsync("#stop", $"#goto {homeX} {homeY} {homeZ}");
                client.TaskStage = "to_home"; results.Add(new { client.InstanceId, stage = "to_home", itemId, targetItemCount = count, horizontalDistanceSquared = homeHorizontalDistance }); continue;
            }
            if (chest is null) { client.TaskStage = "at_home"; results.Add(new { client.InstanceId, stage = "no_storage_near_home", itemId, targetItemCount = count, horizontalDistanceSquared = homeHorizontalDistance }); continue; }
            var chestDistance = DistanceSquared(status.X, status.Y, status.Z, chest.X, chest.Y, chest.Z);
            if (chestDistance > 25)
            {
                var approach = ChestApproach(status, chest);
                if (client.ShouldSendNavigation($"chest:{chest.X}:{chest.Y}:{chest.Z}:{approach.X}:{approach.Y}:{approach.Z}")) await client.SendCommandSequenceAsync("#stop", $"#goto {approach.X} {approach.Y} {approach.Z}");
                client.TaskStage = "to_chest"; results.Add(new { client.InstanceId, stage = "to_chest", itemId, targetItemCount = count, chest, approach = new { x = approach.X, y = approach.Y, z = approach.Z }, distanceSquared = chestDistance }); continue;
            }
            var openSentAt = DateTimeOffset.UtcNow;
            await client.SendAsync(new { type = "action", operation = "open_block", x = chest.X, y = chest.Y, z = chest.Z });
            var opened = await client.WaitForFreshStatusAsync(openSentAt, IsBlockContainerOpen, TimeSpan.FromSeconds(4));
            if (opened is null) { client.TaskStage = "at_chest"; results.Add(new { client.InstanceId, stage = "open_chest_failed", itemId, targetItemCount = count, chest, message = "未确认箱子界面已打开；下一周期会重试。" }); continue; }
            var depositSentAt = DateTimeOffset.UtcNow;
            await client.SendAsync(new { type = "action", operation = "deposit_item", item = itemId });
            var afterDeposit = await client.WaitForFreshStatusAsync(depositSentAt, x => InventoryCount(x, itemId) < count, TimeSpan.FromSeconds(4));
            await client.SendAsync(new { type = "action", operation = "close_screen" });
            var remaining = afterDeposit is null ? count : InventoryCount(afterDeposit, itemId);
            if (remaining == 0) { client.TaskStage = "complete"; results.Add(new { client.InstanceId, stage = "complete", itemId, targetItemCount = 0, chest, depositedCount = count }); continue; }
            client.TaskStage = "at_chest"; results.Add(new { client.InstanceId, stage = "deposit_incomplete", itemId, remainingTargetItemCount = remaining, chest, depositedCount = count - remaining, message = "箱子可能已满或转移动作未完成；下一周期会重试。" });
        }
        var response = new { taskKey, itemId, requiredCount, skippedPrimaryProtected = selection.SkippedPrimary, results };
        await BroadcastClusterStateAsync();
        return response;
    }
    public async Task<object> ProgressSmeltingTaskAsync(string target, string inputItemId, int inputCount, string fuelItemId, int fuelCount, string resultItemId, int homeX, int homeY, int homeZ)
    {
        inputItemId = NormalizeItemId(inputItemId); fuelItemId = NormalizeItemId(fuelItemId); resultItemId = NormalizeItemId(resultItemId);
        var taskKey = $"smelt|{inputItemId}|{inputCount}|{fuelItemId}|{fuelCount}|{resultItemId}|{homeX}|{homeY}|{homeZ}";
        var selection = ResolveAiTargets(target); var targets = selection.Targets;
        var results = new List<object>();
        foreach (var client in targets)
        {
            var status = client.LastStatus;
            if (status is null || DateTimeOffset.UtcNow - client.LastStatusReceivedAt > TimeSpan.FromSeconds(5)) { results.Add(new { client.InstanceId, stage = "waiting_status" }); continue; }
            client.BeginTask(taskKey);
            var carriedInput = InventoryCount(status, inputItemId); var carriedFuel = InventoryCount(status, fuelItemId); var carriedResult = InventoryCount(status, resultItemId);
            if (client.TaskStage == "complete") { results.Add(new { client.InstanceId, stage = "complete", inputItemId, carriedInput, fuelItemId, carriedFuel, resultItemId, carriedResult }); continue; }
            if (client.TaskStage == "depositing_smelted")
            {
                if (carriedInput == 0 && carriedFuel == 0 && carriedResult == 0) { client.TaskStage = "complete"; results.Add(new { client.InstanceId, stage = "complete", resultItemId, resultCount = 0 }); continue; }
                client.TaskStage = "at_chest";
            }

            if (client.TaskStage is "to_chest" or "at_chest")
            {
                var chest = FindHomeBlock(status, homeX, homeY, homeZ, "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel");
                if (chest is null) { results.Add(new { client.InstanceId, stage = "no_storage_near_home", carriedInput, carriedFuel, carriedResult }); continue; }
                var chestDistance = DistanceSquared(status.X, status.Y, status.Z, chest.X, chest.Y, chest.Z);
                if (chestDistance > 25)
                {
                    var approach = ChestApproach(status, chest); if (client.ShouldSendNavigation($"smelt-chest:{chest.X}:{chest.Y}:{chest.Z}:{approach.X}:{approach.Y}:{approach.Z}")) await client.SendCommandSequenceAsync("#stop", $"#goto {approach.X} {approach.Y} {approach.Z}");
                    client.TaskStage = "to_chest"; results.Add(new { client.InstanceId, stage = "to_chest", chest, approach = new { x = approach.X, y = approach.Y, z = approach.Z }, carriedResult }); continue;
                }
                var openSentAt = DateTimeOffset.UtcNow; await client.SendAsync(new { type = "action", operation = "open_block", x = chest.X, y = chest.Y, z = chest.Z });
                var opened = await client.WaitForFreshStatusAsync(openSentAt, IsBlockContainerOpen, TimeSpan.FromSeconds(4));
                if (opened is null) { client.TaskStage = "at_chest"; results.Add(new { client.InstanceId, stage = "open_chest_failed", chest }); continue; }
                var depositSentAt = DateTimeOffset.UtcNow;
                await client.SendAsync(new { type = "action", operation = "deposit_item", item = resultItemId });
                await Task.Delay(150); await client.SendAsync(new { type = "action", operation = "deposit_item", item = fuelItemId });
                await Task.Delay(150); await client.SendAsync(new { type = "action", operation = "deposit_item", item = inputItemId });
                var deposited = await client.WaitForFreshStatusAsync(depositSentAt, x => InventoryCount(x, resultItemId) == 0 && InventoryCount(x, fuelItemId) == 0 && InventoryCount(x, inputItemId) == 0, TimeSpan.FromSeconds(4));
                await client.SendAsync(new { type = "action", operation = "close_screen" });
                if (deposited is not null) { client.TaskStage = "complete"; results.Add(new { client.InstanceId, stage = "complete", chest, depositedResultCount = carriedResult }); continue; }
                var latest = client.LastStatus ?? status; client.TaskStage = "at_chest"; results.Add(new { client.InstanceId, stage = "deposit_incomplete", chest, remainingInput = InventoryCount(latest, inputItemId), remainingFuel = InventoryCount(latest, fuelItemId), remainingResult = InventoryCount(latest, resultItemId) }); continue;
            }

            if (client.TaskStage is not ("smelting" or "collecting_output"))
            {
                if (carriedInput < inputCount)
                {
                    if (client.TaskStage != "mining_input" || (!status.BaritoneWorking && client.ShouldSendNavigation($"mine-input:{taskKey}"))) await StartMiningWithQuotaAsync(client, inputItemId, carriedInput, inputCount - carriedInput, "mining_input");
                    client.TaskStage = "mining_input"; results.Add(new { client.InstanceId, stage = "mining_input", inputItemId, targetItemCount = carriedInput, requiredCount = inputCount, status.BaritoneWorking }); continue;
                }
                if (carriedFuel < fuelCount)
                {
                    if (client.TaskStage != "mining_fuel" || (!status.BaritoneWorking && client.ShouldSendNavigation($"mine-fuel:{taskKey}"))) await StartMiningWithQuotaAsync(client, fuelItemId, carriedFuel, fuelCount - carriedFuel, "mining_fuel");
                    client.TaskStage = "mining_fuel"; results.Add(new { client.InstanceId, stage = "mining_fuel", fuelItemId, targetItemCount = carriedFuel, requiredCount = fuelCount, status.BaritoneWorking }); continue;
                }
            }

            var homeHorizontalDistance = HorizontalDistanceSquared(status.X, status.Z, homeX, homeZ);
            var furnace = FindHomeBlock(status, homeX, homeY, homeZ, "minecraft:furnace", "minecraft:blast_furnace");
            if (furnace is null && homeHorizontalDistance > 10L * 10L)
            {
                if (client.ShouldSendNavigation($"smelt-home:{homeX}:{homeY}:{homeZ}")) await client.SendCommandSequenceAsync("#stop", $"#goto {homeX} {homeY} {homeZ}");
                client.TaskStage = client.TaskStage == "smelting" ? "smelting" : "to_home"; results.Add(new { client.InstanceId, stage = "to_home", carriedInput, carriedFuel, horizontalDistanceSquared = homeHorizontalDistance }); continue;
            }
            if (furnace is null) { results.Add(new { client.InstanceId, stage = "no_furnace_near_home", carriedInput, carriedFuel, carriedResult }); continue; }
            var furnaceDistance = DistanceSquared(status.X, status.Y, status.Z, furnace.X, furnace.Y, furnace.Z);
            if (furnaceDistance > 25)
            {
                var approach = ChestApproach(status, furnace); if (client.ShouldSendNavigation($"furnace:{furnace.X}:{furnace.Y}:{furnace.Z}:{approach.X}:{approach.Y}:{approach.Z}")) await client.SendCommandSequenceAsync("#stop", $"#goto {approach.X} {approach.Y} {approach.Z}");
                if (client.TaskStage != "smelting") client.TaskStage = "to_furnace"; results.Add(new { client.InstanceId, stage = "to_furnace", furnace, approach = new { x = approach.X, y = approach.Y, z = approach.Z }, carriedInput, carriedFuel }); continue;
            }

            var furnaceOpenSentAt = DateTimeOffset.UtcNow; await client.SendAsync(new { type = "action", operation = "open_block", x = furnace.X, y = furnace.Y, z = furnace.Z });
            var furnaceStatus = await client.WaitForFreshStatusAsync(furnaceOpenSentAt, IsFurnaceOpen, TimeSpan.FromSeconds(4));
            if (furnaceStatus is null) { results.Add(new { client.InstanceId, stage = "open_furnace_failed", furnace }); continue; }

            if (client.TaskStage is not ("smelting" or "collecting_output"))
            {
                var loadSentAt = DateTimeOffset.UtcNow; await client.SendAsync(new { type = "action", operation = "deposit_item", item = inputItemId }); await Task.Delay(200); await client.SendAsync(new { type = "action", operation = "deposit_item", item = fuelItemId });
                var loaded = await client.WaitForFreshStatusAsync(loadSentAt, x => ContainerCount(x, inputItemId) > 0 && (ContainerCount(x, fuelItemId) > 0 || x.FurnaceLit), TimeSpan.FromSeconds(4));
                await client.SendAsync(new { type = "action", operation = "close_screen" });
                if (loaded is null) { client.TaskStage = "to_furnace"; results.Add(new { client.InstanceId, stage = "load_furnace_failed", furnace, message = "熔炉可能已有不兼容物品、燃料槽已满或交互失败，下一周期重试。" }); continue; }
                client.TaskStage = "smelting"; results.Add(new { client.InstanceId, stage = "smelting", furnace, furnaceInput = ContainerCount(loaded, inputItemId), furnaceFuel = ContainerCount(loaded, fuelItemId), furnaceOutput = ContainerCount(loaded, resultItemId), loaded.FurnaceLit, loaded.FurnaceBurnProgress, loaded.FurnaceLitProgress, requiredOutput = inputCount }); continue;
            }

            var furnaceInput = ContainerCount(furnaceStatus, inputItemId); var furnaceFuel = ContainerCount(furnaceStatus, fuelItemId); var furnaceOutput = ContainerCount(furnaceStatus, resultItemId);
            if (furnaceOutput < inputCount)
            {
                await client.SendAsync(new { type = "action", operation = "close_screen" });
                results.Add(new { client.InstanceId, stage = "smelting", furnace, furnaceInput, furnaceFuel, furnaceOutput, furnaceStatus.FurnaceLit, furnaceStatus.FurnaceBurnProgress, furnaceStatus.FurnaceLitProgress, requiredOutput = inputCount }); continue;
            }
            var withdrawSentAt = DateTimeOffset.UtcNow; await client.SendAsync(new { type = "action", operation = "withdraw_item", item = resultItemId }); await Task.Delay(200); await client.SendAsync(new { type = "action", operation = "withdraw_item", item = fuelItemId }); await Task.Delay(200); await client.SendAsync(new { type = "action", operation = "withdraw_item", item = inputItemId });
            var withdrawn = await client.WaitForFreshStatusAsync(withdrawSentAt, x => InventoryCount(x, resultItemId) > carriedResult, TimeSpan.FromSeconds(4));
            await client.SendAsync(new { type = "action", operation = "close_screen" });
            if (withdrawn is null) { client.TaskStage = "collecting_output"; results.Add(new { client.InstanceId, stage = "take_furnace_output_failed", furnace, furnaceOutput }); continue; }
            client.TaskStage = "to_chest"; results.Add(new { client.InstanceId, stage = "to_chest", furnace, collectedResultCount = InventoryCount(withdrawn, resultItemId), remainingFuelCollected = InventoryCount(withdrawn, fuelItemId) });
        }
        return new { taskKey, inputItemId, inputCount, fuelItemId, fuelCount, resultItemId, skippedPrimaryProtected = selection.SkippedPrimary, results };
    }
    private static bool IsBlockContainerOpen(StatusMessage status) => !string.IsNullOrWhiteSpace(status.OpenContainer) && status.OpenContainer != "InventoryMenu" && status.ContainerSlots?.Any(x => x.Section == "block_container") == true;
    private static bool IsFurnaceOpen(StatusMessage status) => status.OpenContainer?.EndsWith("FurnaceMenu", StringComparison.Ordinal) == true && status.ContainerSlots?.Any(x => x.Section == "block_container") == true;
    private static int InventoryCount(StatusMessage status, string itemId) => status.Inventory?.Where(x => x.Item == itemId).Sum(x => x.Count) ?? 0;
    private static int ContainerCount(StatusMessage status, string itemId) => status.ContainerSlots?.Where(x => x.Section == "block_container" && x.Item == itemId).Sum(x => x.Count) ?? 0;
    private static string NormalizeItemId(string itemId) => FastItemId(itemId) ?? (itemId.Contains(':') ? itemId : "minecraft:" + itemId);
    private static string? FastItemId(string value)
    {
        var normalized = value.Trim().ToLowerInvariant();
        return normalized switch
        {
            "石头" or "圆石" or "石块" or "cobblestone" or "minecraft:cobblestone" => "minecraft:cobblestone",
            "煤" or "煤炭" or "coal" or "minecraft:coal" => "minecraft:coal",
            "铁" or "铁矿" or "铁矿石" or "粗铁" or "raw_iron" or "minecraft:raw_iron" => "minecraft:raw_iron",
            "金" or "金矿" or "金矿石" or "粗金" or "raw_gold" or "minecraft:raw_gold" => "minecraft:raw_gold",
            "钻石" or "diamond" or "minecraft:diamond" => "minecraft:diamond",
            "泥土" or "dirt" or "minecraft:dirt" => "minecraft:dirt",
            "沙子" or "sand" or "minecraft:sand" => "minecraft:sand",
            "砂砾" or "沙砾" or "gravel" or "minecraft:gravel" => "minecraft:gravel",
            "橡木" or "橡木原木" or "oak_log" or "minecraft:oak_log" => "minecraft:oak_log",
            "樱花木" or "樱花原木" or "cherry_log" or "minecraft:cherry_log" => "minecraft:cherry_log",
            "木头" or "原木" or "log" => "minecraft:oak_log",
            _ when normalized.StartsWith("minecraft:", StringComparison.Ordinal) => normalized,
            _ when Regex.IsMatch(normalized, "^[a-z0-9_]+$") => "minecraft:" + normalized,
            _ => null
        };
    }
    private static NearbyBlock? FindHomeBlock(StatusMessage status, int homeX, int homeY, int homeZ, params string[] blockIds) => status.FunctionalBlocks?.Where(x => blockIds.Contains(x.Block)).Where(x => HorizontalDistanceSquared(x.X, x.Z, homeX, homeZ) <= 24L * 24L && Math.Abs(x.Y - homeY) <= 24).OrderBy(x => DistanceSquared(status.X, status.Y, status.Z, x.X, x.Y, x.Z)).FirstOrDefault();
    private static (int X, int Y, int Z) ChestApproach(StatusMessage status, NearbyBlock chest)
    {
        var occupiedFunctionalBlocks = status.FunctionalBlocks?.Select(x => (x.X, x.Y, x.Z)).ToHashSet() ?? [];
        var candidates = new[] { (chest.X - 1, chest.Y, chest.Z), (chest.X + 1, chest.Y, chest.Z), (chest.X, chest.Y, chest.Z - 1), (chest.X, chest.Y, chest.Z + 1) };
        return candidates.Where(x => !occupiedFunctionalBlocks.Contains(x)).OrderBy(x => DistanceSquared(status.X, status.Y, status.Z, x.Item1, x.Item2, x.Item3)).Select(x => (X: x.Item1, Y: x.Item2, Z: x.Item3)).FirstOrDefault((X: chest.X - 1, Y: chest.Y, Z: chest.Z));
    }
    private static long DistanceSquared(int ax, int ay, int az, int bx, int by, int bz) { long x = ax - bx, y = ay - by, z = az - bz; return x * x + y * y + z * z; }
    private static long HorizontalDistanceSquared(int ax, int az, int bx, int bz) { long x = ax - bx, z = az - bz; return x * x + z * z; }
    private static int TaskStageStep(string stage, int totalSteps) => stage switch
    {
        "complete" => totalSteps,
        "to_home" or "to_chest" or "at_home" or "no_storage_near_home" => 1,
        "at_chest" or "depositing" or "deposit_incomplete" or "open_chest_failed" => 2,
        _ => 0
    };
    private static string TaskStageLabel(string stage) => stage switch
    {
        "new" => "准备开始",
        "waiting_status" => "等待玩家状态",
        "mining" => "正在挖掘",
        "agent_mining" or "mining_input" or "mining_fuel" => "正在按背包配额挖掘",
        "mining_quota_complete" => "已达到背包数量并停止 Baritone",
        "mining_quota_stopped" => "挖掘已因超时、无进展或提前空闲而停止",
        "to_home" => "正在前往指定坐标",
        "at_home" => "已到指定坐标，寻找容器",
        "no_storage_near_home" => "附近没有找到容器",
        "to_chest" => "正在接近容器",
        "at_chest" => "正在打开容器",
        "open_chest_failed" => "打开容器失败，准备重试",
        "depositing" => "正在放入物品",
        "deposit_incomplete" => "放入未完成，准备重试",
        "complete" => "已验证全部放入容器",
        "stopped" => "已停止",
        _ => stage
    };
    private static string MineTargets(string itemId) => itemId switch { "minecraft:cobblestone" => "stone", "minecraft:diamond" => "diamond_ore deepslate_diamond_ore", "minecraft:coal" => "coal_ore deepslate_coal_ore", "minecraft:raw_iron" => "iron_ore deepslate_iron_ore", "minecraft:raw_gold" => "gold_ore deepslate_gold_ore", _ => itemId["minecraft:".Length..] };
    private TargetSelection ResolveAiTargets(string target)
    {
        var raw = target == "all" ? clients.Values.ToList() : clients.TryGetValue(target, out var one) ? [one] : [];
        var eligible = raw.Where(x => !x.IsPrimary || x.AiControlAllowed).ToList();
        return new(eligible, raw.Count - eligible.Count);
    }
    private TargetSelection ResolveAllTargets(string target)
    {
        var targets = target == "all" ? clients.Values.ToList() : clients.TryGetValue(target, out var one) ? [one] : [];
        return new(targets, 0);
    }
    private void ApplyRolesLocked()
    {
        foreach (var client in clients.Values)
        {
            client.IsPrimary = client.InstanceId == primaryInstanceId;
            client.AiControlAllowed = !client.IsPrimary || primaryAiControlAllowed;
            client.IsPossessionTarget = client.InstanceId == possessionTargetId;
        }
    }
    private bool RestoreOrRefreshPrimaryIdentity(ClientConnection connection, StatusMessage status)
    {
        var settingsChanged = false;
        lock (clusterStateGate)
        {
            var matchesSavedIdentity =
                (!string.IsNullOrWhiteSpace(preferredPrimaryInstanceId) && connection.InstanceId == preferredPrimaryInstanceId) ||
                (!string.IsNullOrWhiteSpace(preferredPrimaryPlayerUuid) && string.Equals(status.PlayerUuid, preferredPrimaryPlayerUuid, StringComparison.OrdinalIgnoreCase)) ||
                (!string.IsNullOrWhiteSpace(preferredPrimaryPlayerName) && string.Equals(status.PlayerName, preferredPrimaryPlayerName, StringComparison.OrdinalIgnoreCase));

            if (primaryInstanceId is null && matchesSavedIdentity)
            {
                primaryInstanceId = connection.InstanceId;
                possessionTargetId = null;
                clusterStateVersion++;
                ApplyRolesLocked();
            }

            if (primaryInstanceId == connection.InstanceId &&
                (preferredPrimaryInstanceId != connection.InstanceId ||
                 !string.Equals(preferredPrimaryPlayerUuid, status.PlayerUuid, StringComparison.OrdinalIgnoreCase) ||
                 !string.Equals(preferredPrimaryPlayerName, status.PlayerName, StringComparison.OrdinalIgnoreCase)))
            {
                preferredPrimaryInstanceId = connection.InstanceId;
                preferredPrimaryPlayerUuid = status.PlayerUuid;
                preferredPrimaryPlayerName = status.PlayerName;
                settingsChanged = true;
            }
        }
        if (settingsChanged) SavePersistentSettings();
        return settingsChanged;
    }
    private async Task BroadcastClusterStateAsync()
    {
        var snapshot = Snapshot(); string? primary; string? possession; bool aiAllowed; bool allowBreak; bool showPlayers; bool showRoutes; bool showAiReplies; bool showProgress; List<string> hardBreakRules; List<string> softBreakRules; long version;
        lock (clusterStateGate) { primary = primaryInstanceId; possession = possessionTargetId; aiAllowed = primaryAiControlAllowed; allowBreak = allowBaritoneBreak; showPlayers = showControllablePlayerBoxes; showRoutes = showBaritoneRoutes; showAiReplies = showAiRepliesInChat; showProgress = showTaskProgress; hardBreakRules = [.. blocksToDisallowBreaking]; softBreakRules = [.. blocksToAvoidBreaking]; version = clusterStateVersion; ApplyRolesLocked(); }
        var instances = snapshot.Select(x => new { x.InstanceId, playerName = x.LastStatus?.PlayerName ?? "", x.IsPrimary, x.AiControlAllowed, x.IsPossessionTarget }).ToList();
        var players = MapSnapshot().Players.Select(x => new { x.Uuid, x.Name, x.Dimension, x.PositionAvailable, x.X, x.Y, x.Z, x.IsControllable, x.InstanceId }).ToList();
        var routes = snapshot.Where(x => x.LastStatus?.BaritonePath?.Count > 0).Select(x => new
        {
            x.InstanceId,
            playerName = x.LastStatus!.PlayerName,
            dimension = x.LastStatus.Dimension ?? "minecraft:overworld",
            points = x.LastStatus.BaritonePath!.Take(256).ToList()
        }).ToList();
        var taskProgress = ActiveTaskProgressSnapshot();
        foreach (var local in snapshot)
        {
            local.ExpectedClusterStateVersion = version;
            try { await local.SendAsync(new { type = "cluster_state", clusterStateVersion = version, localInstanceId = local.InstanceId, primaryInstanceId = primary, primaryAiControlAllowed = aiAllowed, possessionTargetId = possession, isPrimary = local.InstanceId == primary, isPossessionTarget = local.InstanceId == possession, allowBaritoneBreak = allowBreak, showControllablePlayerBoxes = showPlayers, showBaritoneRoutes = showRoutes, showAiRepliesInChat = showAiReplies, showTaskProgress = showProgress, blocksToDisallowBreaking = hardBreakRules, blocksToAvoidBreaking = softBreakRules, taskProgress, instances, players, routes }); }
            catch (IOException) { }
        }
    }
    public async Task<bool> WaitForClusterStateAckAsync(string instanceId, TimeSpan timeout)
    {
        var until = DateTimeOffset.UtcNow + timeout;
        while (DateTimeOffset.UtcNow < until)
        {
            if (!clients.TryGetValue(instanceId, out var client)) return false;
            if (client.ClusterStateAckVersion >= client.ExpectedClusterStateVersion && client.ClientReportsPrimary == client.IsPrimary) return true;
            await Task.Delay(100);
        }
        return false;
    }
    private void HandleClusterStateAck(ClientConnection source, JsonElement root)
    {
        source.ClusterStateAckVersion = root.TryGetProperty("clusterStateVersion", out var version) ? version.GetInt64() : 0;
        source.ClientReportsPrimary = root.TryGetProperty("isPrimary", out var isPrimary) && isPrimary.GetBoolean();
        _ = source.PersistStatusAsync();
        Changed?.Invoke();
    }
    private async Task HandlePossessionSelectAsync(ClientConnection source, JsonElement root)
    {
        if (!source.IsPrimary) { await source.SendAsync(new { type = "possession_select_ack", accepted = false, error = "该实例不是 C# 当前确认的主要玩家" }); return; }
        var requested = root.TryGetProperty("targetInstanceId", out var targetElement) ? targetElement.GetString() : null;
        string? acceptedTarget;
        lock (clusterStateGate)
        {
            possessionTargetId = !string.IsNullOrWhiteSpace(requested) && requested != source.InstanceId && clients.ContainsKey(requested) ? requested : null;
            acceptedTarget = possessionTargetId;
            clusterStateVersion++;
            ApplyRolesLocked();
        }
        Changed?.Invoke(); await BroadcastClusterStateAsync();
        var accepted = string.IsNullOrWhiteSpace(requested) || acceptedTarget == requested;
        await source.SendAsync(new { type = "possession_select_ack", accepted, targetInstanceId = acceptedTarget, error = accepted ? (string?)null : "请求的目标实例已经离线或实例 ID 无效" });
    }
    private async Task HandleRemoteControlAsync(ClientConnection source, JsonElement root)
    {
        string? targetId; lock (clusterStateGate) targetId = source.IsPrimary ? possessionTargetId : null;
        if (targetId is null || !clients.TryGetValue(targetId, out var target)) return;
        await target.SendAsync(new { type = "remote_control", sourceInstanceId = source.InstanceId, input = root.Clone() });
    }
    private async Task HandleRemoteStateAsync(ClientConnection source, JsonElement root)
    {
        string? primaryId; bool sourceIsTarget;
        lock (clusterStateGate) { primaryId = primaryInstanceId; sourceIsTarget = source.InstanceId == possessionTargetId; }
        if (!sourceIsTarget || primaryId is null || !clients.TryGetValue(primaryId, out var primary)) return;
        source.LastRemoteStateReceivedAt = DateTimeOffset.UtcNow;
        source.LastRemoteStateSequence = root.TryGetProperty("sequence", out var sequence) ? sequence.GetInt64() : 0;
        await primary.SendAsync(new { type = "remote_state", sourceInstanceId = source.InstanceId, state = root.Clone() });
    }
    private async Task HandleRemoteChunkAsync(ClientConnection source, JsonElement root)
    {
        string? primaryId; bool sourceIsTarget;
        lock (clusterStateGate) { primaryId = primaryInstanceId; sourceIsTarget = source.InstanceId == possessionTargetId; }
        if (!sourceIsTarget || primaryId is null || !clients.TryGetValue(primaryId, out var primary)) return;
        source.LastRemoteChunkReceivedAt = DateTimeOffset.UtcNow;
        source.LastRemoteChunkSequence = root.TryGetProperty("sequence", out var sequence) ? sequence.GetInt64() : 0;
        await primary.SendAsync(new { type = "remote_chunk", sourceInstanceId = source.InstanceId, chunk = root.Clone() });
    }
    private async Task HandleRemoteUiClickAsync(ClientConnection source, JsonElement root)
    {
        string? targetId = null;
        if (source.IsPrimary && root.TryGetProperty("targetInstanceId", out var requestedTarget)) targetId = requestedTarget.GetString();
        if (source.IsPrimary && string.IsNullOrWhiteSpace(targetId)) lock (clusterStateGate) targetId = possessionTargetId;
        if (targetId is null || !clients.TryGetValue(targetId, out var target)) return;
        await target.SendAsync(new { type = "remote_ui_click", sourceInstanceId = source.InstanceId, click = root.Clone() });
    }
    private async Task HandleRemoteInspectAsync(ClientConnection source, JsonElement root)
    {
        if (!source.IsPrimary || !root.TryGetProperty("targetInstanceId", out var targetElement)) return;
        var targetId = targetElement.GetString();
        if (string.IsNullOrWhiteSpace(targetId) || targetId == source.InstanceId || !clients.TryGetValue(targetId, out var target)) return;
        var operation = root.TryGetProperty("operation", out var operationElement) ? operationElement.GetString() ?? "" : "";
        await target.SendAsync(new { type = "remote_inspect", sourceInstanceId = source.InstanceId, operation });
    }
    private async Task HandleRemoteInspectStateAsync(ClientConnection source, JsonElement root)
    {
        string? primaryId; lock (clusterStateGate) primaryId = primaryInstanceId;
        if (source.IsPrimary || primaryId is null || !clients.TryGetValue(primaryId, out var primary) || !root.TryGetProperty("state", out var state)) return;
        var operation = root.TryGetProperty("operation", out var operationElement) ? operationElement.GetString() ?? "" : "";
        await primary.SendAsync(new { type = "remote_inspect_state", sourceInstanceId = source.InstanceId, operation, state = state.Clone() });
    }
    private async Task HandleRemoteWorldActionAsync(ClientConnection source, JsonElement root)
    {
        if (!source.IsPrimary || !root.TryGetProperty("targetInstanceId", out var targetElement)) return;
        var targetId = targetElement.GetString();
        if (string.IsNullOrWhiteSpace(targetId) || targetId == source.InstanceId || !clients.TryGetValue(targetId, out var target)) return;
        await target.SendAsync(new { type = "remote_world_action", sourceInstanceId = source.InstanceId, action = root.Clone() });
    }
    private static bool TryReadBlockRuleList(JsonElement root, string propertyName, out List<string> values, out string error)
    {
        values = [];
        error = "";
        if (!root.TryGetProperty(propertyName, out var element) || element.ValueKind != JsonValueKind.Array)
        {
            error = $"缺少方块规则数组：{propertyName}";
            return false;
        }
        foreach (var item in element.EnumerateArray().Take(513))
        {
            if (values.Count >= 512) { error = "单类方块规则最多 512 个"; return false; }
            if (item.ValueKind != JsonValueKind.String) { error = "方块规则必须是方块注册 ID"; return false; }
            var id = (item.GetString() ?? "").Trim().ToLowerInvariant();
            if (!BlockIdPattern.IsMatch(id)) { error = $"无效的方块注册 ID：{id}"; return false; }
            if (!values.Contains(id, StringComparer.Ordinal)) values.Add(id);
        }
        return true;
    }
    private static string[] BlockRuleCommands(IReadOnlyList<string> hard, IReadOnlyList<string> soft)
    {
        var hardCommand = hard.Count == 0 ? "#set reset blocksToDisallowBreaking" : "#set blocksToDisallowBreaking " + string.Join(',', hard);
        // Baritone's list parser cannot represent an empty list; structure_void is a harmless practical sentinel.
        var softCommand = "#set blocksToAvoidBreaking " + (soft.Count == 0 ? "minecraft:structure_void" : string.Join(',', soft));
        return [hardCommand, softCommand];
    }
    private async Task HandleClusterUserCommandAsync(ClientConnection source, JsonElement root)
    {
        if (!source.IsPrimary) return;
        var operation = root.TryGetProperty("operation", out var operationElement) ? operationElement.GetString() ?? "" : "";
        var targetId = root.TryGetProperty("targetInstanceId", out var targetElement) ? targetElement.GetString() : null;
        if (operation == "stop_baritone")
        {
            if (string.IsNullOrWhiteSpace(targetId) || !clients.ContainsKey(targetId)) { await source.SendAsync(new { type = "cluster_user_command_ack", accepted = false, operation, error = "目标实例无效" }); return; }
            var result = await StopBaritoneAsync(targetId, bypassPrimaryProtection: true);
            await source.SendAsync(new { type = "cluster_user_command_ack", accepted = true, operation, targetInstanceId = targetId, result });
            return;
        }
        if (operation == "set_cluster_settings")
        {
            bool previousAllowBreak; bool updatedAllowBreak;
            lock (clusterStateGate)
            {
                previousAllowBreak = allowBaritoneBreak;
                if (root.TryGetProperty("allowBaritoneBreak", out var allowBreakElement)) allowBaritoneBreak = allowBreakElement.GetBoolean();
                if (root.TryGetProperty("showControllablePlayerBoxes", out var showPlayersElement)) showControllablePlayerBoxes = showPlayersElement.GetBoolean();
                if (root.TryGetProperty("showBaritoneRoutes", out var showRoutesElement)) showBaritoneRoutes = showRoutesElement.GetBoolean();
                if (root.TryGetProperty("showAiRepliesInChat", out var showAiRepliesElement)) showAiRepliesInChat = showAiRepliesElement.GetBoolean();
                if (root.TryGetProperty("showTaskProgress", out var showProgressElement)) showTaskProgress = showProgressElement.GetBoolean();
                updatedAllowBreak = allowBaritoneBreak;
                clusterStateVersion++;
            }
            if (previousAllowBreak != updatedAllowBreak)
                foreach (var client in Snapshot()) await client.SendCommandDirectAsync($"#set allowBreak {updatedAllowBreak.ToString().ToLowerInvariant()}");
            SavePersistentSettings();
            await BroadcastClusterStateAsync();
            await source.SendAsync(new { type = "cluster_user_command_ack", accepted = true, operation, allowBaritoneBreak = updatedAllowBreak, showControllablePlayerBoxes, showBaritoneRoutes, showAiRepliesInChat, showTaskProgress });
            return;
        }
        if (operation == "set_block_mining_rules")
        {
            if (!TryReadBlockRuleList(root, "blocksToDisallowBreaking", out var hard, out var hardError))
            {
                await source.SendAsync(new { type = "cluster_user_command_ack", accepted = false, operation, error = hardError });
                return;
            }
            if (!TryReadBlockRuleList(root, "blocksToAvoidBreaking", out var soft, out var softError))
            {
                await source.SendAsync(new { type = "cluster_user_command_ack", accepted = false, operation, error = softError });
                return;
            }
            var hardSet = hard.ToHashSet(StringComparer.Ordinal);
            soft.RemoveAll(hardSet.Contains);
            lock (clusterStateGate)
            {
                blocksToDisallowBreaking = hard;
                blocksToAvoidBreaking = soft;
                clusterStateVersion++;
            }
            SavePersistentSettings();
            var commands = BlockRuleCommands(hard, soft);
            foreach (var client in Snapshot()) await client.SendCommandSequenceAsync(commands);
            await BroadcastClusterStateAsync();
            await source.SendAsync(new { type = "cluster_user_command_ack", accepted = true, operation, blocksToDisallowBreaking = hard, blocksToAvoidBreaking = soft });
            return;
        }
        if (operation != "follow_player" || string.IsNullOrWhiteSpace(targetId) || !clients.ContainsKey(targetId)) { await source.SendAsync(new { type = "cluster_user_command_ack", accepted = false, operation, error = "目标实例无效或操作不支持" }); return; }
        var playerName = root.TryGetProperty("playerName", out var playerElement) ? playerElement.GetString() ?? "" : "";
        if (string.IsNullOrWhiteSpace(playerName) || playerName.Length > 32 || playerName.Any(ch => !(char.IsLetterOrDigit(ch) || ch is '_')))
        {
            await source.SendAsync(new { type = "cluster_user_command_ack", accepted = false, operation, error = "玩家名称无效" }); return;
        }
        await UserFollowAsync(targetId, playerName);
        await source.SendAsync(new { type = "cluster_user_command_ack", accepted = true, operation, targetInstanceId = targetId, playerName });
    }
    private void HandleMapTile(ClientConnection source, JsonElement root)
    {
        if (!root.TryGetProperty("dimension", out var dimensionElement)) return;
        var dimension = dimensionElement.GetString();
        if (string.IsNullOrWhiteSpace(dimension)) return;
        var chunkX = root.TryGetProperty("chunkX", out var chunkXElement) ? chunkXElement.GetInt32() : 0;
        var chunkZ = root.TryGetProperty("chunkZ", out var chunkZElement) ? chunkZElement.GetInt32() : 0;
        lock (mapGate)
        {
            if (root.TryGetProperty("materials", out var materialArray) && materialArray.ValueKind == JsonValueKind.Array)
                foreach (var materialElement in materialArray.EnumerateArray().Take(512))
                {
                    var id = materialElement.TryGetProperty("id", out var idElement) ? idElement.GetString() : null;
                    var width = materialElement.TryGetProperty("width", out var widthElement) ? widthElement.GetInt32() : 0;
                    var height = materialElement.TryGetProperty("height", out var heightElement) ? heightElement.GetInt32() : 0;
                    var pixels = materialElement.TryGetProperty("pixels", out var pixelsElement) ? pixelsElement.GetString() : null;
                    if (string.IsNullOrWhiteSpace(id) || width is < 1 or > 64 || height is < 1 or > 64 || string.IsNullOrWhiteSpace(pixels)) continue;
                    try
                    {
                        var data = Convert.FromBase64String(pixels);
                        if (data.Length == width * height * 4) mapMaterials[id] = new(id, width, height, data);
                    }
                    catch (FormatException) { }
                }
            if (root.TryGetProperty("blocks", out var blockArray) && blockArray.ValueKind == JsonValueKind.Array)
                foreach (var blockElement in blockArray.EnumerateArray().Take(1024))
                {
                    var x = blockElement.TryGetProperty("x", out var xElement) ? xElement.GetInt32() : 0;
                    var y = blockElement.TryGetProperty("y", out var yElement) ? yElement.GetInt32() : 0;
                    var z = blockElement.TryGetProperty("z", out var zElement) ? zElement.GetInt32() : 0;
                    if ((x >> 4) != chunkX || (z >> 4) != chunkZ) continue;
                    var block = blockElement.TryGetProperty("block", out var blockElementValue) ? blockElementValue.GetString() ?? "minecraft:air" : "minecraft:air";
                    var material = blockElement.TryGetProperty("material", out var materialElement) ? materialElement.GetString() ?? block : block;
                    var color = blockElement.TryGetProperty("color", out var colorElement) ? colorElement.GetInt32() : unchecked((int)0xFF555555);
                    mapBlocks[new(dimension, x, z)] = new(dimension, x, y, z, block, material, color);
                }
        }
        source.LastMapTileReceivedAt = DateTimeOffset.UtcNow;
        source.LastMapTileSequence = root.TryGetProperty("sequence", out var sequenceElement) ? sequenceElement.GetInt64() : 0;
    }
    private async Task AcceptLoop(CancellationToken ct) { while (!ct.IsCancellationRequested) { try { _ = HandleAsync(await listener.AcceptTcpClientAsync(ct)); } catch (OperationCanceledException) { } } }
    private async Task HandleAsync(TcpClient tcp)
    {
        using var tcpOwner = tcp; using var reader = new StreamReader(tcp.GetStream(), Encoding.UTF8, false, leaveOpen: true); using var writer = new StreamWriter(tcp.GetStream(), new UTF8Encoding(false), leaveOpen: true) { AutoFlush = true };
        ClientConnection? c = null;
        try
        {
            var auth = JsonSerializer.Deserialize<HelloMessage>(await reader.ReadLineAsync() ?? "", JsonProtocol.Options);
            if (auth?.Type != "hello" || auth.Token != token || string.IsNullOrWhiteSpace(auth.InstanceId)) return;
            c = new ClientConnection(auth.InstanceId, writer); clients[auth.InstanceId] = c;
            bool currentAllowBreak; List<string> currentHardRules; List<string> currentSoftRules;
            lock (clusterStateGate)
            {
                if (primaryInstanceId is null && auth.InstanceId == preferredPrimaryInstanceId) primaryInstanceId = auth.InstanceId;
                clusterStateVersion++;
                ApplyRolesLocked();
                currentAllowBreak = allowBaritoneBreak;
                currentHardRules = [.. blocksToDisallowBreaking];
                currentSoftRules = [.. blocksToAvoidBreaking];
            }
            await c.SendCommandDirectAsync($"#set allowBreak {currentAllowBreak.ToString().ToLowerInvariant()}");
            await c.SendCommandSequenceAsync(BlockRuleCommands(currentHardRules, currentSoftRules));
            Changed?.Invoke(); await BroadcastClusterStateAsync();
            while (await reader.ReadLineAsync() is { } line)
            {
                using var document = JsonDocument.Parse(line); var root = document.RootElement;
                if (!root.TryGetProperty("type", out var typeElement)) continue;
                switch (typeElement.GetString())
                {
                    case "status":
                        var status = root.Deserialize<StatusMessage>(JsonProtocol.Options); if (status is null) break;
                        var finished = c.UpdateStatus(status);
                        var miningQuotaResult = c.ObserveMiningQuota(status);
                        if (miningQuotaResult is not null)
                        {
                            await c.SendCommandDirectAsync("#stop");
                            c.TaskStage = miningQuotaResult.Reached ? "mining_quota_complete" : "mining_quota_stopped";
                        }
                        RestoreOrRefreshPrimaryIdentity(c, status);
                        _ = c.PersistStatusAsync(); Changed?.Invoke(); await BroadcastClusterStateAsync();
                        if (finished || miningQuotaResult is not null)
                            BaritoneWorkFinished?.Invoke(new(c.InstanceId, status.PlayerName, status.BaritoneWorkSequence, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
                        break;
                    case "cluster_state_ack": HandleClusterStateAck(c, root); break;
                    case "possession_select": await HandlePossessionSelectAsync(c, root); break;
                    case "control_input": await HandleRemoteControlAsync(c, root); break;
                    case "remote_state": await HandleRemoteStateAsync(c, root); break;
                    case "remote_chunk": await HandleRemoteChunkAsync(c, root); break;
                    case "remote_ui_click": await HandleRemoteUiClickAsync(c, root); break;
                    case "remote_inspect": await HandleRemoteInspectAsync(c, root); break;
                    case "remote_inspect_state": await HandleRemoteInspectStateAsync(c, root); break;
                    case "remote_world_action": await HandleRemoteWorldActionAsync(c, root); break;
                    case "cluster_user_command": await HandleClusterUserCommandAsync(c, root); break;
                    case "map_tile": HandleMapTile(c, root); break;
                }
            }
        }
        catch (JsonException) { }
        finally
        {
            if (c is not null)
            {
                clients.TryRemove(new KeyValuePair<string, ClientConnection>(c.InstanceId, c));
                lock (clusterStateGate)
                {
                    if (possessionTargetId == c.InstanceId) possessionTargetId = null;
                    if (primaryInstanceId == c.InstanceId) primaryInstanceId = null;
                    clusterStateVersion++;
                    ApplyRolesLocked();
                }
                Changed?.Invoke(); await BroadcastClusterStateAsync();
            }
        }
    }
    public ValueTask DisposeAsync() { cancel?.Cancel(); listener.Stop(); return ValueTask.CompletedTask; }
}
public sealed class ClientConnection(string instanceId, StreamWriter writer) { private readonly SemaphoreSlim gate = new(1, 1); private readonly SemaphoreSlim cacheGate = new(1, 1); private string? lastCommand; private DateTimeOffset lastCommandAt; public string InstanceId { get; } = instanceId; public StatusMessage? LastStatus { get; set; }
    public bool IsPrimary { get; set; } public bool AiControlAllowed { get; set; } = true; public bool IsPossessionTarget { get; set; }
    public long ExpectedClusterStateVersion { get; set; } public long ClusterStateAckVersion { get; set; } public bool ClientReportsPrimary { get; set; }
    public DateTimeOffset LastRemoteStateReceivedAt { get; set; } public long LastRemoteStateSequence { get; set; }
    public DateTimeOffset LastRemoteChunkReceivedAt { get; set; } public long LastRemoteChunkSequence { get; set; }
    public DateTimeOffset LastMapTileReceivedAt { get; set; } public long LastMapTileSequence { get; set; }
    private string? navigationKey; private DateTimeOffset navigationSentAt;
    private long lastBaritoneWorkSequence = -1;
    private MiningQuota? activeMiningQuota;
    public MiningQuotaResult? LastMiningQuotaResult { get; private set; }
    public string? ActiveTaskKey { get; private set; } public string TaskStage { get; set; } = "new";
    public void BeginTask(string key) { if (ActiveTaskKey == key) return; ActiveTaskKey = key; TaskStage = "new"; }
    public MiningQuota? MiningQuotaSnapshot { get { lock (this) return activeMiningQuota; } }
    public void BeginMiningQuota(string itemId, int startCount, int targetCount, TimeSpan maximumDuration, TimeSpan noProgressTimeout)
    {
        lock (this)
        {
            var now = DateTimeOffset.UtcNow;
            activeMiningQuota = new(itemId, startCount, targetCount, startCount, now, now, maximumDuration, noProgressTimeout);
            LastMiningQuotaResult = null;
        }
    }
    public void CancelMiningQuota() { lock (this) activeMiningQuota = null; }
    public MiningQuotaResult? ObserveMiningQuota(StatusMessage status)
    {
        lock (this)
        {
            if (activeMiningQuota is null) return null;
            var quota = activeMiningQuota;
            var now = DateTimeOffset.UtcNow;
            var current = status.Inventory?.Where(item => item.Item == quota.ItemId).Sum(item => item.Count) ?? 0;
            if (current > quota.LastObservedCount)
                quota = quota with { LastObservedCount = current, LastProgressAt = now };

            string? reason = null;
            var reached = current >= quota.TargetCount;
            if (reached) reason = "inventory_quota_reached";
            else if (now - quota.StartedAt >= quota.MaximumDuration) reason = "maximum_mining_duration_reached";
            else if (now - quota.LastProgressAt >= quota.NoProgressTimeout) reason = "mining_no_progress_timeout";
            else if (now - quota.StartedAt >= TimeSpan.FromSeconds(5) && !status.BaritoneWorking) reason = "baritone_stopped_before_quota";

            if (reason is null) { activeMiningQuota = quota; return null; }
            activeMiningQuota = null;
            LastMiningQuotaResult = new(quota.ItemId, quota.StartCount, quota.TargetCount, current, reached, reason, quota.StartedAt, now);
            return LastMiningQuotaResult;
        }
    }
    public DateTimeOffset LastStatusReceivedAt { get; private set; }
    public bool UpdateStatus(StatusMessage status) { var finished = lastBaritoneWorkSequence >= 0 && status.BaritoneWorkSequence > lastBaritoneWorkSequence; lastBaritoneWorkSequence = status.BaritoneWorkSequence; LastStatus = status; LastStatusReceivedAt = DateTimeOffset.UtcNow; return finished; }
    public object CachedStatus() => new { connected = true, InstanceId, isPrimary = IsPrimary, aiControlAllowed = AiControlAllowed, isPossessionTarget = IsPossessionTarget, expectedClusterStateVersion = ExpectedClusterStateVersion, clusterStateAckVersion = ClusterStateAckVersion, clientReportsPrimary = ClientReportsPrimary, clusterStateSynchronized = ClusterStateAckVersion >= ExpectedClusterStateVersion && ClientReportsPrimary == IsPrimary, lastRemoteStateReceivedAt = LastRemoteStateReceivedAt, lastRemoteStateSequence = LastRemoteStateSequence, lastRemoteChunkReceivedAt = LastRemoteChunkReceivedAt, lastRemoteChunkSequence = LastRemoteChunkSequence, miningQuota = MiningQuotaSnapshot, lastMiningQuotaResult = LastMiningQuotaResult, receivedAt = LastStatusReceivedAt, ageMilliseconds = LastStatusReceivedAt == default ? -1 : (long)(DateTimeOffset.UtcNow - LastStatusReceivedAt).TotalMilliseconds, status = LastStatus };
    public async Task PersistStatusAsync() { await cacheGate.WaitAsync(); try { var dir = Path.Combine(AppContext.BaseDirectory, "status-cache"); Directory.CreateDirectory(dir); var safeId = string.Concat(InstanceId.Select(ch => char.IsLetterOrDigit(ch) || ch is '-' or '_' ? ch : '_')); await File.WriteAllTextAsync(Path.Combine(dir, safeId + ".json"), JsonSerializer.Serialize(CachedStatus(), JsonProtocol.Options)); } finally { cacheGate.Release(); } }
    public bool TryBeginCommand(string command) { lock (this) { if (lastCommand == command && DateTimeOffset.UtcNow - lastCommandAt < TimeSpan.FromMinutes(10)) return false; lastCommand = command; lastCommandAt = DateTimeOffset.UtcNow; return true; } }
    public bool ShouldSendNavigation(string key) { lock (this) { if (navigationKey == key && DateTimeOffset.UtcNow - navigationSentAt < TimeSpan.FromSeconds(75)) return false; navigationKey = key; navigationSentAt = DateTimeOffset.UtcNow; return true; } }
    public async Task<StatusMessage?> WaitForFreshStatusAsync(DateTimeOffset after, Func<StatusMessage, bool> predicate, TimeSpan timeout) { var until = DateTimeOffset.UtcNow + timeout; while (DateTimeOffset.UtcNow < until) { var status = LastStatus; if (status is not null && LastStatusReceivedAt > after && predicate(status)) return status; await Task.Delay(100); } return null; }
    public async Task SendCommandDirectAsync(string command) { if (string.Equals(command.Trim(), "#stop", StringComparison.OrdinalIgnoreCase)) CancelMiningQuota(); lastCommand = command; lastCommandAt = DateTimeOffset.UtcNow; await SendAsync(new CommandMessage("command", command)); }
    public async Task SendCommandSequenceAsync(params string[] commands) { foreach (var command in commands) { await SendCommandDirectAsync(command); await Task.Delay(250); } }
    public async Task SendAsync(object msg) { await gate.WaitAsync(); try { await writer.WriteLineAsync(JsonSerializer.Serialize(msg, JsonProtocol.Options)); } finally { gate.Release(); } } }
public sealed record MiningQuota(string ItemId, int StartCount, int TargetCount, int LastObservedCount,
    DateTimeOffset StartedAt, DateTimeOffset LastProgressAt, TimeSpan MaximumDuration, TimeSpan NoProgressTimeout);
public sealed record MiningQuotaResult(string ItemId, int StartCount, int TargetCount, int ObservedCount,
    bool Reached, string Reason, DateTimeOffset StartedAt, DateTimeOffset FinishedAt);
public sealed record TargetSelection(List<ClientConnection> Targets, int SkippedPrimary);
public record HelloMessage(string Type, string InstanceId, string Token); public record CommandMessage(string Type, string Command); public record StatusMessage(string Type, long SnapshotTime, string PlayerName, string? PlayerUuid, string? Dimension, float Health, float MaxHealth, int Food, float Saturation, int Air, int X, int Y, int Z, List<InventoryItem>? Inventory, List<EquipmentItem>? Equipment, List<NearbyBlock>? NearbyBlocks, List<NearbyBlock>? FunctionalBlocks, List<FunctionalBlockContext>? FunctionalBlockContexts, List<BlockChangeInfo>? BlockChanges, long BlockChangeSequence, string? OpenContainer, List<ContainerSlotInfo>? ContainerSlots, bool FurnaceLit, float FurnaceBurnProgress, float FurnaceLitProgress, bool BaritoneLoaded, bool BaritoneStatusAvailable, string? BaritoneStatusError, bool BaritoneWorking, List<string>? BaritoneProcesses, List<PathPoint>? BaritonePath, long BaritoneWorkSequence, long BaritoneWorkFinishedAt, bool ClusterPrimary, bool ClusterPossessionTarget, string? SelectedPossessionTarget, long RemoteStateSequence, long RemoteChunkSequence, long RemoteChunkSentAt, long RemoteChunkReceivedAt, string? RemoteChunkError, List<ObservedPlayer>? Players); public record BaritoneWorkFinishedNotice(string InstanceId, string PlayerName, long Sequence, long FinishedAt); public record InventoryItem(string Item, int Count, int MaxCount, int Slot); public record EquipmentItem(string Item, int Count, int MaxCount, int Slot, string EquipmentSlot); public record NearbyBlock(string Block, int X, int Y, int Z); public record FunctionalBlockContext(string Block, int X, int Y, int Z, List<AdjacentBlock>? AdjacentBlocks); public record AdjacentBlock(string Block, int X, int Y, int Z, int Dx, int Dy, int Dz); public record BlockChangeInfo(long Sequence, long Timestamp, int X, int Y, int Z, string Before, string After); public record ContainerSlotInfo(int Slot, int ContainerSlot, string Section, string Item, int Count, int MaxCount); public record ObservedPlayer(string Uuid, string Name, string Dimension, bool PositionAvailable, double X, double Y, double Z, float Yaw, float Pitch, float Health, float MaxHealth, int Latency, string GameMode, string? SkinId, int SkinWidth, int SkinHeight, byte[]? SkinPixels); public record PathPoint(int X, int Y, int Z);
public readonly record struct MapCellKey(string Dimension, int X, int Z);
public sealed record MapBlockCell(string Dimension, int X, int Y, int Z, string Block, string Material, int Color);
public sealed record MapMaterial(string Id, int Width, int Height, byte[] Pixels);
public sealed record MapPlayerMarker(string Uuid, string Name, string Dimension, bool PositionAvailable, double X, double Y, double Z, float Yaw, float Pitch, float Health, float MaxHealth, int Latency, string GameMode, bool IsControllable, string? InstanceId, string? SkinId, int SkinWidth, int SkinHeight, byte[]? SkinPixels);
public sealed record WorldMapSnapshot(string Dimension, IReadOnlyList<string> Dimensions, IReadOnlyList<MapBlockCell> Blocks, IReadOnlyList<MapMaterial> Materials, IReadOnlyList<MapPlayerMarker> Players);
public sealed record CollectionTaskPlan(string TaskKey, string Target, string ItemId, int RequiredCount, int HomeX, int HomeY, int HomeZ,
    string Title, IReadOnlyList<string> Steps, IReadOnlyList<string> InstanceIds, DateTimeOffset CreatedAt);
public sealed record ActiveTaskInstanceProgress(string InstanceId, string PlayerName, string Stage, string StageLabel,
    int CurrentStep, int CurrentItemCount, int RequiredCount, bool BaritoneWorking);
public sealed record ActiveTaskProgress(bool Active, bool Complete, string Title, string ItemId, int RequiredCount,
    int HomeX, int HomeY, int HomeZ, int CurrentStep, int TotalSteps, IReadOnlyList<string> Steps,
    IReadOnlyList<ActiveTaskInstanceProgress> Instances, DateTimeOffset CreatedAt);
public sealed record ActiveTaskAdvanceResult(bool HasPlan, bool Complete, string Summary, ActiveTaskProgress? Progress, object? Details);
public sealed record AgentExecutionPlan(string TaskKey, string Title, IReadOnlyList<string> Steps, int CurrentStep,
    bool Complete, string Status, IReadOnlyList<string> InstanceIds, DateTimeOffset CreatedAt);
public static class JsonProtocol { public static readonly JsonSerializerOptions Options = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = true }; }

using System.ClientModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Text.Json;
using Microsoft.Agents.AI;
using Microsoft.Extensions.AI;
using OpenAI;
using OpenAI.Chat;

namespace BaritoneClusterServer;

/// <summary>
/// Minecraft cluster agent backed by Microsoft Agent Framework. The framework owns the
/// conversation, tool-call loop and Agent Skills disclosure; this class only exposes the
/// trusted game operations implemented by <see cref="ClusterHub"/>.
/// </summary>
public sealed class DeepSeekAgent
{
    private const string PlannerInstructions = """
        你是 Minecraft 多实例任务规划器。你没有任何游戏工具，也绝对不能执行游戏操作。
        收到用户目标和当前集群摘要后，先拆成足够细的、严格有依赖顺序的编号步骤。

        规划要求：
        - 每个采集、合成中间材料、放置方块、打开功能方块、烧炼、移动、交付和最终验证都应是独立步骤。
        - 明确每一步的输入物品、最低数量、应调用的游戏能力和完成判据。
        - 3x3 合成必须先有工作台、place_block 放置、open_nearest_functional_block 打开。
        - 烧炼必须包含制作/放置/打开熔炉、准备燃料、装入、等待、取出。
        - 交给玩家必须使用 give_item_to_player 并验证交付。
        - 不得因玩家已有高级装备而省略用户明确要求的过程。
        - 用中文输出，先写一行“目标：…”，然后输出编号步骤；复杂任务通常不少于 10 步。
        """;
    private const string SystemInstructions = """
        你是专门控制 Minecraft 多实例集群的自主 Game Agent。你的工作方式应像成熟的编码 Agent：
        C# 会在每次请求中预加载匹配的 Agent Skill 和最新集群摘要。先直接利用这些信息制定计划、调用工具、
        观察结果并验证，不要再次调用工具获取请求里已经提供且足够新鲜的信息。

        必须遵守：
        - 每个 instanceId 是可独立控制、状态彼此独立的玩家。用户说“全部玩家”时使用 target=all，
          表示每个可控实例都分别完成目标，而不是由任意一个实例代替全部实例完成。
        - 工具返回和最新上报状态是唯一事实来源。已发送命令不等于已完成；不得编造背包数量、坐标、
          容器内容、方块、Baritone 状态或任务进度。
        - 状态为 null、ageMilliseconds=-1 或超过 5 秒时先等待并重读。不要在无法确认状态时盲目执行破坏性操作。
        - 请求中的 CURRENT_CLUSTER_CONTEXT 如果 ageMilliseconds 在 0 到 5000 之间，就是可直接使用的新鲜事实。
          对明确的挖掘、移动或跟随命令，首次响应应直接调用对应动作工具，不要先调用 list_instances、
          get_instance_status 或 wait_instance_state 做重复查询。
        - 请求中若有 FAST_PATH_ALREADY_STARTED，表示 C# 已按用户明确命令下发了第一个 Baritone 动作。
          不得重复下发该动作；先简短确认已经启动，然后等完成事件触发下一轮再检查和执行后续步骤。
        - 一个 Agent 运行可以连续调用多个工具。存在依赖的步骤必须按顺序执行和验证，例如打开容器后再转移，
          烧炼后读取熔炉和背包状态，确认产物进入箱子后才宣布完成。
        - CURRENT_CLUSTER_CONTEXT.activeTask 不为空且 active=true 时，它是 C# 已持久化的分阶段计划。一次 Baritone
          工作结束只代表当前动作结束，不代表整个目标完成；只有 activeTask.complete=true 才能结束该任务。
        - 长任务可能持续几小时。Baritone 工作时不要重复发送相同命令；让当前运行结束，后台执行器会在
          Baritone 完成事件或定时状态更新后使用同一会话继续。
        - 调用 move_to、mine_item、follow_player、progress_collection_task 或 progress_smelting_task 后，只要结果表明
          Baritone 已开始工作，就立刻用一句话报告当前动作并结束本轮。禁止在同一轮反复调用 wait_instance_state
          等待 Baritone；C# 会在工作结束事件到达后立即唤醒你继续。
        - isPrimary=true 且 aiControlAllowed=false 的主要玩家受保护。只有用户当前消息明确授权控制主要玩家时，
          才能调用 set_primary_ai_control(true)。target=all 会自动跳过受保护的主要玩家。
        - 优先选择语义明确的工具和匹配 Skill。普通采集入箱使用 progress_collection_task；包含烧炼的流程必须用
          progress_smelting_task；不要用 send_command 拼凑已有专用工具能够完成的动作。
        - 放置工作台、熔炉、箱子或其他方块必须调用 place_block，并检查 success 与 placedBlock 坐标；use_item
          只是空中使用物品，不能作为方块放置工具。放置成功后使用 open_nearest_functional_block 打开，再执行
          3×3 合成、装炉或容器操作。严禁尝试不存在的 #place，严禁用 #setblock 修改世界。
        - 把物品交给玩家必须调用 give_item_to_player；不得只在远处 discard_item 后声称对方已经收到。
        - 用户要求停止，或所有目标均经最新状态验证完成后，先 stop_baritone，再调用 stop_continuous_task。
        - 执行 APPROVED_EXECUTION_PLAN 时，每完成一个或多个编号步骤就调用 update_execution_plan_progress，completedSteps 是已经完全完成的步骤数。
          最终成功必须先调用 finish_execution_plan(success=true)；确认无法继续时调用 finish_execution_plan(success=false)。计划尚未结束时禁止调用 stop_continuous_task。
        - 对纯咨询、无需继续等待游戏状态的请求，正常回答后调用 stop_continuous_task，避免后台无意义轮询。
        - 回复用户时简洁报告事实、当前阶段和阻塞项，不输出虚假的未来承诺。
        """;

    private readonly ClusterHub hub;
    private readonly string apiKey;
    private readonly string model;
    private const int AutoCompactAfterInvocations = 8;
    private const int AutoCompactAfterCharacters = 45_000;
    private readonly SemaphoreSlim initializationGate = new(1, 1);
    private readonly SemaphoreSlim conversationGate = new(1, 1);
    private readonly List<string> contextTranscript = [];
    private ChatClientAgent? agent;
    private ChatClientAgent? plannerAgent;
    private AgentSession? session;
    private string compactedContext = "";
    private int sessionInvocationCount;
    private int estimatedContextCharacters;
    private int lastRunToolCalls;

    public DeepSeekAgent(ClusterHub hub, string apiKey, string model)
    {
        this.hub = hub;
        this.apiKey = apiKey;
        this.model = string.IsNullOrWhiteSpace(model) ? "deepseek-chat" : model;
    }

    public bool StopRequested { get; private set; }
    public string? StopReason { get; private set; }
    public bool LastRunNeedsContinuation { get; private set; }
    public int LastRunToolCalls => Volatile.Read(ref lastRunToolCalls);
    public event Action<string>? ToolInvoked;
    public event Action<string>? ContextMaintenance;

    public async Task<string> PlanAsync(string userText, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(userText);
        await EnsureInitializedAsync(cancellationToken);
        var context = JsonSerializer.Serialize(hub.AgentContextSnapshot(), JsonProtocol.Options);
        var plannerSession = await plannerAgent!.CreateSessionAsync(cancellationToken: cancellationToken);
        var response = await plannerAgent.RunAsync($"""
            用户目标：{userText}

            <CURRENT_CLUSTER_CONTEXT>
            {context}
            </CURRENT_CLUSTER_CONTEXT>

            只输出执行计划，不得声称已经执行，不得输出工具调用。
            """, plannerSession, cancellationToken: cancellationToken);
        var text = response.Text?.Trim();
        return string.IsNullOrWhiteSpace(text) ? "目标：完成用户任务\n1. 读取并验证目标实例状态。\n2. 按用户要求逐步执行并在每步后验证。\n3. 验证最终产物和交付结果。" : text;
    }

    public async Task<string> ReplyAsync(string userText, int maxRounds = 40, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(userText);
        _ = maxRounds; // Kept for source compatibility; Agent Framework owns its function-call loop.
        await EnsureInitializedAsync(cancellationToken);
        await conversationGate.WaitAsync(cancellationToken);
        try
        {
            await CompactContextIfNeededAsync(cancellationToken);

            StopRequested = false;
            StopReason = null;
            LastRunNeedsContinuation = false;
            Interlocked.Exchange(ref lastRunToolCalls, 0);

            var context = JsonSerializer.Serialize(hub.AgentContextSnapshot(), JsonProtocol.Options);
            var skills = FastSkillRouter.LoadFor(userText);
            var compactedBlock = string.IsNullOrWhiteSpace(compactedContext) ? "" : $"""

                <COMPACTED_CONVERSATION_CONTEXT>
                {compactedContext}
                </COMPACTED_CONVERSATION_CONTEXT>
                """;
            var request = $"""
                {userText}

                <CURRENT_CLUSTER_CONTEXT>
                {context}
                </CURRENT_CLUSTER_CONTEXT>

                <PRELOADED_AGENT_SKILLS>
                {skills}
                </PRELOADED_AGENT_SKILLS>
                {compactedBlock}

                立即处理用户目标。若上下文新鲜且动作明确，第一次响应就调用改变游戏状态的工具；不要重复读取相同信息。
                """;
            var response = await agent!.RunAsync(request, session, cancellationToken: cancellationToken);
            var text = response.Text?.Trim();
            var answer = string.IsNullOrWhiteSpace(text) ? "Agent 已完成本轮处理。" : text;
            RememberContext(userText, answer, request.Length);
            return answer;
        }
        finally
        {
            conversationGate.Release();
        }
    }

    public async Task ClearContextAsync(CancellationToken cancellationToken = default)
    {
        await EnsureInitializedAsync(cancellationToken);
        await conversationGate.WaitAsync(cancellationToken);
        try
        {
            session = await agent!.CreateSessionAsync(cancellationToken: cancellationToken);
            compactedContext = "";
            contextTranscript.Clear();
            sessionInvocationCount = 0;
            estimatedContextCharacters = 0;
            StopRequested = false;
            StopReason = null;
            LastRunNeedsContinuation = false;
        }
        finally
        {
            conversationGate.Release();
        }
    }

    private async Task CompactContextIfNeededAsync(CancellationToken cancellationToken)
    {
        if (sessionInvocationCount < AutoCompactAfterInvocations && estimatedContextCharacters < AutoCompactAfterCharacters) return;

        var transcript = string.Join("\n\n", contextTranscript);
        if (transcript.Length > 60_000) transcript = transcript[^60_000..];
        string summary;
        try
        {
            var compactSession = await plannerAgent!.CreateSessionAsync(cancellationToken: cancellationToken);
            var response = await plannerAgent.RunAsync($"""
                把下面的 Minecraft Agent 历史压缩成不超过 1200 个中文字的持久摘要，供新会话继续任务。
                只保留：用户最终目标、已经验证完成的步骤、当前未完成步骤、重要工具结果、坐标、物品数量、失败原因和用户偏好。
                删除：重复的等待回复、客套话、相同状态的重复描述、完整 AI 叙述、过期状态、工具格式标记和不影响后续执行的内容。
                不得把“命令已发送”写成“任务已完成”。当前游戏事实仍必须由下一轮 CURRENT_CLUSTER_CONTEXT 重新验证。

                <PREVIOUS_COMPACTED_CONTEXT>
                {compactedContext}
                </PREVIOUS_COMPACTED_CONTEXT>
                <RECENT_TRANSCRIPT>
                {transcript}
                </RECENT_TRANSCRIPT>
                """, compactSession, cancellationToken: cancellationToken);
            summary = response.Text?.Trim() ?? "";
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            summary = string.Join("\n", contextTranscript.TakeLast(4));
        }

        if (summary.Length > 8_000) summary = summary[..8_000];
        if (string.IsNullOrWhiteSpace(summary)) summary = "旧会话已清理；继续时以当前执行计划和最新集群状态为准。";
        compactedContext = summary;
        session = await agent!.CreateSessionAsync(cancellationToken: cancellationToken);
        contextTranscript.Clear();
        sessionInvocationCount = 0;
        estimatedContextCharacters = compactedContext.Length;
        ContextMaintenance?.Invoke("Agent 上下文已自动压缩：已删除重复等待、冗余 AI 回复和过期状态，保留任务目标与关键进度。");
    }

    private void RememberContext(string userText, string answer, int requestCharacters)
    {
        sessionInvocationCount++;
        estimatedContextCharacters += requestCharacters + answer.Length;
        var userRecord = userText.StartsWith("继续同一个持续任务", StringComparison.Ordinal)
            ? userText.Split('\n', 2)[0]
            : LimitContextText(userText, 6_000);
        contextTranscript.Add("用户/执行器：" + userRecord);
        if (!IsUnnecessaryWaitingReply(answer)) contextTranscript.Add("AI：" + LimitContextText(answer, 5_000));
        while (contextTranscript.Count > 24) contextTranscript.RemoveAt(0);
    }

    private bool IsUnnecessaryWaitingReply(string answer) => LastRunToolCalls == 0 &&
        answer.Contains("Baritone", StringComparison.OrdinalIgnoreCase) &&
        (answer.Contains("等待", StringComparison.OrdinalIgnoreCase) || answer.Contains("still working", StringComparison.OrdinalIgnoreCase));

    private static string LimitContextText(string value, int maximum) => value.Length <= maximum ? value : value[..maximum] + "…";
    private async Task EnsureInitializedAsync(CancellationToken cancellationToken)
    {
        if (agent is not null && session is not null) return;

        await initializationGate.WaitAsync(cancellationToken);
        try
        {
            if (agent is not null && session is not null) return;

            var tools = new MinecraftAgentTools(hub, RecordToolCall, RequestStop).Build();

            var client = new OpenAIClient(
                new ApiKeyCredential(apiKey),
                new OpenAIClientOptions { Endpoint = new Uri("https://api.deepseek.com") });

            var chatClient = client.GetChatClient(model);
            var newPlannerAgent = chatClient.AsAIAgent(new ChatClientAgentOptions
            {
                Name = "MinecraftTaskPlanner",
                Description = "Creates detailed Minecraft execution plans without access to game tools.",
                ChatOptions = new ChatOptions
                {
                    Instructions = PlannerInstructions,
                    Temperature = 0.1f,
                    MaxOutputTokens = 4096,
                    AllowMultipleToolCalls = false,
                    Tools = []
                },
                UseProvidedChatClientAsIs = true
            });

            var newAgent = chatClient.AsAIAgent(new ChatClientAgentOptions
            {
                Name = "MinecraftClusterAgent",
                Description = "Controls multiple Fabric/Baritone Minecraft client instances through the C# cluster hub.",
                ChatOptions = new ChatOptions
                {
                    Instructions = SystemInstructions,
                    Temperature = 0.15f,
                    MaxOutputTokens = 8192,
                    AllowMultipleToolCalls = true,
                    Tools = tools
                },
                UseProvidedChatClientAsIs = true
            }, clientFactory: inner => new FunctionInvokingChatClient(inner, loggerFactory: null, functionInvocationServices: null)
            {
                MaximumIterationsPerRequest = 12,
                MaximumConsecutiveErrorsPerRequest = 2,
                IncludeDetailedErrors = true,
                AllowConcurrentInvocation = false
            });

            var newSession = await newAgent.CreateSessionAsync(cancellationToken: cancellationToken);
            plannerAgent = newPlannerAgent;
            agent = newAgent;
            session = newSession;
        }
        finally
        {
            initializationGate.Release();
        }
    }

    private void RecordToolCall(string operation)
    {
        Interlocked.Increment(ref lastRunToolCalls);
        ToolInvoked?.Invoke(operation);
    }

    private object RequestStop(string reason)
    {
        if (hub.HasIncompleteAgentExecutionPlan)
        {
            LastRunNeedsContinuation = true;
            return new { stopped = false, reason = "执行计划尚未完成。必须先逐步验证并调用 finish_execution_plan；等待 Baritone 或暂时没有动作都不等于任务完成。" };
        }
        StopRequested = true;
        StopReason = string.IsNullOrWhiteSpace(reason) ? "AI 已验证本次任务结束" : reason.Trim();
        return new { stopped = true, reason = StopReason };
    }

}

/// <summary>Strongly typed tools converted by Microsoft.Extensions.AI into JSON-schema functions.</summary>
internal sealed class MinecraftAgentTools(
    ClusterHub hub,
    Action<string> recordToolCall,
    Func<string, object> requestStop)
{
    public IList<AITool> Build() =>
    [
        Tool((Func<IEnumerable<object>>)ListInstances, "list_instances", "列出在线可控实例、玩家、位置、状态新鲜度、主要玩家保护和 Baritone 摘要。"),
        Tool((Func<string, object>)GetInstanceStatus, "get_instance_status", "读取单个实例完整缓存：背包、装备、副手、容器、附近方块、方块变化、生命和 Baritone。"),
        Tool((Func<string, int, bool, CancellationToken, Task<object>>)WaitInstanceStateAsync, "wait_instance_state", "短暂等待实例产生新状态；只用于动作前后的快速刷新，不得等待正在工作的 Baritone。maxWaitSeconds 为 1 到 5。"),
        Tool((Func<string, int, int, int, Task<object>>)MoveToAsync, "move_to", "停止目标当前路径并移动到指定坐标。target 为精确 instanceId 或 all。"),
        Tool((Func<string, string, int, Task<object>>)MineItemAsync, "mine_item", "让每个目标实例分别采集指定数量的物品对应矿物；不负责回家和入箱。"),
        Tool((Func<string, string, Task<object>>)FollowPlayerAsync, "follow_player", "停止当前路径并让目标实例持续跟随指定玩家名称。"),
        Tool((Func<string, string, int, CancellationToken, Task<object>>)DiscardItemAsync, "discard_item", "从目标玩家背包丢弃指定物品；count=-1 表示全部。"),
        Tool((Func<string, string, int, CancellationToken, Task<object>>)CraftItemAsync, "craft_item", "使用玩家已解锁配方和当前合成网格合成指定物品。"),
        Tool((Func<string, string, CancellationToken, Task<object>>)UseItemAsync, "use_item", "选择背包中的指定物品并右键使用一次。"),
        Tool((Func<string, string, CancellationToken, Task<object>>)PlaceBlockNearbyAsync, "place_block", "把背包里的方块物品放到玩家附近安全、可交互的实体地面上，并返回实际方块坐标。放置工作台、熔炉、箱子等必须使用本工具，禁止用 use_item、#place 或 #setblock 代替。"),
        Tool((Func<string, string, CancellationToken, Task<object>>)OpenNearestFunctionalBlockAsync, "open_nearest_functional_block", "按方块注册 ID 找到玩家附近最近的功能方块并右键打开，验证对应容器或合成界面已经打开。"),
        Tool((Func<string, string, int, int, int, int, Task<object>>)ProgressCollectionTaskAsync, "progress_collection_task", "推进采集、回家、寻找并打开箱子、入箱的可靠状态机；每个目标实例独立完成。"),
        Tool((Func<string, string, int, string, int, string, int, int, int, Task<object>>)ProgressSmeltingTaskAsync, "progress_smelting_task", "推进采矿、采燃料、回家、装炉、等待烧炼、取出、入箱的完整状态机。"),
        Tool((Func<string, int, int, int, Task<object>>)OpenBlockAsync, "open_block", "以目标玩家身份右键指定坐标方块，打开箱子、熔炉或工作台。"),
        Tool((Func<string, string, Task<object>>)DepositItemAsync, "deposit_item", "把目标玩家背包内指定物品快速转入当前已经打开的容器。"),
        Tool((Func<string, string, Task<object>>)WithdrawItemAsync, "withdraw_item", "把当前容器内指定物品快速转入目标玩家背包。"),
        Tool((Func<string, string, string, int, CancellationToken, Task<object>>)GiveItemToPlayerAsync, "give_item_to_player", "让给予者移动到指定玩家身旁，再从背包丢出指定数量物品；目标是可控实例时还会验证其背包是否拾取。"),
        Tool((Func<string, int, int, string, Task<object>>)ClickContainerSlotAsync, "click_container_slot", "点击当前容器槽位；clickType 可为 PICKUP、QUICK_MOVE、THROW 或 SWAP。"),
        Tool((Func<string, Task<object>>)CloseContainerAsync, "close_container", "关闭目标实例当前容器界面。"),
        Tool((Func<string, string, Task<object>>)SendCommandAsync, "send_command", "下发尚无专用工具的 #Baritone 命令或用户明确要求的 /Minecraft 命令。"),
        Tool((Func<string, Task<object>>)StopBaritoneAsync, "stop_baritone", "立即向目标实例发送 #stop。"),
        Tool((Func<bool, Task<object>>)SetPrimaryAiControlAsync, "set_primary_ai_control", "设置主要玩家 AI 权限；true 仅限用户当前消息明确授权。"),
        Tool((Func<int, string, Task<object>>)UpdateExecutionPlanProgressAsync, "update_execution_plan_progress", "完成规划中的一个或多个编号步骤后更新右上角进度。completedSteps 是从第 1 步开始已经完全完成的步骤总数，不能把仅已下发或仍在运行的动作算作完成。"),
        Tool((Func<bool, string, Task<object>>)FinishExecutionPlanAsync, "finish_execution_plan", "最终目标已经验证完成时 success=true；确认阻塞且无法继续时 success=false。必须先结束计划，之后才能 stop_continuous_task。"),
        Tool((Func<string, object>)StopContinuousTask, "stop_continuous_task", "仅在执行计划已由 finish_execution_plan 结束、纯咨询已回答或用户要求停止时结束后台 Agent；等待 Baritone 时禁止调用。")
    ];

    private static AIFunction Tool(Delegate method, string name, string description) =>
        AIFunctionFactory.Create(method, name, description, JsonProtocol.Options);

    private IEnumerable<object> ListInstances()
    {
        Track();
        return hub.InstanceSummaries();
    }

    private object GetInstanceStatus(string instanceId)
    {
        Track();
        return hub.Status(Required(instanceId, nameof(instanceId)));
    }

    private Task<object> WaitInstanceStateAsync(string instanceId, int maxWaitSeconds = 3, bool requireBaritoneIdle = false, CancellationToken cancellationToken = default)
    {
        Track();
        return hub.WaitForInstanceStateAsync(Required(instanceId, nameof(instanceId)), Math.Clamp(maxWaitSeconds, 1, 5), requireBaritoneIdle, cancellationToken);
    }

    private Task<object> MoveToAsync(string target, int x, int y, int z)
    {
        Track();
        return hub.MoveToAsync(Target(target), x, y, z);
    }

    private Task<object> MineItemAsync(string target, string itemId, int count)
    {
        Track();
        return hub.MineItemAsync(Target(target), Item(itemId), Positive(count, nameof(count)));
    }

    private Task<object> FollowPlayerAsync(string target, string playerName)
    {
        Track();
        return hub.FollowPlayerAsync(Target(target), Required(playerName, nameof(playerName)));
    }

    private Task<object> DiscardItemAsync(string target, string itemId, int count = -1, CancellationToken cancellationToken = default)
    {
        Track();
        if (count == 0 || count < -1) throw new ArgumentOutOfRangeException(nameof(count), "count 必须为 -1 或正整数。");
        return hub.DiscardItemAsync(Target(target), Item(itemId), count, cancellationToken);
    }

    private Task<object> CraftItemAsync(string target, string itemId, int count, CancellationToken cancellationToken = default)
    {
        Track();
        return hub.CraftItemAsync(Target(target), Item(itemId), Positive(count, nameof(count)), cancellationToken);
    }

    private Task<object> UseItemAsync(string target, string itemId, CancellationToken cancellationToken = default)
    {
        Track();
        return hub.UseItemAsync(Target(target), Item(itemId), cancellationToken);
    }

    private Task<object> PlaceBlockNearbyAsync(string target, string itemId, CancellationToken cancellationToken = default)
    {
        Track();
        return hub.PlaceBlockNearbyAsync(Target(target), Item(itemId), cancellationToken);
    }

    private Task<object> OpenNearestFunctionalBlockAsync(string target, string blockId, CancellationToken cancellationToken = default)
    {
        Track();
        return hub.OpenNearestFunctionalBlockAsync(Target(target), Item(blockId), cancellationToken);
    }

    private Task<object> ProgressCollectionTaskAsync(string target, string itemId, int requiredCount, int homeX, int homeY, int homeZ)
    {
        Track();
        return hub.ProgressCollectionTaskAsync(Target(target), Item(itemId), Positive(requiredCount, nameof(requiredCount)), homeX, homeY, homeZ);
    }

    private Task<object> ProgressSmeltingTaskAsync(string target, string inputItemId, int inputCount, string fuelItemId, int fuelCount, string resultItemId, int homeX, int homeY, int homeZ)
    {
        Track();
        return hub.ProgressSmeltingTaskAsync(Target(target), Item(inputItemId), Positive(inputCount, nameof(inputCount)), Item(fuelItemId), Positive(fuelCount, nameof(fuelCount)), Item(resultItemId), homeX, homeY, homeZ);
    }

    private Task<object> OpenBlockAsync(string target, int x, int y, int z)
    {
        Track();
        return hub.ActionAsync(Target(target), new { type = "action", operation = "open_block", x, y, z });
    }

    private Task<object> DepositItemAsync(string target, string itemId)
    {
        Track();
        return hub.ActionAsync(Target(target), new { type = "action", operation = "deposit_item", item = Item(itemId) });
    }

    private Task<object> WithdrawItemAsync(string target, string itemId)
    {
        Track();
        return hub.ActionAsync(Target(target), new { type = "action", operation = "withdraw_item", item = Item(itemId) });
    }

    private Task<object> GiveItemToPlayerAsync(string target, string itemId, string playerName, int count = 1, CancellationToken cancellationToken = default)
    {
        Track();
        return hub.GiveItemToPlayerAsync(Target(target), Item(itemId), Required(playerName, nameof(playerName)), Positive(count, nameof(count)), cancellationToken);
    }

    private Task<object> ClickContainerSlotAsync(string target, int slot, int button = 0, string clickType = "PICKUP")
    {
        Track();
        if (slot < 0) throw new ArgumentOutOfRangeException(nameof(slot));
        if (button < 0) throw new ArgumentOutOfRangeException(nameof(button));
        var normalizedType = Required(clickType, nameof(clickType)).ToUpperInvariant();
        if (normalizedType is not ("PICKUP" or "QUICK_MOVE" or "THROW" or "SWAP"))
            throw new ArgumentException("clickType 必须为 PICKUP、QUICK_MOVE、THROW 或 SWAP。", nameof(clickType));
        return hub.ActionAsync(Target(target), new { type = "action", operation = "click_slot", slot, button, clickType = normalizedType });
    }

    private Task<object> CloseContainerAsync(string target)
    {
        Track();
        return hub.ActionAsync(Target(target), new { type = "action", operation = "close_screen" });
    }

    private Task<object> SendCommandAsync(string target, string command)
    {
        Track();
        return hub.SendAsync(Target(target), Required(command, nameof(command)));
    }

    private Task<object> StopBaritoneAsync(string target)
    {
        Track();
        return hub.StopBaritoneAsync(Target(target));
    }

    private Task<object> SetPrimaryAiControlAsync(bool allowed)
    {
        Track();
        return hub.SetPrimaryAiControlAllowedAsync(allowed);
    }

    private Task<object> UpdateExecutionPlanProgressAsync(int completedSteps, string status)
    {
        Track();
        if (completedSteps < 0) throw new ArgumentOutOfRangeException(nameof(completedSteps));
        return hub.UpdateAgentExecutionPlanAsync(completedSteps, status ?? "");
    }

    private Task<object> FinishExecutionPlanAsync(bool success, string summary)
    {
        Track();
        return hub.FinishAgentExecutionPlanAsync(success, summary ?? "");
    }

    private object StopContinuousTask(string reason)
    {
        Track();
        return requestStop(reason);
    }

    private void Track([CallerMemberName] string operation = "") => recordToolCall(operation);
    private static string Target(string value) => Required(value, "target");
    private static string Item(string value) => Required(value, "itemId");
    private static string Required(string value, string name) => string.IsNullOrWhiteSpace(value) ? throw new ArgumentException($"{name} 不能为空。", name) : value.Trim();
    private static int Positive(int value, string name) => value > 0 ? value : throw new ArgumentOutOfRangeException(name, "必须为正整数。");
}

internal static class FastSkillRouter
{
    private static readonly object Gate = new();
    private static readonly Dictionary<string, string> Cache = new(StringComparer.Ordinal);

    public static string LoadFor(string prompt)
    {
        var names = new HashSet<string>(StringComparer.Ordinal) { "cluster-coordination" };
        if (ContainsAny(prompt, "挖", "采集", "矿", "箱", "收集", "石头", "煤", "钻石")) names.Add("resource-collection");
        if (ContainsAny(prompt, "烧", "熔炉", "烧炼", "铁锭", "金锭")) names.Add("smelting-and-storage");
        if (ContainsAny(prompt, "背包", "合成", "制作", "丢", "扔", "给", "物品", "容器")) names.Add("inventory-and-crafting");
        if (ContainsAny(prompt, "移动", "坐标", "跟随", "寻路", "走到", "回家")) names.Add("navigation-and-waiting");
        return string.Join("\n\n", names.Select(Load));
    }

    private static string Load(string name)
    {
        lock (Gate)
        {
            if (Cache.TryGetValue(name, out var cached)) return cached;
            var path = Path.Combine(AppContext.BaseDirectory, "skills", name, "SKILL.md");
            if (!File.Exists(path)) return Cache[name] = $"Skill {name}: 文件不存在，使用系统规则继续。";
            var text = File.ReadAllText(path);
            var bodyStart = text.IndexOf("\n---", 4, StringComparison.Ordinal);
            if (bodyStart >= 0) text = text[(bodyStart + 4)..].Trim();
            return Cache[name] = $"## Skill: {name}\n{text}";
        }
    }

    private static bool ContainsAny(string text, params string[] values) => values.Any(value => text.Contains(value, StringComparison.OrdinalIgnoreCase));
}

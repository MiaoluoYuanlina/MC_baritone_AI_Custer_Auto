using System.Threading.Channels;

namespace BaritoneClusterServer;

/// <summary>
/// Keeps one Agent Framework session alive behind the single Send button and wakes it when
/// Baritone finishes or on a timer. The user can cancel the whole run with the Interrupt button.
/// </summary>
public sealed class LongTaskRunner(ClusterHub hub, DeepSeekAgent agent)
{
    public async Task RunAsync(string task, Action<string> report, CancellationToken cancellationToken)
    {
        var completedWork = Channel.CreateUnbounded<BaritoneWorkFinishedNotice>();
        void OnBaritoneWorkFinished(BaritoneWorkFinishedNotice notice) => completedWork.Writer.TryWrite(notice);
        hub.BaritoneWorkFinished += OnBaritoneWorkFinished;
        var cycle = 0;
        var trigger = "任务启动";
        var consecutiveErrors = 0;
        var consecutiveIdleAnswers = 0;
        void OnToolInvoked(string operation) => report($"Agent 已调用游戏工具：{operation}");
        void OnContextMaintenance(string message) => report(message);
        agent.ToolInvoked += OnToolInvoked;
        agent.ContextMaintenance += OnContextMaintenance;
        string? fastPath = null;
        string executionPlan;

        try
        {
            report("规划 Agent：正在生成完整执行步骤（此阶段没有游戏工具，不会操作玩家）…");
            executionPlan = await agent.PlanAsync(task, cancellationToken);
            report($"执行计划：\n{executionPlan}");
            await hub.PublishAiReplyAsync("执行计划：\n" + executionPlan);
        }
        catch (OperationCanceledException) { executionPlan = "任务已在规划阶段取消。"; }
        catch (Exception ex)
        {
            executionPlan = $"目标：{task}\n1. 读取并验证实例状态。\n2. 按用户要求逐项执行所有依赖步骤。\n3. 验证最终产物与交付结果后才结束。";
            report($"规划 Agent 暂时不可用，使用安全后备计划：{ex.Message}\n{executionPlan}");
        }

        if (!cancellationToken.IsCancellationRequested)
            await hub.BeginAgentExecutionPlanAsync(task, executionPlan);

        try
        {
            if (!cancellationToken.IsCancellationRequested) fastPath = await hub.TryStartFastIntentAsync(task);
            if (fastPath is not null) report($"C# 快路径已立即启动首个游戏动作：{fastPath}");
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            report($"C# 快路径未能启动，将交给 Agent：{ex.Message}");
        }

        try
        {
            if (hub.HasActivePlannedTask)
            {
                var planned = hub.ActiveTaskProgressSnapshot();
                if (planned is not null)
                {
                    report("已生成持久化执行计划：");
                    for (var index = 0; index < planned.Steps.Count; index++) report($"{index + 1}. {planned.Steps[index]}");
                    report("每一步都会读取最新状态验证；Baritone 挖掘结束只会推进下一步，不会结束整个任务。");
                }
                string? lastSummary = null;
                while (!cancellationToken.IsCancellationRequested)
                {
                    try
                    {
                        var result = await hub.AdvanceActivePlannedTaskAsync();
                        if (!string.Equals(lastSummary, result.Summary, StringComparison.Ordinal)) report(result.Summary);
                        lastSummary = result.Summary;
                        consecutiveErrors = 0;
                        if (!result.HasPlan || result.Complete) break;
                    }
                    catch (OperationCanceledException) { break; }
                    catch (Exception ex)
                    {
                        consecutiveErrors++;
                        report($"计划推进错误（{consecutiveErrors}/3）：{ex.Message}");
                        if (consecutiveErrors >= 3) break;
                    }

                    using var waitCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                    var timer = Task.Delay(TimeSpan.FromSeconds(10), waitCancellation.Token);
                    var workFinished = completedWork.Reader.ReadAsync(waitCancellation.Token).AsTask();
                    await Task.WhenAny(timer, workFinished);
                    waitCancellation.Cancel();
                }
            }
            else
            {
                while (!cancellationToken.IsCancellationRequested)
                {
                    cycle++;
                    try
                    {
                        report($"Agent · 第 {cycle} 轮（{trigger}）：正在观察、执行并校验…");
                        var prompt = cycle == 1
                            ? $"用户本次对话：{task}\n<APPROVED_EXECUTION_PLAN>\n{executionPlan}\n</APPROVED_EXECUTION_PLAN>\nFAST_PATH_ALREADY_STARTED：{fastPath ?? "无"}\n必须依次执行计划，不能跳过用户明确要求的过程。若快路径已启动，不得重复首个动作；否则状态足够新鲜时第一次响应就执行计划第一个未完成步骤。纯咨询直接回答并结束。"
                            : $"继续同一个持续任务。触发原因：{trigger}。\n<APPROVED_EXECUTION_PLAN>\n{executionPlan}\n</APPROVED_EXECUTION_PLAN>\n读取新状态，对照计划和工具结果继续第一个未完成步骤；不要重置任务，也不要重复已生效的命令。";
                        var answer = await agent.ReplyAsync(prompt, cancellationToken: cancellationToken);
                        report($"AI：{answer}");
                        await hub.PublishAiReplyAsync(answer);
                        consecutiveErrors = 0;
                        if (agent.StopRequested) { report($"Agent 已结束本次对话：{agent.StopReason}"); break; }
                        if (agent.LastRunToolCalls == 0)
                        {
                            consecutiveIdleAnswers++;
                            if (!hub.HasIncompleteAgentExecutionPlan && fastPath is null) break;
                            if (hub.HasIncompleteAgentExecutionPlan && consecutiveIdleAnswers >= 3)
                                trigger = "检测到连续无动作回复；计划仍未完成，必须读取最新状态并执行第一个未完成步骤，不能退出";
                        }
                        else consecutiveIdleAnswers = 0;
                    }
                    catch (OperationCanceledException) { break; }
                    catch (Exception ex)
                    {
                        consecutiveErrors++;
                        report($"Agent 错误（{consecutiveErrors}/3）：{ex.Message}");
                        if (consecutiveErrors >= 3) break;
                    }

                    trigger = await WaitForNextTriggerAsync(completedWork.Reader, report, consecutiveIdleAnswers, cancellationToken);
                }
            }
        }
        catch (OperationCanceledException) { }
        finally
        {
            if (cancellationToken.IsCancellationRequested) await hub.CancelActivePlannedTaskAsync();
            hub.BaritoneWorkFinished -= OnBaritoneWorkFinished;
            agent.ToolInvoked -= OnToolInvoked;
            agent.ContextMaintenance -= OnContextMaintenance;
            completedWork.Writer.TryComplete();
        }
        report(cancellationToken.IsCancellationRequested ? "Agent 已被用户中断。" : "Agent 本次对话已结束。");
    }
    private async Task<string> WaitForNextTriggerAsync(ChannelReader<BaritoneWorkFinishedNotice> completedWork, Action<string> report,
        int consecutiveIdleAnswers, CancellationToken cancellationToken)
    {
        var workingChecks = 0;
        while (!cancellationToken.IsCancellationRequested)
        {
            var baritoneWorking = hub.ActiveTaskTargetsWorking;
            var delay = baritoneWorking ? TimeSpan.FromSeconds(30) : TimeSpan.FromSeconds(consecutiveIdleAnswers >= 3 ? 15 : 3);
            using var waitCancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            var timer = Task.Delay(delay, waitCancellation.Token);
            var workFinished = completedWork.ReadAsync(waitCancellation.Token).AsTask();
            var completed = await Task.WhenAny(timer, workFinished);
            waitCancellation.Cancel();
            if (completed == workFinished && workFinished.IsCompletedSuccessfully)
            {
                var notice = workFinished.Result;
                var instances = new HashSet<string> { notice.InstanceId };
                while (completedWork.TryRead(out var extra)) instances.Add(extra.InstanceId);
                var trigger = $"Baritone 工作结束：{string.Join(", ", instances)}；需要立即检查产物并决定下一步";
                report(trigger);
                return trigger;
            }
            if (!hub.ActiveTaskTargetsWorking)
                return consecutiveIdleAnswers >= 3
                    ? "检测到连续无动作回复；计划仍未完成，必须执行第一个未完成步骤，不能退出"
                    : baritoneWorking ? "C# 检测到 Baritone 已空闲；立即验证本步骤产物并继续计划" : "短间隔状态检查";

            workingChecks++;
            if (workingChecks % 4 == 0)
                report("C# 正在等待 Baritone 完成长动作；没有调用 AI，也没有把重复等待回复加入上下文。");
        }
        return "用户已中断";
    }
}

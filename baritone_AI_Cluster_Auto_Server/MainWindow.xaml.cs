using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;

namespace BaritoneClusterServer;

public partial class MainWindow : Window
{
    private readonly ClusterHub hub = new();
    private readonly ObservableCollection<InstanceView> instances = [];
    private CancellationTokenSource? activeAgentCancellation;
    private string? contextInstanceId;
    private MapWindow? mapWindow;
    private DeepSeekAgent? conversationAgent;
    private string? conversationApiKey;
    private string? conversationModel;

    public MainWindow()
    {
        InitializeComponent();
        var savedSettings = AppSettingsStore.Load();
        ApiKeyBox.Password = AppSettingsStore.LoadApiKey(savedSettings);
        ModelBox.Text = string.IsNullOrWhiteSpace(savedSettings.Model) ? "deepseek-chat" : savedSettings.Model;
        InstanceList.ItemsSource = instances;
        hub.Changed += Refresh;
        hub.BaritoneWorkFinished += notice => Dispatcher.BeginInvoke(() => HandleBaritoneWorkFinished(notice));
        Loaded += async (_, _) => { await hub.StartAsync(); Refresh(); };
        Closed += async (_, _) =>
        {
            AppSettingsStore.SaveUi(ApiKeyBox.Password.Trim(), ModelBox.Text.Trim());
            activeAgentCancellation?.Cancel();
            await hub.DisposeAsync();
        };
    }

    private void Refresh()
    {
        if (!Dispatcher.CheckAccess()) { Dispatcher.BeginInvoke(Refresh); return; }
        var selectedId = (InstanceList.SelectedItem as InstanceView)?.InstanceId;
        instances.Clear();
        foreach (var item in hub.Snapshot())
            instances.Add(new(item.InstanceId, item.LastStatus, item.IsPrimary, item.AiControlAllowed, item.IsPossessionTarget,
                item.ExpectedClusterStateVersion, item.ClusterStateAckVersion, item.ClientReportsPrimary));
        if (selectedId is not null)
            InstanceList.SelectedItem = instances.FirstOrDefault(x => x.InstanceId == selectedId);
        ConnectionText.Text = $"监听 25570 · 在线 {instances.Count}";
    }

    private async void SendButton_Click(object sender, RoutedEventArgs e)
    {
        if (activeAgentCancellation is not null)
        {
            Append("Agent 正在执行上一条对话；请先点击“中断”。");
            return;
        }

        var prompt = PromptBox.Text.Trim();
        var key = ApiKeyBox.Password.Trim();
        if (prompt.Length == 0) return;
        if (key.Length == 0)
        {
            MessageBox.Show("请输入 DeepSeek API Key。保存时会使用 Windows 当前用户加密。", "缺少 API Key");
            return;
        }

        PromptBox.Clear();
        var cancellation = new CancellationTokenSource();
        activeAgentCancellation = cancellation;
        SendButton.IsEnabled = false;
        InterruptButton.IsEnabled = true;
        ClearContextButton.IsEnabled = false;
        Append($"你：{prompt}");
        try
        {
            var selectedModel = ModelBox.Text.Trim();
            AppSettingsStore.SaveUi(key, selectedModel);
            if (conversationAgent is null || conversationApiKey != key || conversationModel != selectedModel)
            {
                conversationAgent = new DeepSeekAgent(hub, key, selectedModel);
                conversationApiKey = key;
                conversationModel = selectedModel;
            }
            await new LongTaskRunner(hub, conversationAgent).RunAsync(
                prompt,
                text => Dispatcher.BeginInvoke(() => Append(text)),
                cancellation.Token);
        }
        catch (OperationCanceledException)
        {
            Append("Agent 对话已中断。");
        }
        catch (Exception ex) { Append($"Agent 错误：{ex.Message}"); }
        finally
        {
            if (ReferenceEquals(activeAgentCancellation, cancellation))
            {
                cancellation.Dispose();
                activeAgentCancellation = null;
                SendButton.IsEnabled = true;
                InterruptButton.IsEnabled = false;
                ClearContextButton.IsEnabled = true;
            }
        }
    }

    private async void InterruptButton_Click(object sender, RoutedEventArgs e)
    {
        var cancellation = activeAgentCancellation;
        cancellation?.Cancel();
        InterruptButton.IsEnabled = false;
        try
        {
            var result = await hub.StopBaritoneAsync("all", bypassPrimaryProtection: true);
            Append($"已中断 Agent，并向全部在线实例发送 #stop：{System.Text.Json.JsonSerializer.Serialize(result, JsonProtocol.Options)}");
        }
        catch (Exception ex) { Append($"中断 Baritone 失败：{ex.Message}"); }
        finally
        {
            if (activeAgentCancellation is not null)
                InterruptButton.IsEnabled = true;
        }
    }

    private async void ClearContextButton_Click(object sender, RoutedEventArgs e)
    {
        if (activeAgentCancellation is not null)
        {
            MessageBox.Show("请先中断当前正在运行的任务，再清空上下文。", "Agent 正在执行");
            return;
        }
        ClearContextButton.IsEnabled = false;
        try
        {
            if (conversationAgent is not null) await conversationAgent.ClearContextAsync();
            await hub.CancelActivePlannedTaskAsync();
            ChatLog.Clear();
            Append("AI 上下文、压缩摘要、旧任务计划和对话显示已清空；实例状态与已保存配置不受影响。");
        }
        catch (Exception ex)
        {
            Append($"清空上下文失败：{ex.Message}");
        }
        finally
        {
            ClearContextButton.IsEnabled = true;
        }
    }
    private void OpenMapButton_Click(object sender, RoutedEventArgs e)
    {
        if (mapWindow is { IsLoaded: true })
        {
            mapWindow.Activate();
            return;
        }
        mapWindow = new MapWindow(hub) { Owner = this };
        mapWindow.Closed += (_, _) => mapWindow = null;
        mapWindow.Show();
    }

    private void InstanceList_MouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (InstanceList.SelectedItem is not InstanceView selected) return;
        new InstanceDetailsWindow(hub, selected.InstanceId) { Owner = this }.Show();
    }

    private void InstanceList_PreviewMouseRightButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (ItemsControl.ContainerFromElement(InstanceList, e.OriginalSource as DependencyObject) is not ListBoxItem item) return;
        item.IsSelected = true;
        contextInstanceId = (item.DataContext as InstanceView)?.InstanceId;
    }

    private async void SetPrimaryPlayer_Click(object sender, RoutedEventArgs e)
    {
        var instanceId = contextInstanceId ?? (InstanceList.SelectedItem as InstanceView)?.InstanceId;
        contextInstanceId = null;
        if (instanceId is null)
        {
            MessageBox.Show("没有取得右键点击的玩家实例，请重新右键该玩家。", "主要玩家未设置");
            return;
        }
        var result = await hub.SetPrimaryInstanceAsync(instanceId);
        Append($"主要玩家设置：{System.Text.Json.JsonSerializer.Serialize(result, JsonProtocol.Options)}");
        var synchronized = await hub.WaitForClusterStateAckAsync(instanceId, TimeSpan.FromSeconds(3));
        if (synchronized)
            Append($"主要玩家同步成功：客户端 {instanceId} 已确认，F8 附身现已可用。");
        else
            MessageBox.Show("C# 已设置主要玩家，但该 Minecraft 客户端尚未确认。请确认游戏已换成最新 Mod JAR，并重启该游戏实例。", "主要玩家等待同步");
    }

    private async void AllowPrimaryAi_Click(object sender, RoutedEventArgs e)
    {
        var result = await hub.SetPrimaryAiControlAllowedAsync(true);
        Append($"主要玩家 AI 权限：{System.Text.Json.JsonSerializer.Serialize(result, JsonProtocol.Options)}");
    }

    private async void ProtectPrimaryFromAi_Click(object sender, RoutedEventArgs e)
    {
        var result = await hub.SetPrimaryAiControlAllowedAsync(false);
        Append($"主要玩家 AI 权限：{System.Text.Json.JsonSerializer.Serialize(result, JsonProtocol.Options)}");
    }

    private async void StopPossession_Click(object sender, RoutedEventArgs e)
    {
        await hub.StopPossessionAsync();
        Append("已结束主要玩家的当前附身控制。");
    }

    private void HandleBaritoneWorkFinished(BaritoneWorkFinishedNotice notice)
    {
        Append($"Baritone 工作结束：{notice.PlayerName}（{notice.InstanceId}）。");
        Append(activeAgentCancellation is null
            ? "当前没有运行中的 Agent 对话。"
            : "完成事件已进入 Agent 队列；当前短决策结束后会立即读取新状态并判断下一步。");
    }

    private void Append(string text)
    {
        ChatLog.AppendText(text + "\n\n");
        ChatLog.ScrollToEnd();
    }
}

public sealed record InstanceView(string InstanceId, StatusMessage? Status, bool IsPrimary, bool AiControlAllowed, bool IsPossessionTarget,
    long ExpectedClusterStateVersion, long ClusterStateAckVersion, bool ClientReportsPrimary)
{
    public string Display
    {
        get
        {
            var synchronized = ClusterStateAckVersion >= ExpectedClusterStateVersion && ClientReportsPrimary == IsPrimary;
            var syncText = synchronized ? "已同步" : "同步中";
            var role = IsPrimary ? (AiControlAllowed ? $"【主要玩家 · AI 已授权 · {syncText}】" : $"【主要玩家 · AI 保护 · {syncText}】") : IsPossessionTarget ? "【当前附身目标】" : "";
            if (Status is null) return $"{role}{InstanceId}";
            var baritone = !Status.BaritoneLoaded
                ? "Baritone：未加载"
                : !Status.BaritoneStatusAvailable
                    ? $"Baritone：状态不可用{(string.IsNullOrWhiteSpace(Status.BaritoneStatusError) ? "" : $" · {Status.BaritoneStatusError}")}" 
                    : Status.BaritoneWorking
                        ? $"Baritone：工作中 [{string.Join(", ", Status.BaritoneProcesses ?? [])}]"
                        : "Baritone：空闲";
            return $"{role}{InstanceId}\n{Status.PlayerName}  HP {Status.Health:0.0}  饱食 {Status.Food}\n({Status.X}, {Status.Y}, {Status.Z})\n{baritone}";
        }
    }
}

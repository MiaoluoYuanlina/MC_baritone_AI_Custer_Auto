using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace BaritoneClusterServer;

/// Displays the server's live cached snapshot for one independently controllable instance.
public sealed class InstanceDetailsWindow : Window
{
    private static readonly JsonSerializerOptions PrettyJson = new(JsonProtocol.Options) { WriteIndented = true };
    private readonly ClusterHub hub;
    private readonly string instanceId;
    private readonly TextBox details = new()
    {
        IsReadOnly = true,
        AcceptsReturn = true,
        AcceptsTab = true,
        TextWrapping = TextWrapping.NoWrap,
        HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
        FontFamily = new FontFamily("Consolas"),
        FontSize = 13,
        Margin = new Thickness(10)
    };

    public InstanceDetailsWindow(ClusterHub hub, string instanceId)
    {
        this.hub = hub;
        this.instanceId = instanceId;
        Title = $"玩家实例完整上报 · {instanceId}";
        Width = 850;
        Height = 720;
        MinWidth = 520;
        MinHeight = 400;
        Content = details;
        hub.Changed += Refresh;
        Loaded += (_, _) => Refresh();
        Closed += (_, _) => hub.Changed -= Refresh;
    }

    private void Refresh()
    {
        if (!Dispatcher.CheckAccess()) { Dispatcher.BeginInvoke(Refresh); return; }
        details.Text = JsonSerializer.Serialize(hub.Status(instanceId), PrettyJson);
    }
}

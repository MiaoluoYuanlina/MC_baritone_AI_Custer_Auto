using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace BaritoneClusterServer;

public sealed class MapWindow : Window
{
    private readonly ClusterHub hub;
    private readonly MinecraftMapControl map = new();
    private readonly ComboBox dimensionBox = new() { MinWidth = 210, Margin = new Thickness(6, 0, 12, 0) };
    private readonly TextBlock statistics = new() { VerticalAlignment = VerticalAlignment.Center };
    private readonly TextBlock helpText = new() { Margin = new Thickness(10, 5, 10, 8), Foreground = Brushes.DimGray };
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromMilliseconds(500) };
    private bool updatingDimensions;
    private MapPlayerMarker? pendingMovePlayer;

    public MapWindow(ClusterHub hub)
    {
        this.hub = hub;
        Title = "Minecraft 集群地图";
        Width = 1200;
        Height = 800;
        MinWidth = 720;
        MinHeight = 480;

        var toolbar = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(10, 8, 10, 8) };
        toolbar.Children.Add(new TextBlock { Text = "维度", VerticalAlignment = VerticalAlignment.Center });
        toolbar.Children.Add(dimensionBox);
        var fitButton = new Button { Content = "显示全部地图", Padding = new Thickness(12, 4, 12, 4), Margin = new Thickness(0, 0, 8, 0) };
        fitButton.Click += (_, _) => map.FitToData();
        toolbar.Children.Add(fitButton);
        var playersButton = new Button { Content = "定位全部玩家", Padding = new Thickness(12, 4, 12, 4), Margin = new Thickness(0, 0, 8, 0) };
        playersButton.Click += (_, _) => map.CenterOnPlayers();
        toolbar.Children.Add(playersButton);
        var textures = new CheckBox { Content = "显示 Mod 材质", IsChecked = true, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 14, 0) };
        textures.Checked += (_, _) => map.ShowTextures = true;
        textures.Unchecked += (_, _) => map.ShowTextures = false;
        toolbar.Children.Add(textures);
        toolbar.Children.Add(statistics);

        helpText.Text = "左键拖动 · 滚轮缩放 · 右键蓝框玩家头像可跟随或选点移动 · 蓝框为可控实例，橙框为普通玩家";
        var root = new DockPanel();
        DockPanel.SetDock(toolbar, Dock.Top); root.Children.Add(toolbar);
        DockPanel.SetDock(helpText, Dock.Bottom); root.Children.Add(helpText);
        root.Children.Add(new Border { Margin = new Thickness(10, 0, 10, 0), BorderBrush = Brushes.Gray, BorderThickness = new Thickness(1), Child = map });
        Content = root;

        dimensionBox.SelectionChanged += (_, _) => { if (!updatingDimensions) RefreshMap(); };
        map.PlayerContextRequested += OpenPlayerContextMenu;
        map.MapDestinationRequested += MovePlayerToMapBlock;
        map.DestinationSelectionCancelled += () =>
        {
            pendingMovePlayer = null;
            helpText.Text = "已取消地图选点移动";
        };
        timer.Tick += (_, _) => RefreshMap();
        Loaded += (_, _) => { timer.Start(); RefreshMap(); };
        Closed += (_, _) => timer.Stop();
    }

    private void OpenPlayerContextMenu(MapPlayerMarker player)
    {
        var menu = new ContextMenu { PlacementTarget = map, Placement = PlacementMode.MousePoint };
        menu.Items.Add(new MenuItem { Header = $"{player.Name}  ({player.X:0}, {player.Y:0}, {player.Z:0})", IsEnabled = false });
        menu.Items.Add(new Separator());
        if (!player.IsControllable || string.IsNullOrWhiteSpace(player.InstanceId))
        {
            menu.Items.Add(new MenuItem { Header = "普通玩家不能作为控制实例", IsEnabled = false });
        }
        else
        {
            var followMenu = new MenuItem { Header = "跟随玩家" };
            var candidates = hub.MapSnapshot(player.Dimension).Players
                .Where(x => !string.Equals(x.Name, player.Name, StringComparison.OrdinalIgnoreCase) && x.Dimension == player.Dimension)
                .OrderByDescending(x => x.IsControllable).ThenBy(x => x.Name).ToList();
            foreach (var target in candidates)
            {
                var item = new MenuItem { Header = $"{target.Name}{(target.IsControllable ? "（可控）" : "（普通玩家）")}" };
                item.Click += async (_, _) =>
                {
                    await hub.UserFollowAsync(player.InstanceId!, target.Name);
                    helpText.Text = $"已命令 {player.Name} 跟随 {target.Name}";
                };
                followMenu.Items.Add(item);
            }
            if (candidates.Count == 0) followMenu.Items.Add(new MenuItem { Header = "当前维度没有其他玩家", IsEnabled = false });
            menu.Items.Add(followMenu);
            var move = new MenuItem { Header = "移动到地图位置…" };
            move.Click += (_, _) =>
            {
                pendingMovePlayer = player;
                map.BeginDestinationSelection();
                helpText.Text = $"正在为 {player.Name} 选择目的地：左键点击一个已有地图数据的方块，右键取消";
            };
            menu.Items.Add(move);
        }
        map.ContextMenu = menu;
        menu.Closed += (_, _) => { if (ReferenceEquals(map.ContextMenu, menu)) map.ContextMenu = null; };
        menu.IsOpen = true;
    }

    private async void MovePlayerToMapBlock(MapBlockCell? block)
    {
        if (pendingMovePlayer is null) return;
        if (block is null)
        {
            helpText.Text = "该位置还没有地图数据，请点击已渲染的方块";
            return;
        }
        var player = pendingMovePlayer;
        pendingMovePlayer = null;
        map.EndDestinationSelection();
        await hub.UserMoveAsync(player.InstanceId!, block.X, block.Y + 1, block.Z);
        helpText.Text = $"已命令 {player.Name} 移动到 ({block.X}, {block.Y + 1}, {block.Z})";
    }

    private void RefreshMap()
    {
        var selected = dimensionBox.SelectedItem as string;
        var snapshot = hub.MapSnapshot(selected);
        updatingDimensions = true;
        try
        {
            var current = dimensionBox.Items.Cast<string>().ToList();
            if (!current.SequenceEqual(snapshot.Dimensions))
            {
                dimensionBox.Items.Clear();
                foreach (var dimension in snapshot.Dimensions) dimensionBox.Items.Add(dimension);
            }
            dimensionBox.SelectedItem = snapshot.Dimension;
        }
        finally { updatingDimensions = false; }
        map.SetSnapshot(snapshot);
        var positioned = snapshot.Players.Count(x => x.PositionAvailable && x.Dimension == snapshot.Dimension);
        statistics.Text = $"方块 {snapshot.Blocks.Count:N0} · 玩家 {snapshot.Players.Count}（有坐标 {positioned}）";
    }
}

internal sealed class MinecraftMapControl : FrameworkElement
{
    public event Action<MapPlayerMarker>? PlayerContextRequested;
    public event Action<MapBlockCell?>? MapDestinationRequested;
    public event Action? DestinationSelectionCancelled;
    private WorldMapSnapshot? snapshot;
    private readonly Dictionary<string, ImageBrush> textureBrushes = new(StringComparer.Ordinal);
    private readonly Dictionary<string, ImageBrush> playerAvatarBrushes = new(StringComparer.Ordinal);
    private readonly Dictionary<int, SolidColorBrush> colorBrushes = [];
    private readonly Dictionary<(int X, int Z), MapBlockCell> cells = [];
    private double centerX;
    private double centerZ;
    private double scale = 8;
    private Point dragStart;
    private double dragCenterX;
    private double dragCenterZ;
    private bool dragging;
    private bool initialized;
    private bool showTextures = true;
    private bool selectingDestination;

    public bool ShowTextures { get => showTextures; set { showTextures = value; InvalidateVisual(); } }

    public void BeginDestinationSelection() { selectingDestination = true; Cursor = Cursors.Hand; }
    public void EndDestinationSelection() { selectingDestination = false; Cursor = Cursors.Cross; }

    public MinecraftMapControl()
    {
        Focusable = true;
        ClipToBounds = true;
        Cursor = Cursors.Cross;
        SnapsToDevicePixels = true;
        RenderOptions.SetBitmapScalingMode(this, BitmapScalingMode.NearestNeighbor);
    }

    public void SetSnapshot(WorldMapSnapshot value)
    {
        var dimensionChanged = snapshot?.Dimension != value.Dimension;
        snapshot = value;
        cells.Clear();
        foreach (var block in value.Blocks) cells[(block.X, block.Z)] = block;
        foreach (var material in value.Materials)
        {
            if (textureBrushes.ContainsKey(material.Id) || material.Pixels.Length != material.Width * material.Height * 4) continue;
            var bitmap = BitmapSource.Create(material.Width, material.Height, 96, 96, PixelFormats.Bgra32, null, material.Pixels, material.Width * 4);
            bitmap.Freeze();
            var brush = new ImageBrush(bitmap) { Stretch = Stretch.Fill, Opacity = 1.0, TileMode = TileMode.None };
            brush.Freeze(); textureBrushes[material.Id] = brush;
        }
        foreach (var player in value.Players)
        {
            if (string.IsNullOrWhiteSpace(player.SkinId) || playerAvatarBrushes.ContainsKey(player.SkinId) || player.SkinPixels is null || player.SkinWidth <= 0 || player.SkinHeight <= 0 || player.SkinPixels.Length != player.SkinWidth * player.SkinHeight * 4) continue;
            var bitmap = BitmapSource.Create(player.SkinWidth, player.SkinHeight, 96, 96, PixelFormats.Bgra32, null, player.SkinPixels, player.SkinWidth * 4);
            bitmap.Freeze();
            var brush = new ImageBrush(bitmap) { Stretch = Stretch.Fill, TileMode = TileMode.None };
            brush.Freeze(); playerAvatarBrushes[player.SkinId] = brush;
        }
        if (dimensionChanged) initialized = false;
        if (!initialized && (value.Blocks.Count > 0 || value.Players.Any(x => x.PositionAvailable))) FitToData();
        InvalidateVisual();
    }

    public void FitToData()
    {
        if (snapshot is null || (snapshot.Blocks.Count == 0 && !snapshot.Players.Any(x => x.PositionAvailable))) return;
        var points = snapshot.Blocks.Select(x => (X: (double)x.X, Z: (double)x.Z))
            .Concat(snapshot.Players.Where(x => x.PositionAvailable && x.Dimension == snapshot.Dimension).Select(x => (x.X, x.Z))).ToList();
        if (points.Count == 0) return;
        var minX = points.Min(x => x.X); var maxX = points.Max(x => x.X);
        var minZ = points.Min(x => x.Z); var maxZ = points.Max(x => x.Z);
        centerX = (minX + maxX) / 2.0; centerZ = (minZ + maxZ) / 2.0;
        var width = Math.Max(200, ActualWidth); var height = Math.Max(200, ActualHeight);
        scale = Math.Clamp(Math.Min(width / Math.Max(8, maxX - minX + 6), height / Math.Max(8, maxZ - minZ + 6)), 0.35, 32);
        initialized = true; InvalidateVisual();
    }

    public void CenterOnPlayers()
    {
        if (snapshot is null) return;
        var players = snapshot.Players.Where(x => x.PositionAvailable && x.Dimension == snapshot.Dimension).ToList();
        if (players.Count == 0) return;
        var minX = players.Min(x => x.X); var maxX = players.Max(x => x.X);
        var minZ = players.Min(x => x.Z); var maxZ = players.Max(x => x.Z);
        centerX = (minX + maxX) / 2.0; centerZ = (minZ + maxZ) / 2.0;
        scale = Math.Clamp(Math.Min(Math.Max(200, ActualWidth) / Math.Max(12, maxX - minX + 10), Math.Max(200, ActualHeight) / Math.Max(12, maxZ - minZ + 10)), 0.5, 32);
        initialized = true; InvalidateVisual();
    }

    protected override void OnRender(DrawingContext dc)
    {
        dc.DrawRectangle(new SolidColorBrush(Color.FromRgb(27, 30, 34)), null, new Rect(0, 0, ActualWidth, ActualHeight));
        if (snapshot is null) return;
        var minX = centerX - ActualWidth / (2 * scale) - 1; var maxX = centerX + ActualWidth / (2 * scale) + 1;
        var minZ = centerZ - ActualHeight / (2 * scale) - 1; var maxZ = centerZ + ActualHeight / (2 * scale) + 1;
        foreach (var block in snapshot.Blocks)
        {
            if (block.X < minX || block.X > maxX || block.Z < minZ || block.Z > maxZ) continue;
            var rect = new Rect(WorldToScreenX(block.X), WorldToScreenZ(block.Z), Math.Ceiling(scale) + 0.4, Math.Ceiling(scale) + 0.4);
            dc.DrawRectangle(ColorBrush(block.Color), null, rect);
            if (showTextures && scale >= 3 && textureBrushes.TryGetValue(block.Material, out var texture)) dc.DrawRectangle(texture, null, rect);
        }
        DrawChunkGrid(dc, minX, maxX, minZ, maxZ);
        foreach (var player in snapshot.Players.Where(x => x.PositionAvailable && x.Dimension == snapshot.Dimension)) DrawPlayer(dc, player);
    }

    private void DrawChunkGrid(DrawingContext dc, double minX, double maxX, double minZ, double maxZ)
    {
        if (scale < 1.2) return;
        var pen = new Pen(new SolidColorBrush(Color.FromArgb(100, 255, 255, 255)), scale >= 6 ? 1.0 : 0.5); pen.Freeze();
        for (var x = (int)Math.Floor(minX / 16) * 16; x <= maxX; x += 16) dc.DrawLine(pen, new Point(WorldToScreenX(x), 0), new Point(WorldToScreenX(x), ActualHeight));
        for (var z = (int)Math.Floor(minZ / 16) * 16; z <= maxZ; z += 16) dc.DrawLine(pen, new Point(0, WorldToScreenZ(z)), new Point(ActualWidth, WorldToScreenZ(z)));
    }

    private void DrawPlayer(DrawingContext dc, MapPlayerMarker player)
    {
        var x = WorldToScreenX(player.X + 0.5); var y = WorldToScreenZ(player.Z + 0.5);
        if (x < -80 || y < -40 || x > ActualWidth + 80 || y > ActualHeight + 40) return;
        var markerColor = player.IsControllable ? Brushes.DodgerBlue : Brushes.DarkOrange;
        var radius = Math.Clamp(scale * 0.58, 8, 14);
        var border = new Pen(markerColor, 3);
        if (!string.IsNullOrWhiteSpace(player.SkinId) && playerAvatarBrushes.TryGetValue(player.SkinId, out var avatar))
            dc.DrawRectangle(avatar, border, new Rect(x - radius, y - radius, radius * 2, radius * 2));
        else
            dc.DrawEllipse(markerColor, new Pen(Brushes.White, 1.5), new Point(x, y), radius, radius);
        var radians = player.Yaw * Math.PI / 180.0;
        dc.DrawLine(new Pen(Brushes.White, 2), new Point(x, y), new Point(x - Math.Sin(radians) * (radius + 7), y + Math.Cos(radians) * (radius + 7)));
        var text = new FormattedText($"{player.Name}  Y:{player.Y:0}", CultureInfo.CurrentUICulture, FlowDirection.LeftToRight,
            new Typeface("Segoe UI"), 12, Brushes.White, VisualTreeHelper.GetDpi(this).PixelsPerDip);
        dc.DrawText(text, new Point(x + radius + 4, y - text.Height / 2));
    }

    private SolidColorBrush ColorBrush(int color)
    {
        if (colorBrushes.TryGetValue(color, out var brush)) return brush;
        var argb = unchecked((uint)color); var alpha = (byte)(argb >> 24); if (alpha == 0) alpha = 255;
        brush = new SolidColorBrush(Color.FromArgb(alpha, (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb)); brush.Freeze(); colorBrushes[color] = brush; return brush;
    }

    protected override void OnMouseLeftButtonDown(MouseButtonEventArgs e)
    {
        Focus(); dragging = true; dragStart = e.GetPosition(this); dragCenterX = centerX; dragCenterZ = centerZ; Cursor = selectingDestination ? Cursors.Hand : Cursors.SizeAll; CaptureMouse(); e.Handled = true;
    }

    protected override void OnMouseLeftButtonUp(MouseButtonEventArgs e)
    {
        if (!dragging) return;
        var point = e.GetPosition(this); var click = (point - dragStart).LengthSquared <= 16;
        dragging = false; Cursor = selectingDestination ? Cursors.Hand : Cursors.Cross; ReleaseMouseCapture();
        if (selectingDestination && click)
        {
            var worldX = (int)Math.Floor(centerX + (point.X - ActualWidth / 2) / scale);
            var worldZ = (int)Math.Floor(centerZ + (point.Y - ActualHeight / 2) / scale);
            MapDestinationRequested?.Invoke(cells.GetValueOrDefault((worldX, worldZ)));
        }
        e.Handled = true;
    }

    protected override void OnMouseRightButtonUp(MouseButtonEventArgs e)
    {
        if (selectingDestination)
        {
            EndDestinationSelection(); DestinationSelectionCancelled?.Invoke(); e.Handled = true; return;
        }
        if (snapshot is null) return;
        var point = e.GetPosition(this);
        var player = snapshot.Players.Where(x => x.PositionAvailable && x.Dimension == snapshot.Dimension)
            .OrderBy(x => Math.Pow(WorldToScreenX(x.X + 0.5) - point.X, 2) + Math.Pow(WorldToScreenZ(x.Z + 0.5) - point.Y, 2)).FirstOrDefault();
        if (player is null) return;
        var distanceSquared = Math.Pow(WorldToScreenX(player.X + 0.5) - point.X, 2) + Math.Pow(WorldToScreenZ(player.Z + 0.5) - point.Y, 2);
        if (distanceSquared <= 625) { PlayerContextRequested?.Invoke(player); e.Handled = true; }
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        var point = e.GetPosition(this);
        if (dragging)
        {
            centerX = dragCenterX - (point.X - dragStart.X) / scale; centerZ = dragCenterZ - (point.Y - dragStart.Y) / scale; InvalidateVisual(); return;
        }
        if (snapshot is null) return;
        var player = snapshot.Players.Where(x => x.PositionAvailable && x.Dimension == snapshot.Dimension)
            .OrderBy(x => Math.Pow(WorldToScreenX(x.X + 0.5) - point.X, 2) + Math.Pow(WorldToScreenZ(x.Z + 0.5) - point.Y, 2)).FirstOrDefault();
        if (player is not null && Math.Pow(WorldToScreenX(player.X + 0.5) - point.X, 2) + Math.Pow(WorldToScreenZ(player.Z + 0.5) - point.Y, 2) < 225)
        {
            ToolTip = $"{player.Name}\n{(player.IsControllable ? "可控实例" : "普通玩家")} · {player.GameMode} · {player.Latency} ms\n({player.X:0.0}, {player.Y:0.0}, {player.Z:0.0})\n生命 {player.Health:0.0}/{player.MaxHealth:0.0}";
            return;
        }
        var worldX = (int)Math.Floor(centerX + (point.X - ActualWidth / 2) / scale);
        var worldZ = (int)Math.Floor(centerZ + (point.Y - ActualHeight / 2) / scale);
        ToolTip = cells.TryGetValue((worldX, worldZ), out var block) ? $"{block.Block}\n({block.X}, {block.Y}, {block.Z})" : null;
    }

    protected override void OnMouseWheel(MouseWheelEventArgs e)
    {
        var point = e.GetPosition(this);
        var beforeX = centerX + (point.X - ActualWidth / 2) / scale;
        var beforeZ = centerZ + (point.Y - ActualHeight / 2) / scale;
        scale = Math.Clamp(scale * (e.Delta > 0 ? 1.22 : 1 / 1.22), 0.25, 48);
        centerX = beforeX - (point.X - ActualWidth / 2) / scale;
        centerZ = beforeZ - (point.Y - ActualHeight / 2) / scale;
        initialized = true; InvalidateVisual(); e.Handled = true;
    }

    private double WorldToScreenX(double x) => (x - centerX) * scale + ActualWidth / 2;
    private double WorldToScreenZ(double z) => (z - centerZ) * scale + ActualHeight / 2;
}

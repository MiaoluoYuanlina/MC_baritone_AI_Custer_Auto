using System.Security.Cryptography;
using System.IO;
using System.Text;
using System.Text.Json;

namespace BaritoneClusterServer;

public sealed class SavedAppSettings
{
    public string ProtectedApiKey { get; set; } = "";
    public string Model { get; set; } = "deepseek-chat";
    public SavedClusterSettings Cluster { get; set; } = new();
}

public sealed class SavedClusterSettings
{
    public string? PrimaryInstanceId { get; set; }
    public string? PrimaryPlayerUuid { get; set; }
    public string? PrimaryPlayerName { get; set; }
    public bool PrimaryAiControlAllowed { get; set; }
    public bool AllowBaritoneBreak { get; set; } = true;
    public bool ShowControllablePlayerBoxes { get; set; }
    public bool ShowBaritoneRoutes { get; set; }
    public bool ShowAiRepliesInChat { get; set; }
    public bool ShowTaskProgress { get; set; } = true;
    public List<string> BlocksToDisallowBreaking { get; set; } = [];
    public List<string> BlocksToAvoidBreaking { get; set; } = ["minecraft:crafting_table", "minecraft:furnace", "minecraft:chest", "minecraft:trapped_chest"];
}

public static class AppSettingsStore
{
    private static readonly object Gate = new();
    private static readonly JsonSerializerOptions Options = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, WriteIndented = true };
    private static readonly string DirectoryPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "BaritoneAICluster");
    public static readonly string FilePath = Path.Combine(DirectoryPath, "settings.json");

    public static SavedAppSettings Load()
    {
        lock (Gate) return LoadUnsafe();
    }

    public static void SaveUi(string apiKey, string model)
    {
        Update(settings =>
        {
            settings.ProtectedApiKey = Protect(apiKey);
            settings.Model = string.IsNullOrWhiteSpace(model) ? "deepseek-chat" : model.Trim();
        });
    }

    public static string LoadApiKey(SavedAppSettings settings) => Unprotect(settings.ProtectedApiKey);

    public static void SaveCluster(SavedClusterSettings cluster) => Update(settings => settings.Cluster = cluster);

    private static void Update(Action<SavedAppSettings> change)
    {
        lock (Gate)
        {
            var settings = LoadUnsafe();
            change(settings);
            Directory.CreateDirectory(DirectoryPath);
            var temporary = FilePath + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(settings, Options), new UTF8Encoding(false));
            File.Move(temporary, FilePath, true);
        }
    }

    private static SavedAppSettings LoadUnsafe()
    {
        try
        {
            if (!File.Exists(FilePath)) return new();
            return JsonSerializer.Deserialize<SavedAppSettings>(File.ReadAllText(FilePath), Options) ?? new();
        }
        catch (IOException) { return new(); }
        catch (JsonException) { return new(); }
        catch (UnauthorizedAccessException) { return new(); }
    }

    private static string Protect(string value)
    {
        if (string.IsNullOrEmpty(value)) return "";
        try
        {
            var bytes = ProtectedData.Protect(Encoding.UTF8.GetBytes(value), Encoding.UTF8.GetBytes("BaritoneAICluster"), DataProtectionScope.CurrentUser);
            return Convert.ToBase64String(bytes);
        }
        catch (CryptographicException) { return ""; }
    }

    private static string Unprotect(string value)
    {
        if (string.IsNullOrWhiteSpace(value)) return "";
        try
        {
            var bytes = ProtectedData.Unprotect(Convert.FromBase64String(value), Encoding.UTF8.GetBytes("BaritoneAICluster"), DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(bytes);
        }
        catch (FormatException) { return ""; }
        catch (CryptographicException) { return ""; }
    }
}

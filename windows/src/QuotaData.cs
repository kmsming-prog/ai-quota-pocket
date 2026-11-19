using System;
using System.IO;
using System.Collections.Generic;
using System.Web.Script.Serialization;

internal sealed class QuotaEnvelope
{
    public QuotaSnapshot[] snapshots { get; set; }
}

internal sealed class QuotaSnapshot
{
    public string provider { get; set; }
    public long observedAt { get; set; }
    public QuotaWindow[] windows { get; set; }
}

internal sealed class QuotaWindow
{
    public string id { get; set; }
    public decimal? remainingPct { get; set; }
    public long? resetAt { get; set; }
    public string resetText { get; set; }
}

internal static class QuotaData
{
    internal const string HostName = "tw.kang.quota_pocket_windows";
    internal const string ExtensionId = "hijfjkcfigpjjhmkhknppdkdldkjglfg";
    internal const string CodexUrl = "https://chatgpt.com/codex/settings/usage";
    internal const string ClaudeUrl = "https://claude.ai/settings/usage";
    internal static readonly string Folder = AppDomain.CurrentDomain.BaseDirectory;
    internal static readonly string CacheFile = Path.Combine(Folder, "quota-cache.json");
    internal static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = 8192, RecursionLimit = 12 };
    internal static readonly DateTime Epoch = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);

    internal static QuotaEnvelope Parse(string text)
    {
        var value = Json.Deserialize<QuotaEnvelope>(text);
        if (value == null || value.snapshots == null || value.snapshots.Length == 0 || value.snapshots.Length > 2)
            throw new FormatException();
        var providers = new HashSet<string>();
        foreach (var item in value.snapshots)
        {
            if (item == null || (item.provider != "chatgpt" && item.provider != "claude") ||
                !providers.Add(item.provider) || !IsTimestamp(item.observedAt) ||
                item.windows == null || item.windows.Length == 0 || item.windows.Length > 2)
                throw new FormatException();
            var windows = new HashSet<string>();
            foreach (var window in item.windows)
            {
                if (window == null || (window.id != "session" && window.id != "weekly") || !windows.Add(window.id) ||
                    !window.remainingPct.HasValue || window.remainingPct < 0 || window.remainingPct > 100 ||
                    (window.resetAt.HasValue && !IsTimestamp(window.resetAt.Value)) ||
                    (window.resetText != null && window.resetText.Length > 160))
                    throw new FormatException();
            }
        }
        return value;
    }

    private static bool IsTimestamp(long value)
    {
        return value >= 1577836800000L && value <= 4102444800000L;
    }
}

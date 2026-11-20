using System;
using System.IO;

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
    internal static readonly DateTime Epoch = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);
    internal static readonly string StorageDirectory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "FeiyuCreativeStudio", "QuotaPocket");
}

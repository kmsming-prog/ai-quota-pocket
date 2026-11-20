using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

internal sealed class QuotaSource
{
    internal static readonly QuotaSource Codex = new QuotaSource(
        "chatgpt", "Codex", "QUOTA_POCKET_CODEX", "codex", "https://learn.chatgpt.com/docs/cli");
    internal static readonly QuotaSource Claude = new QuotaSource(
        "claude", "Claude", "QUOTA_POCKET_CLAUDE", "claude", "https://code.claude.com/docs/en/setup#install-on-native-windows");

    internal readonly string Provider;
    internal readonly string Label;
    internal readonly string InstallUrl;
    private readonly string pathOverride;
    private readonly string binary;

    private QuotaSource(string provider, string label, string pathOverride, string binary, string installUrl)
    {
        Provider = provider;
        Label = label;
        InstallUrl = installUrl;
        this.pathOverride = pathOverride;
        this.binary = binary;
    }

    internal string FindExecutable()
    {
        string requested = Environment.GetEnvironmentVariable(pathOverride);
        if (requested != null && Path.IsPathRooted(requested) && File.Exists(requested)) return requested;

        string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        string profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        string programs = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
        foreach (string directory in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator)
            .Concat(new[] { Path.Combine(profile, @".local\bin"), Path.Combine(local, @"Microsoft\WinGet\Links"),
                Path.Combine(programs, @"WinGet\Links") }))
        {
            string clean = directory.Trim().Trim('"');
            if (clean.Length == 0 || !Path.IsPathRooted(clean)) continue;
            string candidate = Path.Combine(clean, binary + ".exe");
            if (File.Exists(candidate)) return candidate;
        }
        if (this == Codex)
        {
            string bundle = Path.Combine(local, @"OpenAI\Codex\bin");
            if (Directory.Exists(bundle))
                return Directory.EnumerateDirectories(bundle)
                    .OrderByDescending(Directory.GetLastWriteTimeUtc)
                    .Select(directory => Path.Combine(directory, "codex.exe")).FirstOrDefault(File.Exists);
        }
        return null;
    }

    internal string LoginArguments { get { return this == Codex ? "login" : "auth login"; } }

    internal async Task<QuotaSnapshot> Read()
    {
        string executable = FindExecutable();
        if (executable == null) throw new QuotaReadException("未安裝");
        string directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "FeiyuCreativeStudio", "QuotaPocket");
        Directory.CreateDirectory(directory);
        return await ReadFrom(executable, directory).ConfigureAwait(false);
    }

    internal Task<QuotaSnapshot> ReadFrom(string executable, string directory)
    {
        return this == Codex ? ReadCodex(executable, directory) : ReadClaude(executable, directory);
    }

    private static async Task<QuotaSnapshot> ReadCodex(string executable, string directory)
    {
        using (var cli = new QuotaCli(executable, directory, "app-server", "--listen", "stdio://"))
        {
            await cli.WithDeadline(async () =>
            {
                await cli.Send(new
                {
                    id = 1, method = "initialize",
                    @params = new { clientInfo = new { name = "ai_quota_pocket", title = "AI Quota Pocket", version = "0.2.1" } }
                }).ConfigureAwait(false);
                return await cli.Reply(1).ConfigureAwait(false);
            }, 15000).ConfigureAwait(false);
            await cli.Send(new { method = "initialized" }).ConfigureAwait(false);
            await cli.Send(new { id = 2, method = "account/read", @params = new { refreshToken = false } }).ConfigureAwait(false);
            var account = QuotaProtocol.Object(await cli.WithDeadline(() => cli.Reply(2), 15000).ConfigureAwait(false), "account");
            if (QuotaProtocol.String(account, "type") != "chatgpt") throw new QuotaReadException("請登入");
            await cli.Send(new { id = 3, method = "account/rateLimits/read" }).ConfigureAwait(false);
            var reply = await cli.WithDeadline(() => cli.Reply(3), 30000).ConfigureAwait(false);
            return QuotaProtocol.Codex(reply);
        }
    }

    private static async Task<QuotaSnapshot> ReadClaude(string executable, string directory)
    {
        using (var auth = new QuotaCli(executable, directory, "auth", "status"))
        {
            var status = QuotaProtocol.Decode(await auth.Complete(12000).ConfigureAwait(false));
            if (QuotaProtocol.String(status, "authMethod") != "claude.ai") throw new QuotaReadException("請登入");
        }
        // Invoke only the built-in usage command, with tools, hooks and MCP disabled.
        using (var usage = new QuotaCli(executable, directory, "--print", "--output-format", "stream-json",
            "--verbose", "--no-session-persistence", "--tools", "", "--strict-mcp-config", "--mcp-config",
            "{\"mcpServers\":{}}", "--setting-sources", "", "--settings",
            "{\"remoteControlAtStartup\":false,\"disableAllHooks\":true}", "--", "/usage"))
        {
            return QuotaProtocol.Claude(await usage.Complete(30000).ConfigureAwait(false));
        }
    }
}

internal sealed class QuotaReadException : Exception
{
    internal QuotaReadException(string message) : base(message) { }
}

internal sealed class QuotaCli : IDisposable
{
    private readonly Process process;

    internal QuotaCli(string binary, string directory, params string[] args)
    {
        var options = new ProcessStartInfo(binary, String.Join(" ", args.Select(Argument)))
        {
            WorkingDirectory = directory, UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8
        };
        // Select the signed-in subscription when these keys happened to be set in the parent shell.
        foreach (string key in new[] { "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN",
            "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY", "OPENAI_API_KEY" })
            options.EnvironmentVariables.Remove(key);
        process = Process.Start(options);
        process.ErrorDataReceived += (sender, args2) => { };
        process.BeginErrorReadLine();
    }

    internal async Task Send(object message)
    {
        await process.StandardInput.WriteLineAsync(new JavaScriptSerializer().Serialize(message)).ConfigureAwait(false);
        await process.StandardInput.FlushAsync().ConfigureAwait(false);
    }

    internal async Task<Dictionary<string, object>> Reply(int id)
    {
        for (int count = 0; count < 300; count++)
        {
            string line = await process.StandardOutput.ReadLineAsync().ConfigureAwait(false);
            if (line == null) break;
            var reply = QuotaProtocol.Decode(line);
            if (QuotaProtocol.Number(reply, "id") != id) continue;
            var result = QuotaProtocol.Object(reply, "result");
            if (result != null) return result;
            break;
        }
        throw new QuotaReadException("讀取失敗");
    }

    internal Task<string> Complete(int timeout)
    {
        process.StandardInput.Close();
        return WithDeadline(async () =>
        {
            var text = new StringBuilder();
            var buffer = new char[4096];
            int size;
            while ((size = await process.StandardOutput.ReadAsync(buffer, 0, buffer.Length).ConfigureAwait(false)) != 0)
            {
                if (text.Length + size > 1024 * 1024) throw new QuotaReadException("讀取失敗");
                text.Append(buffer, 0, size);
            }
            return text.ToString();
        }, timeout);
    }

    internal async Task<T> WithDeadline<T>(Func<Task<T>> operation, int timeout)
    {
        Task<T> running = operation();
        using (var cancellation = new System.Threading.CancellationTokenSource())
        {
            if (await Task.WhenAny(running, Task.Delay(timeout, cancellation.Token)).ConfigureAwait(false) != running)
            {
                GC.KeepAlive(running.ContinueWith(task => { GC.KeepAlive(task.Exception); }, TaskContinuationOptions.OnlyOnFaulted));
                throw new QuotaReadException("讀取逾時");
            }
            cancellation.Cancel();
            return await running.ConfigureAwait(false);
        }
    }

    public void Dispose()
    {
        try { if (!process.HasExited) process.Kill(); }
        catch (System.ComponentModel.Win32Exception) { }
        catch (InvalidOperationException) { }
        process.Dispose();
    }

    private static string Argument(string value)
    {
        if (value.Length > 0 && !value.Any(character => Char.IsWhiteSpace(character) || character == '"')) return value;
        return "\"" + Regex.Replace(value, @"(\\*)(""|$)",
            match => new String('\\', match.Groups[1].Length * 2) + (match.Groups[2].Length > 0 ? "\\\"" : "")) + "\"";
    }
}

internal static class QuotaProtocol
{
    private static readonly Regex Row = new Regex(@"^Current (session|week(?: \(all models\))?):\s*(\d+(?:\.\d+)?)%\s*used(?:\s*·\s*resets\s+(.+))?$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    internal static Dictionary<string, object> Decode(string json)
    {
        if (json == null || json.Length > 1024 * 1024) return null;
        try { return new JavaScriptSerializer { MaxJsonLength = 1024 * 1024, RecursionLimit = 30 }
            .Deserialize<Dictionary<string, object>>(json); }
        catch (ArgumentException) { return null; }
        catch (InvalidOperationException) { return null; }
    }

    private static object Field(Dictionary<string, object> data, string key)
    {
        object value;
        return data != null && data.TryGetValue(key, out value) ? value : null;
    }

    internal static Dictionary<string, object> Object(Dictionary<string, object> data, string key)
    {
        return Field(data, key) as Dictionary<string, object>;
    }

    internal static string String(Dictionary<string, object> data, string key) { return Field(data, key) as string; }

    internal static decimal? Number(Dictionary<string, object> data, string key)
    {
        object value = Field(data, key);
        return value is int || value is long || value is decimal ? Convert.ToDecimal(value, CultureInfo.InvariantCulture) : (decimal?)null;
    }

    internal static QuotaSnapshot Codex(Dictionary<string, object> response)
    {
        var general = Object(Object(response, "rateLimitsByLimitId"), "codex") ?? Object(response, "rateLimits");
        var name = String(general, "limitId");
        var windows = new List<QuotaWindow>();
        if (name == null || name == "codex")
            foreach (string key in new[] { "primary", "secondary" })
            {
                var window = Object(general, key);
                decimal? minutes = Number(window, "windowDurationMins");
                string id = minutes == 10080 ? "weekly" : minutes > 0 && minutes < 1440 ? "session" : null;
                Add(windows, id, Number(window, "usedPercent"), EpochMillis(Number(window, "resetsAt")), null);
            }
        return Snapshot("chatgpt", windows);
    }

    internal static QuotaSnapshot Claude(string output)
    {
        var windows = new List<QuotaWindow>();
        var snippets = new List<string>();
        foreach (string line in output.Split('\n'))
        {
            var item = Decode(line);
            string eventType = String(item, "type");
            if (eventType == "assistant")
            {
                var limits = Object(Object(item, "usage_report"), "rate_limits");
                foreach (var limit in Items(limits, "limits"))
                {
                    string kind = String(limit, "kind");
                    string id = kind == "weekly_all" ? "weekly" : kind == "session" ? "session" : null;
                    string at = String(limit, "resets_at");
                    Add(windows, id, Number(limit, "percent"), EpochMillis(Number(limit, "resets_at")) ?? ExactTime(at), at);
                }
                foreach (var part in Items(Object(item, "message"), "content"))
                    if (String(part, "type") == "text") snippets.Add(String(part, "text"));
            }
            else if (eventType == "result" && System.Object.Equals(Field(item, "is_error"), false))
                snippets.Add(String(item, "result"));
        }
        foreach (string snippet in snippets.Where(value => value != null))
            foreach (string line in snippet.Split('\n'))
            {
                Match row = Row.Match(line.Trim());
                decimal used;
                if (!row.Success || !Decimal.TryParse(row.Groups[2].Value, NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out used)) continue;
                string id = row.Groups[1].Value.Equals("session", StringComparison.OrdinalIgnoreCase) ? "session" : "weekly";
                string reset = row.Groups[3].Value.Trim();
                Add(windows, id, used, ExactTime(reset), reset);
            }
        return Snapshot("claude", windows);
    }

    private static IEnumerable<Dictionary<string, object>> Items(Dictionary<string, object> obj, string field)
    {
        var list = Field(obj, field) as System.Collections.IEnumerable;
        return list == null ? Enumerable.Empty<Dictionary<string, object>>() : list.OfType<Dictionary<string, object>>();
    }

    private static long? EpochMillis(decimal? seconds)
    {
        return seconds >= 1577836800m && seconds <= 4102444800m && seconds == Decimal.Truncate(seconds.Value)
            ? (long)(seconds.Value * 1000) : (long?)null;
    }

    private static long? ExactTime(string text)
    {
        DateTimeOffset date;
        if (text == null || !Regex.IsMatch(text, @"^\d{4}-\d\d-\d\dT.*(?:Z|[+-]\d\d:\d\d)$") ||
            !DateTimeOffset.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.None, out date)) return null;
        long value = (long)(date.UtcDateTime - QuotaData.Epoch).TotalMilliseconds;
        return value >= 1577836800000L && value <= 4102444800000L ? value : (long?)null;
    }

    private static void Add(List<QuotaWindow> windows, string id, decimal? used, long? reset, string resetText)
    {
        if (id == null || !used.HasValue || used < 0 || used > 100 || windows.Any(window => window.id == id)) return;
        windows.Add(new QuotaWindow { id = id, remainingPct = 100 - used, resetAt = reset,
            resetText = resetText == null || resetText.Length > 160 ? "" : resetText });
    }

    private static QuotaSnapshot Snapshot(string provider, List<QuotaWindow> windows)
    {
        if (windows.Count == 0) throw new QuotaReadException("無額度資料");
        return new QuotaSnapshot { provider = provider, observedAt = (long)(DateTime.UtcNow - QuotaData.Epoch).TotalMilliseconds,
            windows = windows.ToArray() };
    }
}

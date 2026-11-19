using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Web.Script.Serialization;

internal static class FakeQuotaCli
{
    private static int Main(string[] args)
    {
        bool signedOut = Environment.GetEnvironmentVariable("QUOTA_TEST_SIGNED_OUT") == "1";
        if (args.SequenceEqual(new[] { "app-server", "--listen", "stdio://" }))
        {
            string line;
            bool ready = false;
            while ((line = Console.ReadLine()) != null)
            {
                var request = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(line);
                switch (request["method"] as string)
                {
                    case "initialize": Console.WriteLine("{\"id\":1,\"result\":{}}"); break;
                    case "initialized": ready = true; break;
                    case "account/read":
                        if (!ready) return 1;
                        Console.WriteLine("{\"id\":2,\"result\":{\"account\":" +
                            (signedOut ? "null" : "{\"type\":\"chatgpt\"}") + "}}");
                        break;
                    case "account/rateLimits/read":
                        if (!ready || signedOut) return 1;
                        Console.WriteLine("{\"method\":\"account/rateLimits/updated\"}");
                        Console.WriteLine("{\"id\":3,\"result\":{\"rateLimitsByLimitId\":{\"codex\":{\"limitId\":\"codex\"," +
                            "\"secondary\":{\"usedPercent\":100,\"windowDurationMins\":10080,\"resetsAt\":1795665600}," +
                            "\"primary\":{\"usedPercent\":25.5,\"windowDurationMins\":300,\"resetsAt\":1795240800}}}}}");
                        break;
                    default: return 2;
                }
            }
            return 0;
        }
        if (args.SequenceEqual(new[] { "auth", "status" }))
        {
            Console.WriteLine(signedOut ? "{\"authMethod\":\"none\"}" : "{\"authMethod\":\"claude.ai\"}");
            return signedOut ? 1 : 0;
        }
        if (args.Length > 2 && args[0] == "--print" && args.Last() == "/usage")
        {
            if (signedOut || !args.Contains("--strict-mcp-config") || !args.Contains("{\"mcpServers\":{}}") ||
                !args.Contains("{\"remoteControlAtStartup\":false,\"disableAllHooks\":true}") ||
                args[Array.IndexOf(args, "--tools") + 1] != "") return 2;
            Console.WriteLine("{\"type\":\"assistant\",\"usage_report\":{\"rate_limits\":{\"limits\":[" +
                "{\"kind\":\"weekly_sonnet\",\"percent\":90},{\"kind\":\"session\",\"percent\":18,\"resets_at\":\"2026-11-21T14:00:00+08:00\"}," +
                "{\"kind\":\"weekly_all\",\"percent\":39}]}}}");
            return 0;
        }
        if (args.SequenceEqual(new[] { "stall" })) Thread.Sleep(60000);
        return 2;
    }
}

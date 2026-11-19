package app.quotapocket.widget;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.OffsetDateTime;

public final class ClaudeUsageTest {
    public static void main(String[] args) throws Exception {
        long observed = Instant.parse("2026-11-19T11:10:00Z").toEpochMilli();
        String preciseReset = "2026-11-19T21:08:59.679456+08:00";
        JSONArray limits = new JSONArray()
                .put(new JSONObject().put("kind", "session").put("percent", 42.5).put("resets_at", preciseReset))
                .put(new JSONObject().put("kind", "weekly_scoped").put("percent", 88))
                .put(new JSONObject().put("kind", "weekly_all").put("percent", JSONObject.NULL));
        JSONObject structured = new JSONObject().put("type", "assistant").put("usage_report",
                new JSONObject().put("rate_limits", new JSONObject().put("limits", limits)));
        JSONObject result = new JSONObject().put("type", "result").put("is_error", false).put("result",
                "Current session: 42% used · resets Nov 19, 9:08pm (Asia/Taipei)\n"
                + "Current week (all models): 26% used · resets Nov 23, 8:05pm (Asia/Taipei)\n"
                + "Current week (Sonnet only): 88% used\nUsage credits: 98% used");
        JSONObject snapshot = ClaudeUsage.parse("diagnostic text\n" + structured + "\n" + result, observed);
        JSONArray windows = snapshot.getJSONArray("windows");
        check(windows.length() == 2, "Expected only the session and weekly windows");
        check(snapshot.getLong("observedAt") == observed, "Expected the observation time to be preserved");
        checkWindow(windows.getJSONObject(0), "session", 57.5, OffsetDateTime.parse(preciseReset).toInstant().toEpochMilli());
        checkWindow(windows.getJSONObject(1), "weekly", 74, Instant.parse("2026-11-23T12:05:00Z").toEpochMilli());

        JSONObject textOnly = new JSONObject().put("type", "result").put("is_error", false)
                .put("result", "Current session: 0% used · resets 9pm (Asia/Taipei)\n"
                        + "Current week (all models): 100% used · resets Nov 23, 8pm (Asia/Taipei)");
        windows = ClaudeUsage.parse(textOnly.toString(), observed).getJSONArray("windows");
        checkWindow(windows.getJSONObject(0), "session", 100, Instant.parse("2026-11-19T13:00:00Z").toEpochMilli());
        checkWindow(windows.getJSONObject(1), "weekly", 0, Instant.parse("2026-11-23T12:00:00Z").toEpochMilli());

        long december = Instant.parse("2026-12-31T14:30:00Z").toEpochMilli();
        JSONObject yearRollover = new JSONObject().put("type", "result")
                .put("result", "Current week (all models): 7% used · resets Jan 1, 2027, 12am (Asia/Taipei)");
        windows = ClaudeUsage.parse(yearRollover.toString(), december).getJSONArray("windows");
        checkWindow(windows.getJSONObject(0), "weekly", 93, Instant.parse("2026-12-31T16:00:00Z").toEpochMilli());

        result.put("is_error", true);
        check(ClaudeUsage.parse(result.toString(), observed).getJSONArray("windows").length() == 0,
                "Expected an error result to leave usage unknown");
        System.out.println("Claude usage percentage and reset-time checks passed.");
    }

    private static void checkWindow(JSONObject window, String id, double remaining, long reset) throws Exception {
        check(id.equals(window.getString("id")), "Unexpected usage period");
        check(window.getDouble("remainingPct") == remaining, "Unexpected remaining percentage");
        check(window.getLong("resetAt") == reset, "Unexpected reset time");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

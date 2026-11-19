package app.quotapocket.widget;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ClaudeUsage {
    private static final Pattern ROW = Pattern.compile("(?i)^Current (session|week(?: \\(all models\\))?):"
            + "\\s*(\\d+(?:\\.\\d+)?)%\\s*used(?:\\s*·\\s*resets\\s+(.+))?$");
    private static final Pattern CLOCK = Pattern.compile("(?i)^(?:([a-z]{3}) (\\d{1,2})(?:, (\\d{4}))?(?:,| at)?\\s+)?"
            + "(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s*\\(([^)]+)\\)$");
    private static final String MONTHS = "janfebmaraprmayjunjulaugsepoctnovdec";

    static JSONObject parse(String output, long observedAt) throws JSONException {
        Map<String, JSONObject> windows = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        for (String line : output.split("\\r?\\n")) {
            JSONObject event;
            try { event = new JSONObject(line); }
            catch (JSONException ignored) { continue; }
            if ("assistant".equals(event.optString("type"))) {
                JSONObject report = event.optJSONObject("usage_report");
                JSONObject limits = report == null ? null : report.optJSONObject("rate_limits");
                appendLimits(windows, limits == null ? null : limits.optJSONArray("limits"));
                JSONObject message = event.optJSONObject("message");
                JSONArray content = message == null ? null : message.optJSONArray("content");
                if (content == null) continue;
                for (int i = 0; i < content.length(); i++) {
                    JSONObject part = content.optJSONObject(i);
                    if (part != null && "text".equals(part.optString("type"))) {
                        text.append(part.optString("text")).append('\n');
                    }
                }
            } else if ("result".equals(event.optString("type")) && !event.optBoolean("is_error")) {
                text.append(event.optString("result")).append('\n');
            }
        }

        // Some accounts receive only the printed summary, or a structured report with no rows.
        for (String line : text.toString().split("\\r?\\n")) {
            Matcher row = ROW.matcher(line.trim());
            if (!row.matches()) continue;
            boolean weekly = !"session".equalsIgnoreCase(row.group(1));
            String id = weekly ? "weekly" : "session";
            if (windows.containsKey(id)) continue;
            double used = Double.parseDouble(row.group(2));
            if (!validPercentage(used)) continue;
            String reset = row.group(3) == null ? "" : row.group(3).trim();
            windows.put(id, window(id, used, reset, printedResetAt(reset, observedAt, weekly)));
        }

        JSONArray result = new JSONArray();
        for (JSONObject entry : windows.values()) result.put(entry);
        return new JSONObject().put("provider", "claude").put("observedAt", observedAt).put("windows", result);
    }

    private static void appendLimits(Map<String, JSONObject> windows, JSONArray limits) throws JSONException {
        if (limits == null) return;
        for (int i = 0; i < limits.length(); i++) {
            JSONObject limit = limits.optJSONObject(i);
            if (limit == null) continue;
            String id;
            switch (limit.optString("kind")) {
                case "session": id = "session"; break;
                case "weekly_all": id = "weekly"; break;
                default: continue;
            }
            double used = limit.optDouble("percent", Double.NaN);
            if (!validPercentage(used) || windows.containsKey(id)) continue;
            String reset = limit.isNull("resets_at") ? "" : limit.optString("resets_at");
            long resetAt = 0;
            try { resetAt = OffsetDateTime.parse(reset).toInstant().toEpochMilli(); }
            catch (java.time.DateTimeException ignored) { }
            windows.put(id, window(id, used, reset, resetAt));
        }
    }

    private static boolean validPercentage(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 100;
    }

    private static JSONObject window(String id, double used, String reset, long resetAt) throws JSONException {
        JSONObject result = new JSONObject().put("id", id).put("remainingPct", 100 - used).put("resetText", reset);
        if (resetAt > 0) result.put("resetAt", resetAt);
        return result;
    }

    private static long printedResetAt(String text, long observedAt, boolean weekly) {
        long precise = ResetLabel.exact(text);
        if (precise > 0) return precise;
        Matcher clock = CLOCK.matcher(text);
        if (!clock.matches()) return 0;
        try {
            ZoneId zone = ZoneId.of(clock.group(7));
            ZonedDateTime observed = Instant.ofEpochMilli(observedAt).atZone(zone);
            int hours = Integer.parseInt(clock.group(4));
            int minutes = clock.group(5) == null ? 0 : Integer.parseInt(clock.group(5));
            String meridiem = clock.group(6);
            if (meridiem != null) {
                if (hours < 1 || hours > 12) return 0;
                hours = hours % 12 + (meridiem.equalsIgnoreCase("pm") ? 12 : 0);
            }
            LocalTime time = LocalTime.of(hours, minutes);
            if (clock.group(1) == null) {
                if (weekly) return 0;
                ZonedDateTime candidate = observed.toLocalDate().atTime(time).atZone(zone);
                if (candidate.isBefore(observed.minusMinutes(1))) candidate = candidate.plusDays(1);
                return candidate.isAfter(observed.plusHours(6)) ? 0 : candidate.toInstant().toEpochMilli();
            }
            int monthOffset = MONTHS.indexOf(clock.group(1).toLowerCase(Locale.ROOT));
            if (monthOffset < 0 || monthOffset % 3 != 0) return 0;
            int month = monthOffset / 3 + 1;
            int day = Integer.parseInt(clock.group(2));
            String explicitYear = clock.group(3);
            int year = explicitYear == null ? observed.getYear() : Integer.parseInt(explicitYear);
            ZonedDateTime candidate = LocalDate.of(year, month, day).atTime(time).atZone(zone);
            if (explicitYear == null && candidate.isBefore(observed.minusDays(1))) candidate = candidate.plusYears(1);
            return candidate.isAfter(observed.plusDays(8)) ? 0 : candidate.toInstant().toEpochMilli();
        } catch (java.time.DateTimeException | NumberFormatException ignored) {
            return 0;
        }
    }
}

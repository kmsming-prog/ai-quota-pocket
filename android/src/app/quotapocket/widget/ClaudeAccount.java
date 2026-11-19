package app.quotapocket.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

final class ClaudeAccount {
    private static final String CONNECTED = "claude_connected";
    private static final String SNAPSHOT = "claude_snapshot";
    private static final String STATUS = "claude_status";
    private static final Pattern CONTROLS = Pattern.compile("\\u001b\\[[0-?]*[ -/]*[@-~]");
    private static final AtomicBoolean READING = new AtomicBoolean();
    private static volatile Attempt attempt;
    private static volatile String loginMessage;
    private static final String LAST_PROBE = "claude_last_probe";
    private static boolean signingOut;

    private static final class Attempt {
        volatile Process process;
        volatile String url;
        volatile boolean waitingForCode;
        Thread thread;
    }

    static boolean connected(Context context) { return QuotaStore.prefs(context).getBoolean(CONNECTED, false); }
    static String status(Context context) { return QuotaStore.prefs(context).getString(STATUS, "尚未登入"); }
    static String loginMessage() { return loginMessage; }
    static boolean inProgress() { return attempt != null; }
    static String loginUrl() { Attempt active = attempt; return active == null ? null : active.url; }
    static boolean needsCode() { Attempt active = attempt; return active != null && active.waitingForCode; }

    static JSONObject snapshot(Context context) {
        try { return new JSONObject(QuotaStore.prefs(context).getString(SNAPSHOT, "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    static synchronized void start(Context context) {
        if (attempt != null || signingOut || READING.get()) return;
        Context app = context.getApplicationContext();
        Attempt pending = new Attempt();
        attempt = pending;
        loginMessage = "準備啟動 Claude Code…";
        pending.thread = new Thread(() -> signIn(app, pending), "claude-login");
        pending.thread.start();
    }

    static synchronized void cancel() {
        Attempt pending = attempt;
        if (pending == null) return;
        pending.thread.interrupt();
        if (pending.process != null) pending.process.destroyForcibly();
        loginMessage = "正在停止登入…";
    }

    static void submitCode(String code) throws IOException {
        Attempt active = attempt;
        if (active == null || !active.waitingForCode || active.process == null
                || !code.matches("[A-Za-z0-9_~.#+=/-]{8,1400}")) {
            throw new IOException("Invalid authorization code");
        }
        send(active.process, code + "\r");
    }

    static synchronized void disconnect(Context context) {
        if (attempt != null || signingOut || READING.get()) return;
        Context app = context.getApplicationContext();
        signingOut = true;
        QuotaStore.prefs(app).edit().remove(CONNECTED).remove(SNAPSHOT).remove(STATUS).remove(LAST_PROBE).apply();
        QuotaWidget.renderAll(app);
        new Thread(() -> {
            String result = "已登出 Claude。";
            try { new ClaudeRuntime(app).logout(); }
            catch (Exception ignored) { result = "已停用手機更新。Claude 登出未完成，稍後可再登入。"; }
            synchronized (ClaudeAccount.class) {
                loginMessage = result;
                signingOut = false;
            }
        }, "claude-logout").start();
    }

    private static void signIn(Context context, Attempt pending) {
        String result;
        boolean loggedIn = false;
        boolean prepared = false;
        try {
            ClaudeRuntime runtime = new ClaudeRuntime(context);
            runtime.install(status -> loginMessage = status);
            prepared = true;
            loginMessage = "等待 Claude 的官方登入頁…";
            Process process = runtime.login();
            pending.process = process;
            Thread reader = watchLogin(process, pending);
            long linkDeadline = SystemClock.elapsedRealtime() + 60_000;
            while (pending.url == null && SystemClock.elapsedRealtime() < linkDeadline
                    && !process.waitFor(1, TimeUnit.SECONDS)) { }
            if (process.isAlive() && pending.url == null) {
                throw new ClaudeRuntime.SetupFailure("Claude 未能開啟官方登入頁，可重新按登入（CL-AUTH-LINK）。");
            }
            if (!process.waitFor(12, TimeUnit.MINUTES)) throw new IOException("Login timed out");
            reader.join(1500);
            if (process.exitValue() != 0) throw new IOException("Login command failed");
            String auth = ClaudeRuntime.capture(runtime.authStatus(), 20);
            int first = auth.indexOf('{'), last = auth.lastIndexOf('}');
            if (first < 0 || last <= first || !new JSONObject(auth.substring(first, last + 1)).optBoolean("loggedIn")) {
                throw new IOException("Claude login incomplete");
            }
            QuotaStore.prefs(context).edit().putBoolean(CONNECTED, true).remove(SNAPSHOT).remove(LAST_PROBE)
                    .putString(STATUS, "等待首次更新").commit();
            loggedIn = true;
            result = null;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            result = "已取消 Claude 登入。";
        } catch (ClaudeRuntime.SetupFailure error) {
            result = error.getMessage();
        } catch (Exception ignored) {
            result = Thread.currentThread().isInterrupted() ? "已取消 Claude 登入。"
                    : prepared ? "Claude 官方授權未完成，可重新按登入（CL-AUTH）。"
                    : "手機未能準備 Claude 執行環境（CL-SETUP）。";
        } finally {
            if (pending.process != null && pending.process.isAlive()) pending.process.destroyForcibly();
        }
        synchronized (ClaudeAccount.class) {
            if (attempt == pending) { attempt = null; loginMessage = result; }
        }
        if (loggedIn) {
            QuotaWidget.renderAll(context);
            SyncJob.schedule(context);
            sync(context, true);
            QuotaWidget.renderAll(context);
        }
    }

    private static Thread watchLogin(Process process, Attempt pending) {
        Thread reader = new Thread(() -> {
            try (InputStreamReader input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] chunk = new char[1024];
                StringBuilder text = new StringBuilder();
                for (int count; (count = input.read(chunk)) != -1;) {
                    text.append(chunk, 0, count);
                    if (text.length() > 12000) text.delete(0, text.length() - 8000);
                    String visible = CONTROLS.matcher(text).replaceAll("");
                    String lower = visible.toLowerCase(Locale.ROOT);
                    if (lower.contains("paste") && (lower.contains("paste the code") || lower.contains("paste code"))) {
                        pending.waitingForCode = true;
                    }
                    if (pending.url == null) pending.url = ClaudeLoginLink.findIn(visible);
                }
            } catch (IOException ignored) { }
        }, "claude-login-output");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    static void sync(Context context, boolean manual) {
        if (!connected(context) || attempt != null || "請重新登入".equals(status(context))) return;
        if (!READING.compareAndSet(false, true)) return;
        try {
            synchronized (ClaudeAccount.class) {
                if (attempt != null || signingOut || !connected(context)) return;
            }
            SharedPreferences prefs = QuotaStore.prefs(context);
            long now = System.currentTimeMillis();
            long interval = 30 * 60 * 1000L;
            long lastProbe = prefs.getLong(LAST_PROBE, 0);
            if (!manual && now >= lastProbe && now - lastProbe < interval) return;
            prefs.edit().putLong(LAST_PROBE, now).apply();
            JSONObject result = readUsage(new ClaudeRuntime(context));
            prefs.edit().putString(SNAPSHOT, result.toString()).putString(STATUS, "已更新").apply();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (LoginRequired ignored) {
            QuotaStore.prefs(context).edit().putString(STATUS, "請重新登入").apply();
        } catch (ClaudeRuntime.CommandTimeout ignored) {
            QuotaStore.prefs(context).edit().putString(STATUS, "Claude 更新逾時（CL-USAGE-TIME）").apply();
        } catch (UsageUnavailable error) {
            QuotaStore.prefs(context).edit().putString(STATUS, error.getMessage()).apply();
        } catch (Exception ignored) {
            QuotaStore.prefs(context).edit().putString(STATUS, "Claude 用量讀取失敗（CL-USAGE-RUN）").apply();
        } finally {
            READING.set(false);
        }
    }

    private static JSONObject readUsage(ClaudeRuntime runtime) throws Exception {
        String output;
        try {
            output = ClaudeRuntime.capture(runtime.usage(), 60);
        } catch (ClaudeRuntime.CommandFailure failure) {
            checkUsageFailure(failure.output);
            throw failure;
        }
        JSONObject result = ClaudeUsage.parse(output, System.currentTimeMillis());
        if (result.getJSONArray("windows").length() > 0) return result;
        checkUsageFailure(output);
        throw new UsageUnavailable("Claude 未回傳額度（CL-USAGE-NODATA）");
    }

    private static void checkUsageFailure(String output) throws IOException {
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("please run /login") || lower.contains("not logged in")
                || lower.contains("oauth token has expired") || lower.contains("authentication required")) {
            throw new LoginRequired();
        }
        if (lower.contains("rate limited") || lower.contains("rate_limit_error") || lower.contains("too many requests")) {
            throw new UsageUnavailable("Claude 暫時限制額度查詢（CL-USAGE-LIMIT）");
        }
        if (lower.contains("unable to connect") || lower.contains("enotfound") || lower.contains("econnrefused")
                || lower.contains("certificate") || lower.contains("eai_again") || lower.contains("econnreset")) {
            throw new UsageUnavailable("Claude 額度連線失敗（CL-USAGE-NET）");
        }
    }

    private static void send(Process process, String text) throws IOException {
        process.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
    }

    private static final class LoginRequired extends IOException { }
    private static final class UsageUnavailable extends IOException {
        UsageUnavailable(String status) { super(status); }
    }
}

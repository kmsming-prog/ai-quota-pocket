package app.quotapocket.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.IOException;
import java.net.BindException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

final class CodexAccount {
    private static final String SESSION = "codex_session";
    private static final String SNAPSHOT = "codex_snapshot";
    private static final String STATUS = "codex_status";
    private static final String PENDING = "codex_pending_device";
    private static final Object STATE = new Object();
    private static final Object REFRESH = new Object();
    private static final long LOGIN_TIME_MILLIS = 15 * 60 * 1000L;

    private static Attempt attempt;
    private static String loginError;

    private enum Phase { START, WAITING, BROWSER, EXCHANGE, SAVE }

    private static final class Attempt {
        final boolean browser;
        volatile Phase phase = Phase.START;
        volatile CodexClient.DeviceCode device;
        volatile CodexBrowserLogin callback;
        volatile boolean retrying;
        Thread thread;

        Attempt(boolean browser) { this.browser = browser; }
    }

    static boolean connected(Context context) {
        return QuotaStore.prefs(context).contains(SESSION);
    }

    static String status(Context context) {
        return QuotaStore.prefs(context).getString(STATUS, "尚未登入");
    }

    static JSONObject snapshot(Context context) {
        try {
            return new JSONObject(QuotaStore.prefs(context).getString(SNAPSHOT, "{}"));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    static void start(Context context) {
        synchronized (STATE) {
            if (attempt != null) return;
            QuotaStore.prefs(context).edit().remove(PENDING).apply();
            startAttempt(context, false);
        }
    }

    static void startBrowser(Context context) {
        synchronized (STATE) {
            if (attempt != null) return;
            QuotaStore.prefs(context).edit().remove(PENDING).apply();
            startAttempt(context, true);
        }
    }

    static void resume(Context context) {
        synchronized (STATE) {
            if (attempt == null && QuotaStore.prefs(context).contains(PENDING)) startAttempt(context, false);
        }
    }

    private static void startAttempt(Context context, boolean browser) {
        Context app = context.getApplicationContext();
        loginError = null;
        Attempt pending = new Attempt(browser);
        pending.thread = new Thread(() -> signIn(app, pending), "codex-login");
        attempt = pending;
        pending.thread.start();
    }

    static String deviceCode() {
        synchronized (STATE) {
            return attempt == null || attempt.device == null ? null : attempt.device.code;
        }
    }

    static String loginStatus() {
        synchronized (STATE) {
            if (attempt == null) return loginError;
            if (attempt.retrying) return "網路暫時中斷，正在重試確認授權…";
            switch (attempt.phase) {
                case WAITING: return "正在等候 OpenAI 確認裝置授權…";
                case BROWSER: return "請在 OpenAI 網頁登入並確認授權。";
                case EXCHANGE: return "已收到授權，正在取得登入憑證…";
                case SAVE: return "正在儲存此手機的登入狀態…";
                default: return attempt.browser ? "正在準備瀏覽器登入…" : "正在向 OpenAI 申請一次性代碼…";
            }
        }
    }

    static String browserUrl() {
        synchronized (STATE) {
            return attempt == null || attempt.callback == null || attempt.phase != Phase.BROWSER
                    ? null : attempt.callback.requestUrl;
        }
    }

    static boolean loginInProgress() {
        synchronized (STATE) { return attempt != null; }
    }

    static void cancelLogin(Context context) {
        synchronized (STATE) {
            if (attempt != null) {
                attempt.thread.interrupt();
                if (attempt.callback != null) attempt.callback.close();
            }
            attempt = null;
            loginError = null;
            QuotaStore.prefs(context).edit().remove(PENDING).apply();
        }
    }

    static void disconnect(Context context) {
        synchronized (STATE) {
            cancelLogin(context);
            QuotaStore.prefs(context).edit().remove(SESSION).remove(SNAPSHOT).remove(STATUS).apply();
        }
        QuotaWidget.renderAll(context);
    }

    private static void signIn(Context context, Attempt pending) {
        String failure;
        try {
            JSONObject session = pending.browser ? browserSession(pending) : deviceSession(context, pending);
            pending.phase = Phase.SAVE;
            synchronized (STATE) {
                if (attempt != pending) return;
                boolean saved = QuotaStore.prefs(context).edit().putString(SESSION, seal(session))
                        .remove(PENDING).remove(SNAPSHOT).putString(STATUS, "等待首次更新").commit();
                if (!saved) throw new IOException("Session storage failed");
                attempt = null;
            }
            QuotaWidget.renderAll(context);
            SyncJob.schedule(context);
            sync(context);
            QuotaWidget.renderAll(context);
            return;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception error) {
            failure = failureMessage(pending.phase, error);
        }
        synchronized (STATE) {
            if (attempt == pending) {
                QuotaStore.prefs(context).edit().remove(PENDING).apply();
                attempt = null;
                loginError = failure;
            }
        }
    }

    private static JSONObject browserSession(Attempt pending) throws Exception {
        try (CodexBrowserLogin callback = new CodexBrowserLogin(1455)) {
            pending.callback = callback;
            pending.phase = Phase.BROWSER;
            String code = callback.awaitCode(LOGIN_TIME_MILLIS);
            pending.phase = Phase.EXCHANGE;
            return CodexClient.exchangeCode(code, callback.verifier, callback.redirect);
        } finally {
            pending.callback = null;
        }
    }

    private static JSONObject deviceSession(Context context, Attempt pending) throws Exception {
        long expiresAt = 0;
        SharedPreferences prefs = QuotaStore.prefs(context);
        String saved = prefs.getString(PENDING, null);
        if (saved != null) {
            try {
                JSONObject restored = new JSONObject(new String(QuotaStore.unseal(saved), StandardCharsets.UTF_8));
                expiresAt = restored.getLong("expiresAt");
                if (expiresAt > System.currentTimeMillis() && expiresAt - System.currentTimeMillis() <= LOGIN_TIME_MILLIS) {
                    pending.device = new CodexClient.DeviceCode(restored);
                }
            } catch (Exception ignored) { }
            if (pending.device == null) throw new IOException("Saved device authorization expired");
        } else {
            CodexClient.DeviceCode device = CodexClient.begin();
            expiresAt = System.currentTimeMillis() + LOGIN_TIME_MILLIS;
            synchronized (STATE) {
                if (attempt != pending) throw new InterruptedException();
                try { prefs.edit().putString(PENDING, seal(device.saved().put("expiresAt", expiresAt))).commit(); }
                catch (Exception ignored) { /* An unlocked session can still complete while the app is open. */ }
                pending.device = device;
            }
        }

        pending.phase = Phase.WAITING;
        long deadline = SystemClock.elapsedRealtime() + Math.max(0, expiresAt - System.currentTimeMillis());
        long interval = pending.device.intervalMillis;
        while (SystemClock.elapsedRealtime() + interval < deadline) {
            Thread.sleep(interval);
            JSONObject grant;
            try {
                grant = CodexClient.poll(pending.device);
                pending.retrying = false;
                interval = pending.device.intervalMillis;
            } catch (CodexClient.HttpFailure error) {
                if (error.status != 408 && error.status != 429 && error.status < 500) throw error;
                pending.retrying = true;
                interval = Math.max(interval, Math.min(30000, interval + 5000));
                continue;
            } catch (SocketTimeoutException | SocketException | UnknownHostException error) {
                pending.retrying = true;
                interval = Math.max(interval, Math.min(30000, interval + 5000));
                continue;
            }
            if (grant == null) continue;
            synchronized (STATE) {
                if (attempt != pending) throw new InterruptedException();
                prefs.edit().remove(PENDING).commit();
                pending.device = null;
                pending.phase = Phase.EXCHANGE;
            }
            return CodexClient.exchangeDevice(grant);
        }
        throw new SocketTimeoutException("Device authorization timed out");
    }

    private static String failureMessage(Phase phase, Exception error) {
        if (phase == Phase.SAVE) return "OpenAI 已授權，但手機無法儲存登入狀態（C-SAVE）。";
        if (error instanceof BindException) return "手機無法開啟瀏覽器登入（C-PORT）。可改用裝置碼。";
        if (error instanceof CodexBrowserLogin.Rejected) return "OpenAI 網頁的授權未完成（C-BROWSER）。請重新登入。";
        if (error instanceof CodexClient.HttpFailure) {
            CodexClient.HttpFailure http = (CodexClient.HttpFailure) error;
            if (http.challenge) return "OpenAI 擋下 App 的連線（C-NET）。請換個網路再試。";
            if (phase == Phase.EXCHANGE) return "已收到授權，但 OpenAI 拒絕換取憑證（C-TOKEN " + http.status + "）。請改用另一種登入方式。";
            return "OpenAI 未接受裝置碼登入（C-DEVICE " + http.status + "）。請確認裝置碼設定，或改用瀏覽器登入。";
        }
        if (phase == Phase.START && error instanceof IOException && "Saved device authorization expired".equals(error.getMessage())) {
            return "先前的裝置碼已失效（C-DEVICE）。請重新登入。";
        }
        if (error instanceof SocketTimeoutException && (phase == Phase.WAITING || phase == Phase.BROWSER)) {
            return "等候網頁授權逾時（C-WAIT）。請重新登入，並在 OpenAI 網頁完成確認。";
        }
        if (phase == Phase.EXCHANGE) return "已收到授權，但 App 未取得可用憑證（C-TOKEN）。請重新登入。";
        return "App 無法連線至 OpenAI（C-NET）。請確認手機網路後再試。";
    }

    static void sync(Context context) {
        synchronized (REFRESH) {
            SharedPreferences prefs = QuotaStore.prefs(context);
            String stored = prefs.getString(SESSION, null);
            if (stored == null || "請重新登入".equals(status(context))) return;
            try {
                JSONObject session = new JSONObject(new String(QuotaStore.unseal(stored), StandardCharsets.UTF_8));
                boolean renewed = false;
                long expiry = session.optLong("expires", 0);
                if (expiry > 0 && expiry <= System.currentTimeMillis() / 1000 + 60) {
                    session = CodexClient.renew(session);
                    stored = saveRenewal(prefs, stored, session);
                    if (stored == null) return;
                    renewed = true;
                }
                JSONObject result;
                try {
                    result = CodexClient.usage(session);
                } catch (CodexClient.HttpFailure error) {
                    if (error.status != 401 || renewed) throw error;
                    session = CodexClient.renew(session);
                    stored = saveRenewal(prefs, stored, session);
                    if (stored == null) return;
                    result = CodexClient.usage(session);
                }
                String status = result.getJSONArray("windows").length() == 0 ? "沒有額度資料" : "已更新";
                synchronized (STATE) {
                    if (stored.equals(prefs.getString(SESSION, null))) {
                        prefs.edit().putString(SNAPSHOT, result.toString()).putString(STATUS, status).apply();
                    }
                }
            } catch (CodexClient.HttpFailure error) {
                String message = error.status == 401 || error.invalidGrant ? "請重新登入"
                        : error.status == 429 ? "稍後再更新" : "連線失敗";
                setStatus(prefs, stored, message);
            } catch (Exception ignored) {
                setStatus(prefs, stored, "更新失敗");
            }
        }
    }

    private static String seal(JSONObject session) throws Exception {
        return QuotaStore.seal(session.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String saveRenewal(SharedPreferences prefs, String previous, JSONObject session) throws Exception {
        synchronized (STATE) {
            if (!previous.equals(prefs.getString(SESSION, null))) return null;
            String updated = seal(session);
            if (!prefs.edit().putString(SESSION, updated).commit()) throw new Exception("Session storage failed");
            return updated;
        }
    }

    private static void setStatus(SharedPreferences prefs, String session, String status) {
        synchronized (STATE) {
            if (session != null && session.equals(prefs.getString(SESSION, null))) {
                prefs.edit().putString(STATUS, status).apply();
            }
        }
    }
}

package app.quotapocket.widget;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.net.ssl.HttpsURLConnection;

final class CodexClient {
    static final String ISSUER = "https://auth.openai.com";
    private static final String DEVICE = ISSUER + "/api/accounts/deviceauth/";
    private static final String TOKEN = ISSUER + "/oauth/token";
    private static final String USAGE = "https://chatgpt.com/backend-api/wham/usage";
    static final String SIGN_IN = ISSUER + "/codex/device";

    // Public OAuth client identifier from the Apache-licensed OpenAI Codex device flow.
    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String AUTH_CLAIMS = "https://api.openai.com/auth";
    private static final long ONE_DAY_SECONDS = 86400;

    static final class DeviceCode {
        final String id;
        final String code;
        final long intervalMillis;

        DeviceCode(JSONObject response) throws Exception {
            id = required(response, "device_auth_id");
            code = response.optString("user_code", response.optString("usercode"));
            if (!code.matches("[A-Z0-9-]{4,24}")) throw new IOException("Invalid device code");
            intervalMillis = Math.max(5, Math.min(900, response.optLong("interval", 5))) * 1000;
        }

        JSONObject saved() throws Exception {
            return new JSONObject().put("device_auth_id", id).put("user_code", code).put("interval", intervalMillis / 1000);
        }
    }

    static final class HttpFailure extends IOException {
        final int status;
        final boolean invalidGrant;
        final String errorCode;
        final boolean challenge;

        HttpFailure(int status, String errorCode, boolean challenge) {
            super("Codex request failed (HTTP " + status + ")");
            this.status = status;
            this.errorCode = errorCode;
            this.challenge = challenge;
            this.invalidGrant = "invalid_grant".equals(errorCode) || "refresh_token_expired".equals(errorCode)
                    || "refresh_token_reused".equals(errorCode) || "refresh_token_invalidated".equals(errorCode);
        }

        boolean devicePending() {
            return !challenge && ("deviceauth_authorization_pending".equals(errorCode)
                    || "authorization_pending".equals(errorCode)
                    || ((status == 403 || status == 404) && errorCode.isEmpty()));
        }
    }

    static DeviceCode begin() throws Exception {
        return new DeviceCode(post(DEVICE + "usercode", new JSONObject().put("client_id", CLIENT_ID)));
    }

    static JSONObject poll(DeviceCode device) throws Exception {
        try {
            return post(DEVICE + "token", new JSONObject().put("device_auth_id", device.id)
                    .put("user_code", device.code));
        } catch (HttpFailure error) {
            if (error.devicePending()) return null;
            throw error;
        }
    }

    static JSONObject exchangeDevice(JSONObject grant) throws Exception {
        return exchangeCode(required(grant, "authorization_code"), required(grant, "code_verifier"),
                ISSUER + "/deviceauth/callback");
    }

    static String browserAuthorizationUrl(String redirect, String state, String challenge) throws Exception {
        return ISSUER + "/oauth/authorize?response_type=code&client_id=" + encode(CLIENT_ID)
                + "&redirect_uri=" + encode(redirect) + "&scope=" + encode("openid profile email offline_access")
                + "&code_challenge=" + encode(challenge) + "&code_challenge_method=S256&state=" + encode(state)
                + "&id_token_add_organizations=true&codex_cli_simplified_flow=true";
    }

    static JSONObject exchangeCode(String code, String verifier, String redirect) throws Exception {
        String body = "grant_type=authorization_code&client_id=" + encode(CLIENT_ID)
                + "&redirect_uri=" + encode(redirect) + "&code=" + encode(code) + "&code_verifier=" + encode(verifier);
        return session(request(TOKEN, "application/x-www-form-urlencoded", body, null), null);
    }

    static JSONObject renew(JSONObject previous) throws Exception {
        JSONObject response = post(TOKEN, new JSONObject().put("client_id", CLIENT_ID)
                .put("grant_type", "refresh_token").put("refresh_token", required(previous, "refresh")));
        return session(response, previous);
    }

    private static JSONObject session(JSONObject response, JSONObject previous) throws Exception {
        String access = required(response, "access_token");
        String refresh = response.isNull("refresh_token") ? (previous == null ? "" : previous.optString("refresh"))
                : response.optString("refresh_token", "");
        if (refresh.isEmpty()) throw new IOException("Missing refresh token");
        JSONObject accessClaims = claims(access);
        JSONObject account = claims(response.optString("id_token")).optJSONObject(AUTH_CLAIMS);
        if (account == null) account = accessClaims.optJSONObject(AUTH_CLAIMS);
        if (account != null && account.optBoolean("chatgpt_account_is_fedramp")) {
            throw new IOException("This workspace requires a dedicated endpoint");
        }
        String accountId = previous == null ? "" : previous.optString("account");
        if (account != null && !account.isNull("chatgpt_account_id")) accountId = account.optString("chatgpt_account_id", accountId);
        if (!accountId.matches("[a-zA-Z0-9_-]{0,128}")) throw new IOException("Invalid account id");
        if (previous != null && !previous.optString("account").isEmpty()
                && !previous.optString("account").equals(accountId)) {
            throw new IOException("Account changed during refresh");
        }
        long expiry = accessClaims.optLong("exp", 0);
        long duration = response.optLong("expires_in", 0);
        if (expiry == 0 && duration > 0 && duration <= 30 * ONE_DAY_SECONDS) {
            expiry = System.currentTimeMillis() / 1000 + duration;
        }
        return new JSONObject().put("access", access).put("refresh", refresh)
                .put("account", accountId).put("expires", expiry);
    }

    private static JSONObject claims(String token) {
        // Claims received over TLS only supply routing and expiry. The server authorizes the access token.
        try {
            String payload = token.split("\\.")[1];
            return new JSONObject(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    static JSONObject usage(JSONObject session) throws Exception {
        return snapshot(request(USAGE, null, null, session), System.currentTimeMillis());
    }

    static JSONObject snapshot(JSONObject response, long observedAt) throws Exception {
        JSONObject quota = response.optJSONObject("rate_limit");
        JSONArray windows = new JSONArray();
        if (quota != null) {
            addWindow(windows, quota.optJSONObject("primary_window"));
            addWindow(windows, quota.optJSONObject("secondary_window"));
        }
        return new JSONObject().put("provider", "codex").put("observedAt", observedAt).put("windows", windows);
    }

    private static void addWindow(JSONArray windows, JSONObject window) throws Exception {
        if (window == null) return;
        double used = window.optDouble("used_percent", Double.NaN);
        if (!Double.isFinite(used) || used < 0) return;
        long reset = window.optLong("reset_at", 0);
        long duration = window.optLong("limit_window_seconds", 0);
        String period = duration >= 6 * ONE_DAY_SECONDS ? "weekly" : "session";
        JSONObject result = new JSONObject().put("id", period).put("remainingPct", Math.max(0, 100 - used));
        if (reset > 0 && reset < 4102444800L) result.put("resetAt", reset * 1000);
        windows.put(result);
    }

    private static String required(JSONObject response, String key) throws Exception {
        String value = response.getString(key);
        if (value.isEmpty() || "null".equals(value)) throw new IOException("Missing Codex response field");
        return value;
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static JSONObject post(String url, JSONObject data) throws Exception {
        return request(url, "application/json", data.toString(), null);
    }

    private static JSONObject request(String url, String contentType, String body, JSONObject session) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "QuotaPocket-Android/1.4.1");
            connection.setRequestProperty("Accept", "application/json");
            if (session != null) {
                connection.setRequestProperty("Authorization", "Bearer " + required(session, "access"));
                String account = session.optString("account");
                if (!account.isEmpty()) connection.setRequestProperty("ChatGPT-Account-Id", account);
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", contentType);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream stream = connection.getOutputStream()) { stream.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String errorCode = "";
                if (url.startsWith(ISSUER + "/")) {
                    try {
                        JSONObject failure = readJson(connection.getErrorStream());
                        JSONObject detail = failure.optJSONObject("error");
                        errorCode = detail == null ? failure.optString("error", "") : detail.optString("code", "");
                        if (!errorCode.matches("[a-z_]{1,80}")) errorCode = "";
                    } catch (Exception ignored) { }
                }
                throw new HttpFailure(status, errorCode, "challenge".equalsIgnoreCase(connection.getHeaderField("CF-Mitigated")));
            }
            return readJson(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject readJson(InputStream stream) throws Exception {
        if (stream == null) throw new IOException("Empty Codex response");
        try (InputStream input = stream) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (bytes.size() + count > 128000) throw new IOException("Codex response too large");
                bytes.write(buffer, 0, count);
            }
            return new JSONObject(bytes.toString("UTF-8"));
        }
    }
}

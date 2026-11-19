package app.quotapocket.widget;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class CodexBrowserLogin implements AutoCloseable {
    private static final String CALLBACK = "/auth/callback";
    private static final String LOOPBACK = "127.0.0.1";
    private final ServerSocket listener;
    private final String host;
    final String redirect;
    final String requestUrl;
    final String verifier;
    private final String state;

    CodexBrowserLogin(int port) throws Exception {
        verifier = randomString();
        state = randomString();
        String challenge = urlEncode(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK), port), 4);
            listener.setSoTimeout(1000);
            host = LOOPBACK + ":" + listener.getLocalPort();
            redirect = "http://" + host + CALLBACK;
            requestUrl = CodexClient.browserAuthorizationUrl(redirect, state, challenge);
        } catch (Exception error) {
            close();
            throw error;
        }
    }

    String awaitCode(long timeoutMillis) throws Exception {
        long start = System.nanoTime();
        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < timeoutMillis) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try (Socket client = listener.accept()) {
                // Android browsers connect to this local-only address. Briefly wait for a full request.
                client.setSoTimeout(2500);
                String code;
                try { code = handle(client); }
                catch (IOException | IllegalArgumentException ignored) { continue; }
                if (code != null && !code.isEmpty()) return code;
                if (code != null) throw new Rejected();
            } catch (SocketTimeoutException ignored) { }
        }
        throw new SocketTimeoutException("Browser authorization timed out");
    }

    private String handle(Socket client) throws IOException {
        InputStream in = new BufferedInputStream(client.getInputStream());
        String request = line(in);
        if (request == null) return null;
        String requestHost = null;
        boolean complete = false;
        for (int i = 0; i < 32; i++) {
            String header = line(in);
            if (header == null) return null;
            if (header.isEmpty()) { complete = true; break; }
            if (header.regionMatches(true, 0, "Host:", 0, 5)) {
                if (requestHost != null) return null;
                requestHost = header.substring(5).trim();
            }
        }
        String[] parts = request.split(" ");
        if (!complete || !host.equals(requestHost) || parts.length != 3 || !"GET".equals(parts[0])) return null;
        URI url = URI.create(parts[1]);
        if (url.isAbsolute() || url.getRawAuthority() != null || !CALLBACK.equals(url.getPath())) {
            reply(client.getOutputStream(), "404 Not Found", "請返回額度口袋重新開啟登入頁。", false);
            return null;
        }
        Map<String, String> params = query(url.getRawQuery());
        String supplied = params.get("state");
        if (supplied == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.UTF_8))) {
            reply(client.getOutputStream(), "400 Bad Request", "這個登入頁已失效。請從額度口袋重新開始。", false);
            return null;
        }
        String code = params.get("code");
        if (code == null || code.isEmpty() || code.length() > 2048 || params.containsKey("error")) {
            reply(client.getOutputStream(), "400 Bad Request", "登入未完成，請返回額度口袋。", true);
            return "";
        }
        // A dropped response does not invalidate a callback already received and validated.
        try { reply(client.getOutputStream(), "200 OK", "已收到 OpenAI 授權，請返回額度口袋確認結果。", true); }
        catch (IOException ignored) { }
        return code;
    }

    private static String line(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int ch; (ch = input.read()) != -1;) {
            if (ch == '\n') return line.toString();
            if (line.length() >= 8192) throw new IOException("Invalid callback request");
            if (ch != '\r') line.append((char) ch);
        }
        return null;
    }

    private static Map<String, String> query(String encoded) throws IOException {
        Map<String, String> values = new HashMap<>();
        if (encoded == null) return values;
        for (String entry : encoded.split("&")) {
            String[] pair = entry.split("=", 2);
            if (pair.length != 2) continue;
            String name = URLDecoder.decode(pair[0], "UTF-8");
            if (!"state".equals(name) && !"code".equals(name) && !"error".equals(name)) continue;
            if (values.put(name, URLDecoder.decode(pair[1], "UTF-8")) != null) throw new IOException("Duplicate callback parameter");
        }
        return values;
    }

    private static void reply(OutputStream output, String status, String message, boolean link) throws IOException {
        byte[] html = ("<!doctype html><html lang=\"zh-Hant\"><meta charset=utf-8><meta name=viewport content=\"width=device-width, initial-scale=1\">"
                + "<title>額度口袋</title><style>body{background:#101517;color:#eef6f1;font:18px system-ui;padding:28px}a{color:#b7f3d5}</style>"
                + "<h1>額度口袋</h1><p>" + message + "</p>"
                + (link ? "<p><a href=\"quotapocket://codex-return\">返回額度口袋</a></p>" : "")).getBytes(StandardCharsets.UTF_8);
        output.write(("HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: " + html.length
                + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n"
                + "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(html);
        output.flush();
    }

    private static String randomString() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return urlEncode(bytes);
    }

    private static String urlEncode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }

    @Override public void close() {
        try { listener.close(); }
        catch (IOException ignored) { }
    }

    static final class Rejected extends IOException { }
}

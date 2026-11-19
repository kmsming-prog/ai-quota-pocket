package app.quotapocket.widget;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.net.ssl.HttpsURLConnection;

final class ClaudeRuntime {
    private static final String ALPINE = "https://dl-cdn.alpinelinux.org/alpine/v3.23";
    private static final String OFFICIAL_APK = "https://downloads.claude.ai/claude-code/apk/stable/";
    private static final String CLI_VERSION = "2.1.311-r1";
    private static final String CLI_PACKAGE = "claude-code-" + CLI_VERSION + ".apk";
    private static final String REPOSITORY = "/quota/claude-repo";
    private static final String REPOSITORIES_FILE = "/quota/claude-repositories.list";
    private static final String SIGNING_KEY = "395759c1f7449ef4cdef305a42e820f3c766d6090d142634ebdb049f113168b6";
    private static final String CLAUDE = "/usr/bin/claude";
    private static final String SAFE_USAGE_SETTINGS = "{\"remoteControlAtStartup\":false,\"disableAllHooks\":true}";
    // Dependency closure of the two signed Alpine 3.23.8 base images. apk verifies each signature.
    private static final String[] DEPENDENCY_APKS = {
        "bash-5.3.3-r1.apk", "libblkid-2.41.6-r1.apk", "libeconf-0.8.3-r0.apk", "libfdisk-2.41.6-r1.apk",
        "libgcc-15.2.0-r2.apk", "libmount-2.41.6-r1.apk", "libncursesw-6.5_p20251123-r0.apk",
        "libsmartcols-2.41.6-r1.apk", "libstdc++-15.2.0-r2.apk", "libuuid-2.41.6-r1.apk",
        "ncurses-terminfo-base-6.5_p20251123-r0.apk", "readline-8.3.1-r0.apk", "setarch-2.41.6-r1.apk",
        "skalibs-libs-2.14.4.0-r0.apk", "tzdata-2026f-r0.apk", "util-linux-misc-2.41.6-r1.apk", "utmps-libs-0.1.3.1-r0.apk"
    };

    private final Context context;
    private final File storage;
    private final File root;

    ClaudeRuntime(Context context) {
        this.context = context.getApplicationContext();
        storage = new File(context.getNoBackupFilesDir(), "claude-runtime");
        root = new File(storage, "alpine");
    }

    boolean installed() {
        return new File(storage, "ready").isFile() && Files.exists(new File(root, "usr/bin/claude").toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    void install(Consumer<String> status) throws Exception {
        if (installed()) return;
        directory(storage);
        String architecture = "arm64-v8a".equals(Build.SUPPORTED_ABIS[0]) ? "aarch64" : "x86_64";
        if (!Arrays.asList("arm64-v8a", "x86_64").contains(Build.SUPPORTED_ABIS[0])) {
            throw new SetupFailure("此手機與 Claude 執行環境不相容（CL-ABI）。");
        }
        boolean arm = "aarch64".equals(architecture);
        File previousPackage = new File(root, "quota/" + CLI_PACKAGE);
        File repository = new File(root, "quota/claude-repo/" + architecture);
        File cliPackage = new File(repository, CLI_PACKAGE);
        File baseReady = new File(storage, "alpine-ready-3.23.8");
        long alreadyDownloaded = Math.max(cliPackage.length(), previousPackage.length());
        if (!baseReady.isFile() && storage.getUsableSpace() + alreadyDownloaded < 450L * 1024 * 1024) {
            throw new SetupFailure("手機空間不足。請保留約 450 MB 再重試（CL-SPACE）。");
        }
        if (!baseReady.isFile()) {
            status.accept("正在下載及解開手機執行環境…");
            prepareBase(architecture, baseReady);
        }

        status.accept("正在測試手機執行環境…");
        verifyRuntime();
        if (!Files.exists(new File(root, "usr/bin/claude").toPath(), LinkOption.NOFOLLOW_LINKS)
                || !new File(root, "usr/bin/script").isFile() || !new File(root, "bin/bash").isFile()) {
            status.accept("正在驗證 Claude 官方套件索引…");
            prepareRepository(architecture, repository);
            long packageSize = arm ? 134564768L : 134661530L;
            String packageHash = arm ? "cf39de2e72b9597d3c08887434e2d9853070d66edcc9f9d09a9985ad78230a9a"
                    : "377b1d322ac7fae8a6778dace5ad1af4e2f6d5d680af37954a7005cf026ba3ff";
            try {
                if (!cliPackage.isFile() && previousPackage.isFile()) Files.move(previousPackage.toPath(), cliPackage.toPath());
                downloadCli(OFFICIAL_APK + architecture + "/" + CLI_PACKAGE, cliPackage, packageSize, packageHash, status);
            } catch (InterruptedException error) { throw error; }
            catch (Exception ignored) {
                throw new SetupFailure("Claude Code 下載未完成，可重新按登入續傳（CL-DOWNLOAD）。");
            }

            status.accept("正在驗證並安裝官方 Claude Code…");
            try {
                installPackages(architecture);
            } catch (InterruptedException error) { throw error; }
            catch (CommandFailure error) {
                if (error.integrity) {
                    Path dependencies = new File(root, "quota/deps").toPath();
                    if (Files.exists(dependencies)) removeUnfinishedTree(dependencies);
                    throw new SetupFailure("安裝時有套件未通過驗證。Claude Code 下載檔已保留，可重試（CL-PACKAGE-VERIFY）。");
                }
                String code = error.network ? "CL-DEPS-NET" : "CL-DEPS " + error.exitCode;
                throw new SetupFailure("手機未能安裝 Claude 所需元件（" + code + "）。");
            } catch (Exception ignored) {
                throw new SetupFailure("手機未能安裝 Claude 所需元件（CL-DEPS）。");
            }
        }
        try {
            capture(start(CLAUDE, "--version"), 30);
            String pty = capture(start("/usr/bin/script", "-q", "-e", "-c", "printf QUOTA_PTY_READY", "/dev/null"), 20);
            if (!pty.contains("QUOTA_PTY_READY")) throw new IOException("Terminal smoke check failed");
            Files.write(new File(storage, "ready").toPath(), new byte[] { 1 });
        } catch (InterruptedException error) { throw error; }
        catch (Exception ignored) { throw new SetupFailure("Claude Code 已下載，但手機未能啟動它（CL-CLI）。"); }
        Files.deleteIfExists(cliPackage.toPath());
        Files.deleteIfExists(previousPackage.toPath());
        Path dependencies = new File(root, "quota/deps").toPath();
        if (Files.exists(dependencies)) removeUnfinishedTree(dependencies);
    }

    private void prepareRepository(String architecture, File repository) throws Exception {
        try {
            directory(repository);
            writeClaudeSigningKey();
            String checksum = "aarch64".equals(architecture)
                    ? "1e1169b6e435ba375c5c265af47c091e3066f3ca7997552a2fd0997a73e3b1ff"
                    : "b78f46efab6d5bdab33ccbb2af06a85ff76e80ae65f433db89abc66106cda257";
            try (InputStream input = context.getAssets().open("claude-apkindex-" + architecture + ".tar.gz")) {
                copyVerified(input, new File(repository, "APKINDEX.tar.gz"), checksum, 64 * 1024);
            }
            Files.write(new File(root, REPOSITORIES_FILE.substring(1)).toPath(),
                    (REPOSITORY + "\n").getBytes(StandardCharsets.US_ASCII));
            capture(start("/sbin/apk", "verify", REPOSITORY + "/" + architecture + "/APKINDEX.tar.gz"), 20);
        } catch (InterruptedException error) { throw error; }
        catch (Exception ignored) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            throw new SetupFailure("Claude 官方套件索引未通過驗證（CL-INDEX）。");
        }
    }

    private void installPackages(String architecture) throws Exception {
        File directory = new File(root, "quota/deps");
        directory(directory);
        // The upstream archive is authenticated by the repository index. Adding it by filename
        // would require a separate archive signature that Anthropic does not ship for this release.
        List<String> offline = new ArrayList<>(Arrays.asList("/sbin/apk", "add", "--no-cache", "--no-network",
                "--repositories-file", REPOSITORIES_FILE, "claude-code=" + CLI_VERSION));
        try {
            for (String name : DEPENDENCY_APKS) {
                File destination = new File(directory, name);
                if (!destination.isFile()) fetchDependency(architecture, name, destination);
                offline.add("/quota/deps/" + name);
            }
        } catch (IOException unavailable) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            // A point-release mirror may retire an exact dependency version.
            capture(start("/sbin/apk", "add", "--no-cache", "--repositories-file", REPOSITORIES_FILE,
                    "--repository", ALPINE + "/main", "bash", "util-linux-misc", "tzdata", "claude-code=" + CLI_VERSION), 360);
            return;
        }
        capture(start(offline.toArray(new String[0])), 360);
    }

    private static void fetchDependency(String architecture, String name, File destination) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(ALPINE + "/main/" + architecture + "/" + name).openConnection();
        File temporary = new File(destination.getParentFile(), name + ".part");
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) throw new IOException("Dependency unavailable");
            long size = 0;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted() || (size += count) > 4L * 1024 * 1024) throw new IOException("Dependency download stopped");
                    output.write(buffer, 0, count);
                }
            }
            long expected = connection.getContentLengthLong();
            if (size == 0 || (expected > 0 && expected != size) || !temporary.renameTo(destination)) throw new IOException("Dependency download incomplete");
        } finally {
            connection.disconnect();
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private void prepareBase(String architecture, File baseReady) throws Exception {
        String checksum = "aarch64".equals(architecture)
                ? "3f1bd6811370264cc363bb991ff15ac67621b239ea6e25ff2eb22af3d0641473"
                : "56e2810205c17a5dff78cdd7b2c2af4ccdef16f18d765d5bb0f527438b136676";
        File archive = new File(storage, "alpine.download");
        try {
            download(ALPINE + "/releases/" + architecture + "/alpine-minirootfs-3.23.8-"
                    + architecture + ".tar.gz", archive, checksum);
        } catch (Exception ignored) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            throw new SetupFailure("手機執行環境下載或校驗失敗（CL-BASE）。");
        }
        try {
            if (root.exists()) removeUnfinishedTree(root.toPath());
            directory(root);
            capture(new ProcessBuilder("/system/bin/toybox", "tar", "-oxzf", archive.getAbsolutePath(),
                    "-C", root.getAbsolutePath()).redirectErrorStream(true).start(), 60);
            directory(new File(root, "quota"));
            directory(new File(root, "profile"));
            directory(new File(root, "tmp"));
            directory(new File(root, "etc/apk/keys"));
            Files.write(new File(root, "quota/mcp.json").toPath(), "{\"mcpServers\":{}}".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(root, "etc/apk/repositories").toPath(), (ALPINE + "/main\n").getBytes(StandardCharsets.UTF_8));
            Files.write(baseReady.toPath(), new byte[] { 1 });
        } catch (InterruptedException error) { throw error; }
        catch (Exception ignored) {
            throw new SetupFailure("手機未能解開 Claude 執行環境（CL-EXTRACT）。");
        } finally {
            Files.deleteIfExists(archive.toPath());
        }
    }

    private void writeClaudeSigningKey() throws Exception {
        File key = new File(root, "etc/apk/keys/claude-code.rsa.pub");
        try (InputStream input = context.getAssets().open("claude-code.rsa.pub")) {
            copyVerified(input, key, SIGNING_KEY, 8192);
        }
    }

    private void verifyRuntime() throws Exception {
        try {
            capture(start("/bin/busybox", "true"), 15);
        } catch (InterruptedException error) { throw error; }
        catch (CommandFailure error) {
            throw new SetupFailure("無法在此手機啟動執行環境（CL-LINUX " + error.exitCode + "）。");
        } catch (Exception ignored) {
            throw new SetupFailure("無法在此手機啟動執行環境（CL-LINUX）。");
        }
    }

    Process login() throws Exception {
        return terminal("exec " + CLAUDE + " auth login");
    }

    Process usage() throws Exception {
        // The pinned CLI exposes /usage as a local command in print mode.
        return start(CLAUDE, "--print", "--output-format", "stream-json", "--verbose", "--no-session-persistence",
                "--tools", "", "--strict-mcp-config", "--mcp-config", "/quota/mcp.json",
                "--settings", SAFE_USAGE_SETTINGS, "--", "/usage");
    }

    Process authStatus() throws Exception {
        return start(CLAUDE, "auth", "status");
    }

    void logout() throws Exception {
        capture(start(CLAUDE, "auth", "logout"), 30);
    }

    private Process terminal(String command) throws Exception {
        return start("/usr/bin/script", "-q", "-e", "-c", "stty rows 100 cols 120; " + command, "/dev/null");
    }

    private Process start(String... command) throws Exception {
        File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        File proot = new File(nativeDir, "libproot.so");
        File loader = new File(nativeDir, "libproot-loader.so");
        File temp = new File(storage, "tmp");
        File profile = new File(storage, "profile");
        directory(temp);
        directory(profile);
        updateDns();
        List<String> args = new ArrayList<>(Arrays.asList(proot.getAbsolutePath(), "--kill-on-exit", "--link2symlink",
                "-0", "-r", root.getAbsolutePath(), "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "/proc/self/fd:/dev/fd", "-b", new File(root, "tmp").getAbsolutePath() + ":/dev/shm",
                "-b", profile.getAbsolutePath() + ":/profile", "-w", "/quota"));
        args.addAll(Arrays.asList(command));
        ProcessBuilder builder = new ProcessBuilder(args).directory(storage).redirectErrorStream(true);
        Map<String,String> env = builder.environment();
        env.clear();
        env.put("PROOT_TMP_DIR", temp.getAbsolutePath());
        env.put("PROOT_LOADER", loader.getAbsolutePath());
        env.put("HOME", "/profile");
        env.put("USER", "root");
        env.put("SHELL", "/bin/bash");
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
        env.put("TMPDIR", "/tmp");
        env.put("LANG", "en_US.UTF-8");
        env.put("TZ", java.util.TimeZone.getDefault().getID());
        env.put("TERM", "xterm-256color");
        env.put("TERM_PROGRAM", "QuotaPocket");
        env.put("BROWSER", "/bin/false");
        env.put("DISABLE_AUTOUPDATER", "1");
        env.put("DISABLE_NONESSENTIAL_TRAFFIC", "1");
        env.put("CLAUDE_CODE_DISABLE_TERMINAL_TITLE", "1");
        return builder.start();
    }

    private void updateDns() throws Exception {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network network = manager.getActiveNetwork();
        LinkProperties links = network == null ? null : manager.getLinkProperties(network);
        if (links == null || links.getDnsServers().isEmpty()) return;
        StringBuilder config = new StringBuilder();
        for (InetAddress address : links.getDnsServers()) config.append("nameserver ").append(address.getHostAddress()).append('\n');
        Files.write(new File(root, "etc/resolv.conf").toPath(), config.toString().getBytes(StandardCharsets.US_ASCII));
    }

    static String capture(Process process, int timeoutSeconds) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (output.size() + count > 256000) break;
                    output.write(buffer, 0, count);
                }
            } catch (IOException ignored) { }
        }, "claude-output");
        reader.setDaemon(true);
        reader.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) throw new CommandTimeout();
            reader.join(2000);
            if (reader.isAlive()) throw new IOException("Claude command output did not finish");
            String result = output.toString("UTF-8");
            if (process.exitValue() != 0) throw new CommandFailure(process.exitValue(), result);
            return result;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void directory(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create runtime directory");
    }

    private static void removeUnfinishedTree(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) removeUnfinishedTree(child);
            }
        }
        Files.delete(path);
    }

    private static void download(String url, File destination, String checksum) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) throw new IOException("Runtime download rejected");
            try (InputStream input = connection.getInputStream()) {
                copyVerified(input, destination, checksum, 8L * 1024 * 1024);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void downloadCli(String url, File destination, long expected, String checksum, Consumer<String> progress) throws Exception {
        long size = destination.length();
        if (size > expected) { Files.delete(destination.toPath()); size = 0; }
        if (size < expected) {
            HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
            try {
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(45000);
                connection.setInstanceFollowRedirects(false);
                if (size > 0) connection.setRequestProperty("Range", "bytes=" + size + "-");
                int response = connection.getResponseCode();
                if (response == 200) size = 0;
                else if (response != 206 || !("bytes " + size + "-" + (expected - 1) + "/" + expected).equals(connection.getHeaderField("Content-Range"))) {
                    throw new IOException("Unexpected package download response");
                }
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(destination, size > 0)) {
                    byte[] buffer = new byte[64 * 1024];
                    int previousPercent = -1;
                    for (int count; (count = input.read(buffer)) != -1;) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        size += count;
                        if (size > expected) throw new IOException("Unexpected package size");
                        output.write(buffer, 0, count);
                        int percent = (int) (size * 100 / expected);
                        if (percent != previousPercent) { progress.accept("正在下載 Claude Code · " + percent + "%"); previousPercent = percent; }
                    }
                }
            } finally { connection.disconnect(); }
        }
        if (destination.length() != expected) throw new IOException("Package download incomplete");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        if (!checksum.equals(hex(digest.digest()))) {
            Files.deleteIfExists(destination.toPath());
            throw new IOException("Package checksum mismatch");
        }
    }

    static final class SetupFailure extends IOException {
        SetupFailure(String message) { super(message); }
    }

    static final class CommandTimeout extends IOException { }

    static final class CommandFailure extends IOException {
        final int exitCode;
        final String output;
        final boolean integrity;
        final boolean network;

        CommandFailure(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
            String lower = output.toLowerCase(java.util.Locale.ROOT);
            this.integrity = lower.contains("untrusted signature") || lower.contains("bad signature") || lower.contains("signature verification");
            this.network = lower.contains("temporary error") || lower.contains("network") || lower.contains("certificate") || lower.contains("resolve");
        }
    }

    private static void copyVerified(InputStream input, File destination, String checksum, long limit) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long size = 0;
        try (FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (Thread.currentThread().isInterrupted() || (size += count) > limit) throw new IOException("Runtime download stopped");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        if (!checksum.equals(hex(digest.digest()))) {
            Files.deleteIfExists(destination.toPath());
            throw new IOException("Runtime verification failed");
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}

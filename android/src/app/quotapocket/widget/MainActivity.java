package app.quotapocket.widget;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(15, 20, 25);
    private static final int TEXT = Color.rgb(238, 243, 250);
    private static final int MUTED = Color.rgb(184, 198, 209);
    private static final int ACCENT = Color.rgb(117, 226, 190);
    private static final int OUTLINE = Color.rgb(63, 78, 91);
    private static final String SETTINGS_OPEN = "settings_open";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String message = "";
    private TextView codexStatus;
    private TextView feedback;
    private Button signIn;
    private Button codexBrowser;
    private boolean openCodexBrowserWhenReady;
    private Button openLogin;
    private Button cancelLogin;
    private TextView claudeStatus;
    private Button claudeSignIn;
    private Button claudeBrowser;
    private boolean openClaudeBrowserWhenReady;
    private Button claudeCode;
    private Button claudeCancel;
    private Button addWidget;
    private SettingsPanel settings;
    private AlertDialog widgetModeDialog;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateStatus();
            handler.postDelayed(this, 1200);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        handlePair(getIntent());
        showHome();
        if (state != null && state.getBoolean(SETTINGS_OPEN)) showSettings();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(SETTINGS_OPEN, settings != null);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePair(intent);
        updateStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        CodexAccount.resume(this);
        if (CodexAccount.connected(this) || ClaudeAccount.connected(this)) SyncJob.schedule(this);
        tick.run();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (widgetModeDialog != null) widgetModeDialog.dismiss();
        if (settings != null) settings.dialog.dismiss();
        super.onDestroy();
    }

    private void handlePair(Intent intent) {
        Uri uri = intent.getData();
        if (uri == null || !"quotapocket".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) return;
        try {
            String fragment = uri.getFragment();
            if (fragment == null || !fragment.startsWith("payload=")) throw new Exception("Invalid pairing");
            QuotaStore.finishPair(this, fragment.substring(8));
            SyncJob.schedule(this);
            SyncJob.refresh(this);
            message = "已連接私人儀表板。";
        } catch (Exception ignored) {
            message = "配對未完成或已逾時，請按連接重新開始。";
        }
        intent.setData(null);
    }

    private void showHome() {
        LinearLayout layout = column();
        layout.setPadding(dp(22), dp(16), dp(22), dp(20));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label("額度口袋", 28, TEXT, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        header.addView(button("設定", false, this::showSettings));
        append(layout, header, 0);

        LinearLayout chatgpt = card(layout, "ChatGPT", Color.rgb(23, 39, 41), Color.rgb(53, 89, 81), 22);
        codexStatus = label("", 15, MUTED, false);
        append(chatgpt, codexStatus, 6);
        signIn = add(chatgpt, "登入 ChatGPT", () -> {
            message = "";
            openCodexBrowserWhenReady = true;
            CodexAccount.startBrowser(this);
            updateStatus();
        });
        openLogin = add(chatgpt, "複製代碼並開啟登入頁", this::openCodexLogin);
        codexBrowser = add(chatgpt, "開啟 OpenAI 登入頁", () -> {
            String url = CodexAccount.browserUrl();
            if (url != null) {
                openCodexBrowserWhenReady = false;
                browse(url);
            }
        });
        cancelLogin = add(chatgpt, "取消登入", () -> {
            openCodexBrowserWhenReady = false;
            CodexAccount.cancelLogin(this);
            updateStatus();
        });

        LinearLayout claude = card(layout, "Claude", Color.rgb(36, 33, 41), Color.rgb(86, 66, 77), 12);
        claudeStatus = label("", 15, MUTED, false);
        append(claude, claudeStatus, 6);
        claudeSignIn = add(claude, "登入 Claude", () -> {
            message = "";
            openClaudeBrowserWhenReady = true;
            ClaudeAccount.start(this);
            updateStatus();
        });
        claudeBrowser = add(claude, "開啟 Claude 登入頁", () -> {
            String url = ClaudeAccount.loginUrl();
            if (url != null) {
                openClaudeBrowserWhenReady = false;
                browse(url);
            }
        });
        claudeCode = add(claude, "貼上 Claude 授權碼", this::enterClaudeCode);
        claudeCancel = add(claude, "取消登入", () -> {
            openClaudeBrowserWhenReady = false;
            ClaudeAccount.cancel();
            updateStatus();
        });
        Button refresh = button("立即刷新", true, () -> {
            SyncJob.refresh(this);
            message = "已安排刷新";
            updateStatus();
        });
        append(layout, refresh, 20);
        addWidget = add(layout, "加入 2×1 小工具", this::pinWidget);
        feedback = label("", 14, MUTED, false);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        append(layout, feedback, 12);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.addView(layout);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                view.setPadding(0, dp(8), 0, 0);
            }
            return insets;
        });
        setContentView(scroll);
        scroll.requestApplyInsets();
        updateStatus();
    }

    private void showSettings() {
        if (settings != null) return;
        message = "";
        SettingsPanel panel = new SettingsPanel();
        settings = panel;
        panel.dialog.setOnDismissListener(dialog -> {
            if (settings == panel) settings = null;
        });
        panel.dialog.show();
        updateStatus();
    }

    private final class SettingsPanel {
        final AlertDialog dialog;
        final LinearLayout accountActions;
        final TextView dashboardStatus;
        final TextView feedback;
        final Button widgetMode;
        final Button deviceSignIn;
        final Button signOut;
        final Button claudeSignOut;
        final Button pair;
        final Button unpair;

        SettingsPanel() {
            LinearLayout content = column();
            content.setPadding(dp(20), dp(8), dp(20), dp(12));
            append(content, label("主畫面小工具", 16, TEXT, true), 0);
            widgetMode = add(content, "顯示方式", MainActivity.this::chooseWidgetMode);

            accountActions = column();
            append(accountActions, label("帳號", 16, TEXT, true), 0);
            deviceSignIn = add(accountActions, "ChatGPT 裝置碼登入", () -> {
                dialogDismiss();
                message = "";
                CodexAccount.start(MainActivity.this);
                updateStatus();
            });
            signOut = add(accountActions, "登出 ChatGPT", () -> {
                CodexAccount.disconnect(MainActivity.this);
                message = "已登出 ChatGPT";
                updateStatus();
            });
            claudeSignOut = add(accountActions, "登出 Claude", () -> {
                ClaudeAccount.disconnect(MainActivity.this);
                message = ClaudeAccount.connected(MainActivity.this)
                        ? "Claude 正在更新，請稍後再試。" : "已解除 Claude 手機連線";
                updateStatus();
            });
            append(content, accountActions, 18);

            LinearLayout dashboard = column();
            append(dashboard, label("電腦同步", 16, TEXT, true), 0);
            dashboardStatus = label("", 14, MUTED, false);
            append(dashboard, dashboardStatus, 4);
            pair = add(dashboard, "連接私人儀表板", () -> {
                try {
                    browse(QuotaStore.startPair(MainActivity.this));
                } catch (Exception ignored) {
                    message = "無法開始配對，請稍後再試。";
                    updateStatus();
                }
            });
            add(dashboard, "開啟儀表板", () -> browse(QuotaStore.ORIGIN));
            unpair = add(dashboard, "解除儀表板連接", () -> {
                QuotaStore.disconnect(MainActivity.this);
                message = "已解除儀表板連接";
                updateStatus();
            });
            append(content, dashboard, 18);
            feedback = label("", 14, MUTED, false);
            feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            append(content, feedback, 8);

            ScrollView scroll = new ScrollView(MainActivity.this);
            scroll.addView(content);
            dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("設定").setView(scroll).setPositiveButton("完成", null).create();
        }

        private void dialogDismiss() {
            dialog.dismiss();
        }

        void render(boolean canUseDeviceCode, boolean canSignOut, boolean canSignOutClaude) {
            setButtonText(widgetMode, "顯示方式 · " + QuotaWidget.displayMode(MainActivity.this).label);
            visible(accountActions, canUseDeviceCode || canSignOut || canSignOutClaude);
            visible(deviceSignIn, canUseDeviceCode);
            visible(signOut, canSignOut);
            visible(claudeSignOut, canSignOutClaude);

            SharedPreferences prefs = QuotaStore.prefs(MainActivity.this);
            boolean paired = prefs.contains("connection");
            String status = paired ? prefs.getString("status", "已連接") : "尚未連接";
            display(dashboardStatus, status);
            visible(pair, !paired || "請重新連接".equals(status));
            visible(unpair, paired);
            display(feedback, message);
        }
    }

    private void chooseWidgetMode() {
        WidgetDisplayMode[] modes = WidgetDisplayMode.values();
        String[] choices = {
            "自動（依連接狀態）",
            "只顯示 ChatGPT（1×1）",
            "只顯示 Claude（1×1）",
            "顯示兩者（2×1）"
        };
        widgetModeDialog = new AlertDialog.Builder(this).setTitle("小工具顯示方式")
                .setSingleChoiceItems(choices, QuotaWidget.displayMode(this).ordinal(), (dialog, index) -> {
                    QuotaWidget.setDisplayMode(this, modes[index]);
                    updateStatus();
                    dialog.dismiss();
                }).setNegativeButton("取消", null).create();
        widgetModeDialog.setOnDismissListener(dialog -> widgetModeDialog = null);
        widgetModeDialog.show();
    }

    private void updateStatus() {
        String code = CodexAccount.deviceCode();
        String progress = CodexAccount.loginStatus();
        boolean active = CodexAccount.loginInProgress();
        boolean connected = CodexAccount.connected(this);
        String status = CodexAccount.status(this);
        boolean canSignIn = !active && (!connected || "請重新登入".equals(status));
        display(codexStatus, code != null && active
                ? "一次性代碼  " + code + "\n" + progress
                : progress != null ? progress : accountStatus(status));
        visible(signIn, canSignIn);
        String browser = CodexAccount.browserUrl();
        visible(codexBrowser, browser != null);
        visible(openLogin, active && code != null);
        visible(cancelLogin, active);

        boolean claudeActive = ClaudeAccount.inProgress();
        boolean claudeConnected = ClaudeAccount.connected(this);
        String claudeState = ClaudeAccount.status(this);
        String claudeMessage = ClaudeAccount.loginMessage();
        String claudeUrl = ClaudeAccount.loginUrl();
        display(claudeStatus, claudeUrl != null ? "在瀏覽器完成授權後返回。"
                : claudeMessage != null ? claudeMessage : accountStatus(claudeState));
        visible(claudeSignIn, !claudeActive && (!claudeConnected || "請重新登入".equals(claudeState)));
        visible(claudeBrowser, claudeUrl != null);
        visible(claudeCode, ClaudeAccount.needsCode());
        visible(claudeCancel, claudeActive);

        display(feedback, message);
        setButtonText(addWidget, QuotaWidget.prefersSingle(this) ? "加入 1×1 小工具" : "加入 2×1 小工具");
        if (settings != null) settings.render(canSignIn, connected && !active, claudeConnected && !claudeActive);

        if (!active) openCodexBrowserWhenReady = false;
        if (openCodexBrowserWhenReady && browser != null && hasWindowFocus()) {
            openCodexBrowserWhenReady = false;
            browse(browser);
            return;
        }
        if (!claudeActive) openClaudeBrowserWhenReady = false;
        if (openClaudeBrowserWhenReady && claudeUrl != null && hasWindowFocus()) {
            openClaudeBrowserWhenReady = false;
            browse(claudeUrl);
        }
    }

    private void openCodexLogin() {
        String code = CodexAccount.deviceCode();
        if (code == null) return;
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData clip = ClipData.newPlainText("Codex 登入代碼", code);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        clipboard.setPrimaryClip(clip);
        browse(CodexClient.SIGN_IN);
    }

    private void enterClaudeCode() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("官方登入頁顯示的授權碼");
        new AlertDialog.Builder(this).setTitle("Claude 授權碼").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("確認", (dialog, button) -> {
                    try {
                        ClaudeAccount.submitCode(input.getText().toString().trim());
                        message = "正在等候 Claude 完成登入。";
                    } catch (Exception ignored) {
                        message = "無法送出授權碼，請確認登入仍在進行。";
                    }
                    updateStatus();
                }).show();
    }

    private void browse(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            message = "無法開啟網頁，請確認手機已安裝瀏覽器。";
            updateStatus();
        }
    }

    private void pinWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        boolean single = QuotaWidget.prefersSingle(this);
        ComponentName provider = new ComponentName(this, single ? SingleQuotaWidget.class : QuotaWidget.class);
        if (!manager.isRequestPinAppWidgetSupported() || !manager.requestPinAppWidget(provider, null, null)) {
            message = single ? "請從桌面的小工具清單加入「額度口袋 1×1」。"
                    : "請從桌面的小工具清單加入「額度口袋」的雙服務小工具。";
            updateStatus();
        }
    }

    private static String accountStatus(String status) {
        return "已更新".equals(status) ? "已連接" : status;
    }

    private static void visible(View view, boolean show) {
        view.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private static void display(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
        visible(view, !value.isEmpty());
    }

    private static void setButtonText(Button button, String value) {
        if (!TextUtils.equals(button.getText(), value)) button.setText(value);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void append(LinearLayout container, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        container.addView(child, params);
    }

    private LinearLayout card(LinearLayout parent, String name, int fill, int border, int topMargin) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(fill, border, 18));
        append(card, label(name, 20, TEXT, true), 0);
        append(parent, card, topMargin);
        return card;
    }

    private TextView label(String value, int size, int color, boolean heading) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        if (heading) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        }
        return view;
    }

    private Button button(String name, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(name);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? BACKGROUND : TEXT);
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setMinimumHeight(dp(48));
        button.setMinHeight(dp(48));
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        button.setStateListAnimator(null);
        int fill = primary ? ACCENT : Color.rgb(34, 44, 53);
        int border = primary ? ACCENT : OUTLINE;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x24000000 : 0x24ffffff),
                rounded(fill, border, 12), null));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private Button add(LinearLayout layout, String label, Runnable action) {
        Button button = button(label, false, action);
        append(layout, button, 8);
        return button;
    }

    private GradientDrawable rounded(int color, int border, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setStroke(dp(1), border);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }
}

using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        using (var instance = new Mutex(false, @"Local\KangQuotaPocketWindow"))
        {
            try { if (!instance.WaitOne(0)) return; }
            catch (AbandonedMutexException) { }
            try
            {
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new QuotaPocket(args.Contains("--demo")));
            }
            finally
            {
                instance.ReleaseMutex();
            }
        }
    }
}

internal sealed class QuotaPocket : Form
{
    private static readonly Color Background = Color.FromArgb(24, 29, 33);
    private static readonly Color Foreground = Color.FromArgb(241, 245, 246);
    private static readonly Color Muted = Color.FromArgb(179, 192, 197);
    private readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer { Interval = 3000 };
    private readonly NotifyIcon tray;
    private readonly ContextMenuStrip menu;
    private readonly QuotaCard chatgpt;
    private readonly QuotaCard claude;
    private readonly Label footer;
    private readonly bool demo;

    internal QuotaPocket(bool demo)
    {
        this.demo = demo;
        Text = "AI 額度口袋";
        Font = new Font("Microsoft JhengHei UI", 9F);
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = Background;
        ForeColor = Foreground;
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;
        ShowInTaskbar = false;
        TopMost = true;
        ClientSize = new Size(392, 159);

        AddLabel(this, Text, 12, 7, 280, 28, 11f, FontStyle.Bold, Foreground);
        var options = new Button
        {
            Text = "⋯", AccessibleName = "小工具選單", Location = new Point(346, 5),
            Size = new Size(33, 29), FlatStyle = FlatStyle.Flat, ForeColor = Foreground,
            BackColor = Background, Font = new Font("Segoe UI", 13F, FontStyle.Bold),
            Anchor = AnchorStyles.Top | AnchorStyles.Right
        };
        options.FlatAppearance.BorderSize = 0;
        Controls.Add(options);

        chatgpt = new QuotaCard("ChatGPT · Codex", Color.FromArgb(134, 230, 194), Color.FromArgb(27, 45, 42));
        claude = new QuotaCard("Claude", Color.FromArgb(253, 173, 140), Color.FromArgb(52, 42, 40));
        chatgpt.Location = new Point(12, 38);
        claude.Location = new Point(201, 38);
        Controls.Add(chatgpt);
        Controls.Add(claude);
        footer = AddLabel(this, "", 12, 133, 368, 22, 8f, FontStyle.Regular, Muted);

        menu = new ContextMenuStrip();
        var show = menu.Items.Add("收合至通知區", null, (sender, e) => ToggleVisibility());
        menu.Opening += (sender, e) => show.Text = Visible ? "收合至通知區" : "顯示小工具";
        menu.Items.Add("設定 Chrome", null, (sender, e) => ConnectChrome());
        menu.Items.Add("開啟 Codex 用量頁", null, (sender, e) => OpenUsage(QuotaData.CodexUrl));
        menu.Items.Add("開啟 Claude 用量頁", null, (sender, e) => OpenUsage(QuotaData.ClaudeUrl));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("結束", null, (sender, e) => Close());
        options.Click += (sender, e) => menu.Show(options, new Point(options.Width, options.Height), ToolStripDropDownDirection.AboveLeft);
        ContextMenuStrip = menu;
        tray = new NotifyIcon { Icon = System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath), Text = Text, ContextMenuStrip = menu, Visible = true };
        tray.MouseClick += (sender, e) => { if (e.Button == MouseButtons.Left) ToggleVisibility(); };

        timer.Tick += (sender, e) => { DockToCorner(); RefreshQuotas(); };
        Shown += (sender, e) => { DockToCorner(); RefreshQuotas(); timer.Start(); };
        FormClosed += (sender, e) => { timer.Dispose(); tray.Dispose(); menu.Dispose(); };
    }

    protected override bool ShowWithoutActivation { get { return true; } }

    private void DockToCorner()
    {
        Rectangle area = Screen.PrimaryScreen.WorkingArea;
        Location = new Point(Math.Max(area.Left, area.Right - Width - 12), Math.Max(area.Top, area.Bottom - Height - 10));
    }

    private void ToggleVisibility()
    {
        if (Visible) Hide();
        else
        {
            DockToCorner();
            Show();
        }
    }

    private void RefreshQuotas()
    {
        QuotaSnapshot[] items = new QuotaSnapshot[0];
        if (demo)
        {
            items = new[] {
                DemoItem("chatgpt", 73, 88, 70, 3600),
                DemoItem("claude", 82, 61, 183, 5200)
            };
        }
        else if (File.Exists(QuotaData.CacheFile))
        {
            try
            {
                using (var stream = File.Open(QuotaData.CacheFile, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                using (var reader = new StreamReader(stream))
                    if (stream.Length <= 8192) items = QuotaData.Parse(reader.ReadToEnd()).snapshots;
            }
            catch (IOException) { }
            catch (FormatException) { }
            catch (ArgumentException) { }
        }
        QuotaSnapshot codexItem = items.FirstOrDefault(item => item.provider == "chatgpt");
        QuotaSnapshot claudeItem = items.FirstOrDefault(item => item.provider == "claude");
        chatgpt.Display(codexItem);
        claude.Display(claudeItem);
        if (demo) footer.Text = "示範資料";
        else if (items.Length == 0) footer.Text = "從 ⋯ 選單設定 Chrome，再開啟官方用量頁";
        else
        {
            string coder = ReadTime("Codex", codexItem);
            string writer = ReadTime("Claude", claudeItem);
            footer.Text = "更新  " + coder + "  ·  " + writer;
        }
    }

    private static string ReadTime(string label, QuotaSnapshot item)
    {
        if (item == null) return label + " 等待讀取";
        DateTime lastRead = QuotaData.Epoch.AddMilliseconds(item.observedAt);
        string format = lastRead.ToLocalTime().Date == DateTime.Today ? "HH:mm" : "M/d HH:mm";
        return label + " " + lastRead.ToLocalTime().ToString(format);
    }

    private static QuotaSnapshot DemoItem(string provider, int session, int weekly, int sessionMinutes, int weeklyMinutes)
    {
        long now = (long)(DateTime.UtcNow - QuotaData.Epoch).TotalMilliseconds;
        return new QuotaSnapshot {
            provider = provider, observedAt = now,
            windows = new[] {
                new QuotaWindow { id = "session", remainingPct = session, resetAt = now + sessionMinutes * 60000L },
                new QuotaWindow { id = "weekly", remainingPct = weekly, resetAt = now + weeklyMinutes * 60000L }
            }
        };
    }

    private void ConnectChrome()
    {
        try
        {
            string chrome = FindChrome();
            string extension = Path.Combine(QuotaData.Folder, "Chrome");
            string bridge = Path.Combine(QuotaData.Folder, "QuotaPocketBridge.exe");
            if (!File.Exists(bridge) || !File.Exists(Path.Combine(extension, "manifest.json")))
                throw new FileNotFoundException("請把 ZIP 完整解壓，再開啟小工具。");

            string manifest = Path.Combine(QuotaData.Folder, "native-host.json");
            File.WriteAllText(manifest, QuotaData.Json.Serialize(new {
                name = QuotaData.HostName, description = "AI Quota Pocket for Windows",
                path = bridge, type = "stdio", allowed_origins = new[] { "chrome-extension://" + QuotaData.ExtensionId + "/" }
            }));
            using (RegistryKey key = Registry.CurrentUser.CreateSubKey(@"Software\Google\Chrome\NativeMessagingHosts\" + QuotaData.HostName))
                key.SetValue("", manifest, RegistryValueKind.String);

            Clipboard.SetText(extension);
            MessageBox.Show(this, "已複製擴充功能資料夾路徑。\n\n在即將開啟的 Chrome 擴充功能頁，開啟「開發人員模式」，點「載入未封裝項目」，貼上路徑並選取資料夾。\n\n接著從小工具的 ⋯ 選單開啟兩個用量頁。", Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
            Process.Start(chrome, "chrome://extensions/");
        }
        catch (Exception error)
        {
            MessageBox.Show(this, error.Message, Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
    }

    private void OpenUsage(string url)
    {
        try { Process.Start(FindChrome(), url); }
        catch (Exception error) { MessageBox.Show(this, error.Message, Text); }
    }

    private static string FindChrome()
    {
        var roots = new[] { Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData) };
        foreach (string root in roots)
        {
            string candidate = Path.Combine(root, @"Google\Chrome\Application\chrome.exe");
            if (File.Exists(candidate)) return candidate;
        }
        throw new FileNotFoundException("找不到 Chrome。請先安裝電腦版 Google Chrome。");
    }

    internal static Label AddLabel(Control parent, string text, int x, int y, int width, int height,
        float size, FontStyle style, Color color)
    {
        var label = new Label
        {
            Text = text, Location = new Point(x, y), Size = new Size(width, height),
            Font = new Font("Microsoft JhengHei UI", size, style), ForeColor = color,
            TextAlign = ContentAlignment.MiddleLeft, AutoEllipsis = true
        };
        parent.Controls.Add(label);
        return label;
    }

    private sealed class QuotaCard : Panel
    {
        private readonly Label[] values = new Label[2];
        private readonly Label[] resets = new Label[2];
        private readonly ToolTip details = new ToolTip();

        internal QuotaCard(string title, Color accent, Color background)
        {
            Size = new Size(179, 92);
            BackColor = background;
            AddLabel(this, title, 9, 3, 161, 22, 10f, FontStyle.Bold, accent);
            for (int slot = 0; slot < 2; slot++)
            {
                int x = 9 + slot * 85;
                AddLabel(this, slot == 0 ? "當期剩餘" : "本週剩餘", x, 25, 80, 16, 8f, FontStyle.Regular, Muted);
                values[slot] = AddLabel(this, "—", x, 40, 80, 30, 16f, FontStyle.Bold, accent);
                resets[slot] = AddLabel(this, "", x, 71, 80, 16, 8f, FontStyle.Regular, Foreground);
            }
        }

        internal void Display(QuotaSnapshot snapshot)
        {
            DateTime now = DateTime.UtcNow;
            bool stale = snapshot != null && (now - QuotaData.Epoch.AddMilliseconds(snapshot.observedAt)).TotalMinutes > 20;
            for (int slot = 0; slot < 2; slot++)
            {
                string id = slot == 0 ? "session" : "weekly";
                QuotaWindow window = snapshot == null ? null : snapshot.windows.FirstOrDefault(item => item.id == id);
                DateTime? reset = window != null && window.resetAt.HasValue
                    ? QuotaData.Epoch.AddMilliseconds(window.resetAt.Value) : (DateTime?)null;
                bool elapsed = reset.HasValue && reset.Value <= now;
                values[slot].Text = window == null ? "—" : window.remainingPct.Value.ToString("0.#") + "%";
                resets[slot].Text = window == null ? (snapshot == null ? "未連線" : "無資料")
                    : elapsed ? "待更新" : stale ? "快照已過期" : ResetLabel(window, reset);
                string info = window == null ? "" : window.resetText ?? "";
                details.SetToolTip(resets[slot], info);
            }
        }

        private static string ResetLabel(QuotaWindow window, DateTime? reset)
        {
            if (reset.HasValue)
            {
                DateTime local = reset.Value.ToLocalTime();
                return local.ToString(local.Date == DateTime.Today ? "HH:mm '重置'" : "M/d HH:mm");
            }
            return String.IsNullOrWhiteSpace(window.resetText) ? "時間未提供" : window.resetText;
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) details.Dispose();
            base.Dispose(disposing);
        }
    }
}

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        using (var instance = new Mutex(false, @"Local\KangQuotaPocketDirectWindow"))
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
    private readonly Dictionary<QuotaSource, QuotaSnapshot> snapshots = new Dictionary<QuotaSource, QuotaSnapshot>();
    private readonly Dictionary<QuotaSource, string> status = new Dictionary<QuotaSource, string>();
    private readonly HashSet<QuotaSource> reading = new HashSet<QuotaSource>();
    private readonly HashSet<QuotaSource> signingIn = new HashSet<QuotaSource>();
    private DateTime nextUpdate;

    internal QuotaPocket(bool demo)
    {
        this.demo = demo;
        Text = "AI 額度口袋";
        Font = new Font("Microsoft JhengHei UI", 9F);
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = Background;
        ForeColor = Foreground;
        Icon = System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath);
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
        menu.Items.Add("連接 Codex", null, async (sender, e) => await Connect(QuotaSource.Codex));
        menu.Items.Add("連接 Claude", null, async (sender, e) => await Connect(QuotaSource.Claude));
        menu.Items.Add("立即更新", null, async (sender, e) => await RefreshQuotas());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("結束", null, (sender, e) => Close());
        options.Click += (sender, e) => menu.Show(options, new Point(options.Width, options.Height), ToolStripDropDownDirection.AboveLeft);
        ContextMenuStrip = menu;
        tray = new NotifyIcon { Icon = this.Icon, Text = Text, ContextMenuStrip = menu, Visible = true };
        tray.MouseClick += (sender, e) => { if (e.Button == MouseButtons.Left) ToggleVisibility(); };

        timer.Tick += async (sender, e) =>
        {
            DockToCorner();
            RenderQuotas();
            if (DateTime.UtcNow >= nextUpdate) await RefreshQuotas();
        };
        Shown += async (sender, e) => { DockToCorner(); timer.Start(); await RefreshQuotas(); };
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

    private async Task RefreshQuotas()
    {
        nextUpdate = DateTime.UtcNow.AddMinutes(5);
        if (demo)
        {
            snapshots[QuotaSource.Codex] = DemoItem("chatgpt", 73, 88, 70, 3600);
            snapshots[QuotaSource.Claude] = DemoItem("claude", 82, 61, 183, 5200);
            RenderQuotas();
            return;
        }
        await Task.WhenAll(Update(QuotaSource.Codex), Update(QuotaSource.Claude));
    }

    private async Task Update(QuotaSource service)
    {
        if (signingIn.Contains(service) || !reading.Add(service)) return;
        status[service] = "讀取中";
        RenderQuotas();
        try
        {
            snapshots[service] = await Task.Run(() => service.Read());
            status.Remove(service);
        }
        catch (QuotaReadException failure)
        {
            status[service] = failure.Message;
            if (failure.Message == "請登入" || failure.Message == "未安裝") snapshots.Remove(service);
        }
        catch (Exception) { status[service] = "讀取失敗"; }
        finally { reading.Remove(service); RenderQuotas(); }
    }

    private void RenderQuotas()
    {
        if (IsDisposed || Disposing) return;
        QuotaSnapshot codexItem, claudeItem;
        snapshots.TryGetValue(QuotaSource.Codex, out codexItem);
        snapshots.TryGetValue(QuotaSource.Claude, out claudeItem);
        chatgpt.Display(codexItem);
        claude.Display(claudeItem);
        footer.Text = demo ? "示範資料" :
            ReadStatus(QuotaSource.Codex, codexItem) + "  ·  " + ReadStatus(QuotaSource.Claude, claudeItem);
    }

    private string ReadStatus(QuotaSource service, QuotaSnapshot item)
    {
        string message;
        if (status.TryGetValue(service, out message)) return service.Label + " " + message;
        if (item == null) return service.Label + " 等待讀取";
        DateTime lastRead = QuotaData.Epoch.AddMilliseconds(item.observedAt);
        string format = lastRead.ToLocalTime().Date == DateTime.Today ? "HH:mm" : "M/d HH:mm";
        return service.Label + " " + lastRead.ToLocalTime().ToString(format);
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

    private async Task Connect(QuotaSource service)
    {
        if (demo || signingIn.Contains(service)) return;
        if (reading.Contains(service))
        {
            MessageBox.Show(this, "正在讀取 " + service.Label + " 額度，請稍候再連接。", Text);
            return;
        }
        bool wasSigningIn;
        try
        {
            string executable = service.FindExecutable();
            if (executable == null)
            {
                if (service == QuotaSource.Claude)
                {
                    using (var dialog = CreateClaudeInstallDialog()) dialog.ShowDialog(this);
                }
                else if (MessageBox.Show(this, "請先安裝官方命令列版 Codex CLI。要開啟安裝說明嗎？",
                    Text, MessageBoxButtons.YesNo, MessageBoxIcon.Information) == DialogResult.Yes)
                {
                    Process.Start(service.InstallUrl);
                }
                return;
            }
            signingIn.Add(service);
            snapshots.Remove(service);
            status[service] = "登入中";
            RenderQuotas();
            using (var login = Process.Start(new ProcessStartInfo(executable, service.LoginArguments) { UseShellExecute = true }))
                await Task.Run(() => login.WaitForExit());
        }
        catch (Exception)
        {
            MessageBox.Show(this, "無法開啟 " + service.Label + " 登入。請檢查官方命令列工具。", Text);
        }
        finally
        {
            wasSigningIn = signingIn.Remove(service);
        }
        if (wasSigningIn && !IsDisposed) await Update(service);
    }

    internal static Form CreateClaudeInstallDialog()
    {
        const string install = "winget install --id Anthropic.ClaudeCode --exact --source winget";
        var dialog = new Form
        {
            Text = "安裝 Claude Code 命令列版",
            ClientSize = new Size(550, 200),
            FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = FormStartPosition.CenterParent,
            MaximizeBox = false, MinimizeBox = false, ShowInTaskbar = false,
            AutoScaleMode = AutoScaleMode.Dpi,
            Font = new Font("Microsoft JhengHei UI", 9F),
            Icon = System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath)
        };
        Color textColor = SystemColors.ControlText;
        AddLabel(dialog, "額度口袋需要 Windows 原生的 Claude Code CLI。", 15, 11, 520, 22, 10f, FontStyle.Bold, textColor);
        AddLabel(dialog, "即使 Claude 桌面版已經有「Code」分頁，仍需另外安裝命令列版。",
            15, 35, 520, 23, 9f, FontStyle.Regular, textColor);
        AddLabel(dialog, "以一般權限開啟 Windows 終端機或 PowerShell，再執行這行",
            15, 65, 520, 21, 9f, FontStyle.Regular, textColor);
        var command = new TextBox
        {
            Text = install, ReadOnly = true, Location = new Point(15, 88), Size = new Size(518, 24),
            Font = new Font("Consolas", 10f), AccessibleName = "Claude Code CLI 安裝指令"
        };
        dialog.Controls.Add(command);
        AddLabel(dialog, "完成後，回到額度口袋點「連接 Claude」登入。", 15, 121, 520, 22, 9f, FontStyle.Regular, textColor);

        var copy = new Button { Text = "複製指令", Location = new Point(15, 155), Size = new Size(96, 30) };
        copy.Click += (sender, e) =>
        {
            try { Clipboard.SetText(install); copy.Text = "已複製"; }
            catch (System.Runtime.InteropServices.ExternalException) { command.Focus(); command.SelectAll(); }
        };
        var guide = new Button { Text = "官方安裝步驟", Location = new Point(119, 155), Size = new Size(128, 30) };
        guide.Click += (sender, e) =>
        {
            try { Process.Start(QuotaSource.Claude.InstallUrl); }
            catch (Exception) { MessageBox.Show(dialog, "無法開啟預設瀏覽器。可以複製上方指令繼續安裝。", dialog.Text); }
        };
        var close = new Button { Text = "關閉", Location = new Point(446, 155), Size = new Size(87, 30), DialogResult = DialogResult.Cancel };
        dialog.Controls.AddRange(new Control[] { copy, guide, close });
        dialog.CancelButton = close;
        return dialog;
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

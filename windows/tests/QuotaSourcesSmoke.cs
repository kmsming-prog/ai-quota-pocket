using System;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

internal static class QuotaSourcesSmoke
{
    [STAThread]
    private static int Main(string[] args)
    {
        string work = System.IO.Directory.GetCurrentDirectory();
        if (args.Length == 3 && args[0] == "--live")
        {
            try
            {
                var real = (args[1] == "codex" ? QuotaSource.Codex : QuotaSource.Claude)
                    .ReadFrom(args[2], work).GetAwaiter().GetResult();
                Console.WriteLine("Live query returned " + real.windows.Length + " quota window(s).");
                return real.windows.Length > 0 ? 0 : 3;
            }
            catch (QuotaReadException error) { Console.WriteLine(error.Message); return 3; }
            catch (Exception error) { Console.WriteLine(error.GetType().Name); return 4; }
        }

        string fake = args[0];
        var codex = QuotaSource.Codex.ReadFrom(fake, work).GetAwaiter().GetResult();
        Check(codex, "chatgpt", 74.5m, 0, 1795240800000);
        var claude = QuotaSource.Claude.ReadFrom(fake, work).GetAwaiter().GetResult();
        Check(claude, "claude", 82, 61, 1795240800000);

        var partial = QuotaProtocol.Codex(QuotaProtocol.Decode(
            "{\"rateLimits\":{\"primary\":{\"usedPercent\":12,\"windowDurationMins\":10080}}}"));
        if (partial.windows.Single().id != "weekly" || partial.windows.Single().remainingPct != 88)
            throw new Exception("Weekly-only account was misclassified.");
        try
        {
            QuotaProtocol.Codex(QuotaProtocol.Decode("{\"rateLimits\":{\"limitId\":\"another_model\",\"primary\":{\"usedPercent\":15,\"windowDurationMins\":300}}}"));
            throw new Exception("Unrelated model limit was accepted.");
        }
        catch (QuotaReadException) { }

        var fallback = QuotaProtocol.Claude("{\"type\":\"result\",\"is_error\":false,\"result\":\"Current week (all models): 23% used · resets Fri 4 PM\"}");
        if (fallback.windows.Single().remainingPct != 77 || fallback.windows.Single().resetAt.HasValue)
            throw new Exception("Printed Claude fallback invented a precise reset time.");
        try
        {
            QuotaProtocol.Claude("{\"type\":\"result\",\"is_error\":true,\"result\":\"Current session: 42% used\"}");
            throw new Exception("An error response was treated as valid data.");
        }
        catch (QuotaReadException) { }

        Environment.SetEnvironmentVariable("QUOTA_TEST_SIGNED_OUT", "1");
        foreach (var source in new[] { QuotaSource.Codex, QuotaSource.Claude })
        {
            try
            {
                source.ReadFrom(fake, work).GetAwaiter().GetResult();
                throw new Exception("Signed-out CLI was treated as authenticated.");
            }
            catch (QuotaReadException error) { if (error.Message != "請登入") throw; }
        }
        Environment.SetEnvironmentVariable("QUOTA_TEST_SIGNED_OUT", null);

        using (var stalled = new QuotaCli(fake, work, "stall"))
        {
            try
            {
                stalled.Complete(150).GetAwaiter().GetResult();
                throw new Exception("Stalled CLI did not time out.");
            }
            catch (QuotaReadException error) { if (error.Message != "讀取逾時") throw; }
        }

        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        CheckWindowPosition();
        using (var install = QuotaPocket.CreateClaudeInstallDialog())
        {
            var command = install.Controls.OfType<TextBox>().Single();
            if (!command.ReadOnly || command.Text != "winget install --id Anthropic.ClaudeCode --exact --source winget" ||
                !install.Controls.OfType<Label>().Any(label => label.Text.Contains("Claude 桌面版")) ||
                !install.Controls.OfType<Label>().Any(label => label.Text.Contains("一般權限")) ||
                !QuotaSource.Claude.InstallUrl.EndsWith("#install-on-native-windows", StringComparison.Ordinal))
                throw new Exception("Claude installation guide did not identify the native CLI.");
            if (args.Length > 2)
            {
                install.Show();
                Application.DoEvents();
                using (var bitmap = new Bitmap(install.Width, install.Height))
                {
                    install.DrawToBitmap(bitmap, new Rectangle(0, 0, bitmap.Width, bitmap.Height));
                    bitmap.Save(args[2], ImageFormat.Png);
                }
                install.Close();
            }
        }
        using (var widget = new QuotaPocket(true))
        {
            widget.Show();
            Application.DoEvents();
            var labels = widget.Controls.Cast<Control>().SelectMany(control => control.Controls.Cast<Control>().Concat(new[] { control }))
                .Select(control => control.Text).ToArray();
            if (!widget.TopMost || !labels.Contains("73%") || !labels.Contains("82%")) throw new Exception("Unexpected widget display.");
            if (args.Length > 1)
                using (var bitmap = new Bitmap(widget.Width, widget.Height))
                {
                    widget.DrawToBitmap(bitmap, new Rectangle(0, 0, bitmap.Width, bitmap.Height));
                    bitmap.Save(args[1], ImageFormat.Png);
                }
            widget.Close();
        }
        Console.WriteLine("Codex RPC, Claude stream JSON, missing plan windows, authentication states, timeout, and widget rendering passed.");
        return 0;
    }

    private static void CheckWindowPosition()
    {
        var secondMonitor = new Rectangle(-1280, 0, 1280, 760);
        var windowSize = new Size(392, 159);
        if (QuotaPocket.FitPosition(new Point(-700, 110), windowSize, secondMonitor) != new Point(-700, 110) ||
            QuotaPocket.FitPosition(new Point(-20, 750), windowSize, secondMonitor) != new Point(-392, 601))
            throw new Exception("Position clamping failed for a display left of the primary monitor.");

        string path = Path.Combine(Path.GetTempPath(), "quota-pocket-position-" + Guid.NewGuid().ToString("N") + ".txt");
        try
        {
            Rectangle area = Screen.PrimaryScreen.WorkingArea;
            Point restored, moved;
            using (var widget = new QuotaPocket(true, path))
            {
                restored = QuotaPocket.FitPosition(new Point(area.Left + 30, area.Top + 20), widget.Size, area);
                File.WriteAllText(path, restored.X + "," + restored.Y);
                widget.Show();
                Application.DoEvents();
                if (widget.Location != restored) throw new Exception("The saved position was not restored.");
                moved = QuotaPocket.FitPosition(new Point(area.Left + 70, area.Top + 45), widget.Size, area);
                var title = widget.Controls.OfType<Label>().Single(label => label.Text == widget.Text);
                SendMouseEvent(title, "OnMouseDown", new Point(15, 8));
                SendMouseEvent(title, "OnMouseMove", new Point(15 + moved.X - restored.X, 8 + moved.Y - restored.Y));
                SendMouseEvent(title, "OnMouseUp", new Point(15, 8));
                if (widget.Location != moved || File.ReadAllText(path) != moved.X + "," + moved.Y)
                    throw new Exception("Title drag did not save the new position.");
                widget.ContextMenuStrip.Items[0].PerformClick();
                widget.ContextMenuStrip.Items[0].PerformClick();
                var timer = Stopwatch.StartNew();
                while (timer.ElapsedMilliseconds < 3300)
                {
                    Application.DoEvents();
                    Thread.Sleep(25);
                }
                if (widget.Location != moved) throw new Exception("A refresh or tray toggle moved the widget.");
                widget.Close();
            }
            using (var reopened = new QuotaPocket(true, path))
            {
                reopened.Show();
                Application.DoEvents();
                if (reopened.Location != moved) throw new Exception("The new location was not restored on restart.");
                reopened.Close();
            }
        }
        finally { File.Delete(path); }
    }

    private static void SendMouseEvent(Control widget, string method, Point location)
    {
        var handler = typeof(Control).GetMethod(method, BindingFlags.Instance | BindingFlags.NonPublic);
        handler.Invoke(widget, new object[] { new MouseEventArgs(MouseButtons.Left, 1, location.X, location.Y, 0) });
    }

    private static void Check(QuotaSnapshot value, string provider, decimal session, decimal weekly, long sessionReset)
    {
        var windows = value.windows.ToDictionary(x => x.id);
        if (value.provider != provider || windows.Count != 2 || windows["session"].remainingPct != session ||
            windows["weekly"].remainingPct != weekly || windows["session"].resetAt != sessionReset)
            throw new Exception("Unexpected " + provider + " quotas.");
    }
}

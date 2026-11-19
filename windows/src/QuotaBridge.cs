using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;

internal static class QuotaBridge
{
    private static int Main(string[] args)
    {
        string chromeOrigin = "chrome-extension://" + QuotaData.ExtensionId + "/";
        if (args.Length == 0 || args[0] != chromeOrigin) return 1;
        using (var input = new BinaryReader(Console.OpenStandardInput()))
        using (var output = new BinaryWriter(Console.OpenStandardOutput()))
        {
            bool saved;
            try
            {
                int length = input.ReadInt32();
                if (length < 1 || length > 8192) throw new FormatException();
                byte[] message = input.ReadBytes(length);
                if (message.Length != length) throw new EndOfStreamException();
                var envelope = QuotaData.Parse(Encoding.UTF8.GetString(message));
                Save(envelope);
                saved = true;
            }
            catch (Exception)
            {
                saved = false;
            }
            byte[] response = Encoding.ASCII.GetBytes(saved ? "{\"ok\":true}" : "{\"ok\":false}");
            output.Write(response.Length);
            output.Write(response);
            output.Flush();
            return saved ? 0 : 1;
        }
    }

    private static void Save(QuotaEnvelope envelope)
    {
        using (var mutex = new Mutex(false, @"Local\KangQuotaPocketCache"))
        {
            try
            {
                if (!mutex.WaitOne(5000)) throw new TimeoutException();
            }
            catch (AbandonedMutexException)
            {
                // The last writer stopped before releasing ownership.
            }
            try
            {
                var snapshots = envelope.snapshots;
                if (File.Exists(QuotaData.CacheFile))
                {
                    try
                    {
                        var old = QuotaData.Parse(File.ReadAllText(QuotaData.CacheFile));
                        snapshots = snapshots.Concat(old.snapshots).GroupBy(item => item.provider)
                            .Select(group => group.OrderByDescending(item => item.observedAt).First()).ToArray();
                    }
                    catch (FormatException) { }
                    catch (ArgumentException) { }
                }
                string temporaryFile = QuotaData.CacheFile + ".new";
                File.WriteAllText(temporaryFile, QuotaData.Json.Serialize(new QuotaEnvelope { snapshots = snapshots }));
                if (File.Exists(QuotaData.CacheFile)) File.Replace(temporaryFile, QuotaData.CacheFile, null, true);
                else File.Move(temporaryFile, QuotaData.CacheFile);
            }
            finally
            {
                mutex.ReleaseMutex();
            }
        }
    }
}

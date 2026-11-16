using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using Serilog;

namespace Utochpad.CredentialProvider;

/// <summary>
/// Runs inside LogonUI; talks to the service through a machine-wide named pipe.
/// </summary>
internal static class CpIpcClient
{
    private const string PipeName = "utochpad-cp";
    private const int MaxLineBytes = 1024 * 1024;
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public static CpUnlockResponse RequestUnlock(string username, string domain, int timeoutSeconds)
    {
        var request = new CpUnlockRequest
        {
            Username = username,
            Domain = domain,
            TimeoutSeconds = timeoutSeconds,
        };

        try
        {
            using var pipe = new NamedPipeClientStream(
                ".",
                PipeName,
                PipeDirection.InOut,
                PipeOptions.Asynchronous);

            // The service creates up to four instances. Give it a little more
            // room than a single connect attempt so a busy pipe does not turn
            // into a misleading "service is not running" message.
            pipe.Connect(5000);

            using var writer = new StreamWriter(pipe, new UTF8Encoding(false), 1024, leaveOpen: true)
            {
                NewLine = "\n",
            };
            writer.WriteLine(JsonSerializer.Serialize(request, JsonOptions));
            writer.Flush();

            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds + 5));
            using var reader = new StreamReader(pipe, Encoding.UTF8, false, 1024, leaveOpen: true);
            var line = reader.ReadLineAsync(timeout.Token).GetAwaiter().GetResult();
            if (line is null)
            {
                return new CpUnlockResponse
                {
                    Id = request.Id,
                    Ok = false,
                    Error = "Utochpad 服务没有响应",
                };
            }

            if (Encoding.UTF8.GetByteCount(line) > MaxLineBytes)
                throw new InvalidOperationException("Pipe message is too large.");

            return JsonSerializer.Deserialize<CpUnlockResponse>(line, JsonOptions) ?? new CpUnlockResponse
            {
                Id = request.Id,
                Ok = false,
                Error = "无法解析 Utochpad 服务的响应",
            };
        }
        catch (Exception ex) when (ex is TimeoutException or OperationCanceledException)
        {
            Log.Warning("Timed out waiting for the Utochpad service: {Error}", ex.Message);
            return new CpUnlockResponse
            {
                Id = request.Id,
                Ok = false,
                Error = "等待手机验证超时",
            };
        }
        catch (Exception ex)
        {
            Log.Warning(
                ex,
                "Failed to contact the Utochpad service " +
                "(pipe={Pipe}, user={User}, session={Session})",
                PipeName,
                Environment.UserName,
                System.Diagnostics.Process.GetCurrentProcess().SessionId);
            return new CpUnlockResponse
            {
                Id = request.Id,
                Ok = false,
                Error = "Utochpad 服务未运行。请先启动服务。",
            };
        }
    }

    private sealed class CpUnlockRequest
    {
        public string Id { get; set; } = Guid.NewGuid().ToString("N");
        public string Method { get; set; } = "unlock";
        public string Username { get; set; } = "";
        public string Domain { get; set; } = "";
        public int TimeoutSeconds { get; set; } = 30;
    }

    internal sealed class CpUnlockResponse
    {
        public string Id { get; set; } = "";
        public bool Ok { get; set; }
        public string? PasswordB64 { get; set; }
        public string? Error { get; set; }
    }
}

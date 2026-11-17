using System.IO.Pipes;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Utochpad.Panel.Services;

public sealed class UiPipeClient
{
    private const string PipeName = "utochpad-ui";
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNameCaseInsensitive = true,
    };

    private readonly SemaphoreSlim _refreshLock = new(1, 1);
    private bool _errorDismissed;

    public UiStatus? Status { get; private set; }
    public string? Error { get; private set; }
    public int PairingEpoch { get; private set; }

    public event EventHandler? Updated;

    public async Task RefreshAsync()
    {
        if (!await _refreshLock.WaitAsync(0))
            return;

        try
        {
            Status = await SendAsync<UiStatus>("status", timeout: TimeSpan.FromSeconds(10));
            Error = null;
            _errorDismissed = false;
        }
        catch (Exception ex) when (
            ex is IOException or InvalidOperationException or JsonException or TimeoutException)
        {
            Status = null;
            if (!_errorDismissed)
                Error = ex.Message;
        }
        finally
        {
            _refreshLock.Release();
            Updated?.Invoke(this, EventArgs.Empty);
        }
    }

    public void DismissError()
    {
        _errorDismissed = true;
        Error = null;
        Updated?.Invoke(this, EventArgs.Empty);
    }

    public Task<PairInfo> BeginPairingAsync() =>
        SendAsync<PairInfo>("pair.begin");

    public async Task CancelPairingAsync()
    {
        await SendAsync<object>("pair.cancel");
        await RefreshAsync();
    }

    public async Task UnpairAsync()
    {
        await SendAsync<object>("pair.unpair");
        PairingEpoch++;
        await RefreshAsync();
    }

    public async Task SetPasswordAsync(string password)
    {
        await SendAsync<object>("password.set", new { password }, TimeSpan.FromSeconds(110));
        await RefreshAsync();
    }

    public async Task RestartViiperAsync()
    {
        await SendAsync<object>("viiper.restart", timeout: TimeSpan.FromSeconds(35));
        await RefreshAsync();
    }

    private async Task<T> SendAsync<T>(
        string method,
        object? parameters = null,
        TimeSpan? timeout = null)
    {
        using var cancellation = new CancellationTokenSource(timeout ?? TimeSpan.FromSeconds(10));
        var id = Guid.NewGuid().ToString("N");

        try
        {
            await using var pipe = new NamedPipeClientStream(
                ".",
                PipeName,
                PipeDirection.InOut,
                PipeOptions.Asynchronous);
            await pipe.ConnectAsync(2000, cancellation.Token);

            await using var writer = new StreamWriter(pipe, new UTF8Encoding(false), leaveOpen: true);
            using var reader = new StreamReader(pipe, Encoding.UTF8, leaveOpen: true);

            var request = JsonSerializer.Serialize(new PipeRequest(id, method, parameters), JsonOptions);
            await writer.WriteLineAsync(request.AsMemory(), cancellation.Token);
            await writer.FlushAsync(cancellation.Token);

            var line = await reader.ReadLineAsync(cancellation.Token);
            if (string.IsNullOrEmpty(line))
                throw new IOException("Utochpad 服务没有响应");

            var response = JsonSerializer.Deserialize<PipeResponse>(line, JsonOptions)
                ?? throw new JsonException("无法解析服务响应");
            if (!string.Equals(response.Id, id, StringComparison.Ordinal))
                throw new JsonException("服务响应 ID 不匹配");
            if (!response.Ok)
                throw new InvalidOperationException(response.Error ?? "Utochpad 服务返回未知错误");
            if (typeof(T) == typeof(object))
                return (T)(object)new object();
            if (response.Result is not { ValueKind: not JsonValueKind.Null } result)
                throw new JsonException("服务响应缺少结果");

            return result.Deserialize<T>(JsonOptions)
                ?? throw new JsonException("无法读取服务响应数据");
        }
        catch (OperationCanceledException) when (cancellation.IsCancellationRequested)
        {
            throw new TimeoutException("请求超时");
        }
        catch (TimeoutException)
        {
            throw new IOException("Utochpad 服务未运行或无法连接");
        }
        catch (UnauthorizedAccessException ex)
        {
            throw new IOException("没有权限连接 Utochpad 服务", ex);
        }
    }

    private sealed record PipeRequest(
        [property: JsonPropertyName("id")] string Id,
        [property: JsonPropertyName("method")] string Method,
        [property: JsonPropertyName("params")] object? Params);

    private sealed class PipeResponse
    {
        public string Id { get; set; } = "";
        public bool Ok { get; set; }
        public JsonElement? Result { get; set; }
        public string? Error { get; set; }
    }

    public sealed class UiStatus
    {
        public string Version { get; set; } = "";
        public bool Paired { get; set; }
        public int PairVersion { get; set; }
        public bool PhoneConnected { get; set; }
        public bool PhoneHasPassword { get; set; }
        public bool ViiperRunning { get; set; }
        public string? PendingUnlockUser { get; set; }
        public int Port { get; set; }
        public string[] Hosts { get; set; } = [];
        public string DataDirectory { get; set; } = "";
        public string LogDirectory { get; set; } = "";
    }

    public sealed class PairInfo
    {
        public string QrJson { get; set; } = "";
        public string ManualCode { get; set; } = "";
        public DateTimeOffset ExpiresAt { get; set; }
        public int PairVersion { get; set; }
    }
}

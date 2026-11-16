using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Utochpad.Service.Crypto;
using Utochpad.Service.Protocol;

namespace Utochpad.Service;

/// <summary>An authenticated, encrypted phone connection.</summary>
public sealed class PhoneSession
{
    private readonly WebSocket _socket;
    private readonly ViiperManager _viiper;
    private readonly AeadSession _sendCrypto;
    private readonly AeadSession _receiveCrypto;
    private readonly ILogger _logger;

    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private readonly object _pendingGate = new();
    private readonly byte[] _receiveChunk = new byte[16 * 1024];

    private TaskCompletionSource<UnlockResult>? _pendingUnlock;
    private TaskCompletionSource<(bool Ok, string? Error)>? _pendingSetPassword;

    private volatile bool _connected;
    private volatile bool _passwordSet;
    private volatile string? _activeMode;
    private volatile string? _pendingUnlockUser;

    public PhoneSession(
        WebSocket socket,
        ViiperManager viiper,
        AeadSession sendCrypto,
        AeadSession receiveCrypto,
        bool passwordSet,
        ILogger logger)
    {
        _socket = socket;
        _viiper = viiper;
        _sendCrypto = sendCrypto;
        _receiveCrypto = receiveCrypto;
        _passwordSet = passwordSet;
        _logger = logger;
    }

    public bool IsConnected => _connected && _socket.State == WebSocketState.Open;

    public bool PasswordSet => _passwordSet;

    public string? ActiveMode => _activeMode;

    public string? PendingUnlockUser => _pendingUnlockUser;

    public async Task RunAsync(CancellationToken cancellationToken)
    {
        _connected = true;

        try
        {
            while (!cancellationToken.IsCancellationRequested && _socket.State == WebSocketState.Open)
            {
                var (type, data, count) = await ReceiveAsync(cancellationToken).ConfigureAwait(false);
                if (type == WebSocketMessageType.Close)
                    break;
                if (type != WebSocketMessageType.Binary)
                    continue;

                byte[] plaintext;
                try
                {
                    plaintext = _receiveCrypto.Open(data.AsSpan(0, count));
                }
                catch (CryptographicException ex)
                {
                    _logger.LogWarning(ex, "Rejected a frame with invalid authentication");
                    break;
                }

                if (plaintext.Length == 0)
                    continue;

                switch (plaintext[0])
                {
                    case PhonePayloadType.Control:
                        await HandleControlAsync(plaintext.AsMemory(1), cancellationToken).ConfigureAwait(false);
                        break;

                    // The touchpad and radial knob share a page and are attached
                    // to the same virtual bus, so each payload type is routed to
                    // its own device whenever that device is present.
                    case PhonePayloadType.Ptp:
                    case PhonePayloadType.Radial:
                        await ForwardInputAsync(plaintext[0], plaintext.AsMemory(1), cancellationToken)
                            .ConfigureAwait(false);
                        break;
                }
            }
        }
        catch (OperationCanceledException)
        {
            // shutdown
        }
        catch (WebSocketException ex)
        {
            _logger.LogDebug(ex, "Phone socket closed");
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Phone session failed");
        }
        finally
        {
            _connected = false;
            _activeMode = null;
            FailPending("手机连接已断开");
            // Device teardown is owned by PhoneHub: if this session was already
            // replaced by a newer connection, leaving here would tear down the
            // devices the new session just attached.
        }
    }

    public async Task<(bool Ok, string? Password, string? Error)> RequestUnlockAsync(
        string username,
        string domain,
        int timeoutSeconds,
        CancellationToken cancellationToken)
    {
        var id = Guid.NewGuid().ToString("N");
        var tcs = new TaskCompletionSource<UnlockResult>(TaskCreationOptions.RunContinuationsAsynchronously);

        lock (_pendingGate)
        {
            if (_pendingUnlock is not null)
                return (false, null, "已有手机验证请求正在进行");

            _pendingUnlock = tcs;
            _pendingUnlockUser = username;
            _pendingUnlockId = id;
        }

        try
        {
            var sent = await SendControlAsync(new WsControlMessage
            {
                T = WsMessageTypes.UnlockRequest,
                Id = id,
                Username = username,
                Domain = domain,
                TimeoutSeconds = timeoutSeconds,
            }, cancellationToken).ConfigureAwait(false);

            if (!sent)
                return (false, null, "无法向手机发送验证请求");

            var delay = Task.Delay(TimeSpan.FromSeconds(timeoutSeconds), cancellationToken);
            var winner = await Task.WhenAny(tcs.Task, delay).ConfigureAwait(false);

            // WhenAny also returns the delay task when the token was canceled
            // or the timer fired. A response that completed at the same moment
            // still wins, and a cancellation is not a phone-side timeout.
            if (winner == delay && !tcs.Task.IsCompleted)
            {
                if (cancellationToken.IsCancellationRequested)
                    return (false, null, "请求已取消");

                try
                {
                    await SendControlAsync(new WsControlMessage
                    {
                        T = WsMessageTypes.CancelUnlock,
                        Id = id,
                    }, CancellationToken.None).ConfigureAwait(false);
                }
                catch
                {
                    // best effort
                }

                return (false, null, "手机验证超时");
            }

            var result = await tcs.Task.ConfigureAwait(false);
            return (result.Ok, result.Password, result.Error);
        }
        catch (OperationCanceledException)
        {
            return (false, null, "请求已取消");
        }
        finally
        {
            lock (_pendingGate)
            {
                if (ReferenceEquals(_pendingUnlock, tcs))
                {
                    _pendingUnlock = null;
                    _pendingUnlockUser = null;
                    _pendingUnlockId = null;
                }
            }
        }
    }

    public async Task<(bool Ok, string? Error)> SetPasswordAsync(string password, CancellationToken cancellationToken)
    {
        var tcs = new TaskCompletionSource<(bool Ok, string? Error)>(TaskCreationOptions.RunContinuationsAsynchronously);

        lock (_pendingGate)
        {
            if (_pendingSetPassword is not null)
                return (false, "上一次密码推送尚未完成");

            _pendingSetPassword = tcs;
        }

        try
        {
            var sent = await SendControlAsync(new WsControlMessage
            {
                T = WsMessageTypes.SetPassword,
                PasswordB64 = Convert.ToBase64String(Encoding.UTF8.GetBytes(password)),
            }, cancellationToken).ConfigureAwait(false);

            if (!sent)
                return (false, "无法向手机发送密码");

            var delay = Task.Delay(TimeSpan.FromSeconds(90), cancellationToken);
            var winner = await Task.WhenAny(tcs.Task, delay).ConfigureAwait(false);
            if (winner == delay && !tcs.Task.IsCompleted)
            {
                return cancellationToken.IsCancellationRequested
                    ? (false, "请求已取消")
                    : (false, "手机端确认超时");
            }

            return await tcs.Task.ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return (false, "请求已取消");
        }
        finally
        {
            lock (_pendingGate)
            {
                if (ReferenceEquals(_pendingSetPassword, tcs))
                    _pendingSetPassword = null;
            }
        }
    }

    private async Task ForwardInputAsync(
        byte payloadType,
        ReadOnlyMemory<byte> frame,
        CancellationToken cancellationToken)
    {
        if (_viiper.CanWrite(payloadType))
        {
            await _viiper.WriteFrameAsync(payloadType, frame).ConfigureAwait(false);
            return;
        }

        // The devices can disappear under a live phone session (the panel's
        // restart action, a VIIPER crash). Tell the phone once so it can leave
        // its "ready" state and re-enter the mode instead of streaming into a
        // void.
        if (_activeMode is null)
            return;

        _activeMode = null;
        await SendControlAsync(new WsControlMessage
        {
            T = WsMessageTypes.ModeError,
            Mode = UtochpadConstants.ModeInput,
            Message = "输入组件已重启，请重试。",
        }, cancellationToken).ConfigureAwait(false);
    }

    private async Task HandleControlAsync(ReadOnlyMemory<byte> payload, CancellationToken cancellationToken)
    {
        WsControlMessage? message;
        try
        {
            message = JsonSerializer.Deserialize<WsControlMessage>(payload.Span, JsonDefaults.Options);
        }
        catch
        {
            return;
        }

        if (message is null)
            return;

        switch (message.T)
        {
            case WsMessageTypes.UnlockResponse:
                CompleteUnlock(message);
                break;

            case WsMessageTypes.SetPasswordResult:
                if (message.Ok == true)
                    _passwordSet = true;

                TaskCompletionSource<(bool Ok, string? Error)>? setPassword;
                lock (_pendingGate) setPassword = _pendingSetPassword;
                setPassword?.TrySetResult((message.Ok == true, message.Error));
                break;

            case WsMessageTypes.EnterMode:
                await EnterModeAsync(message.Mode, cancellationToken).ConfigureAwait(false);
                break;
        }
    }

    private void CompleteUnlock(WsControlMessage message)
    {
        TaskCompletionSource<UnlockResult>? pending;
        string? pendingId;

        lock (_pendingGate)
        {
            pending = _pendingUnlock;
            pendingId = _pendingUnlockId;
        }

        if (pending is null)
            return;

        if (!string.IsNullOrEmpty(message.Id) && message.Id != pendingId)
            return;

        string? password = null;
        if (message.Ok == true && !string.IsNullOrEmpty(message.PasswordB64))
        {
            try
            {
                password = Encoding.UTF8.GetString(Convert.FromBase64String(message.PasswordB64));
            }
            catch
            {
                password = null;
            }
        }

        pending.TrySetResult(new UnlockResult(
            message.Ok == true && password is not null,
            password,
            message.Error));
    }

    private async Task EnterModeAsync(string? mode, CancellationToken cancellationToken)
    {
        if (mode != UtochpadConstants.ModeInput)
        {
            await SendControlAsync(new WsControlMessage
            {
                T = WsMessageTypes.ModeError,
                Mode = mode,
                Message = "无法启动输入设备，请重试。",
            }, cancellationToken).ConfigureAwait(false);
            return;
        }

        var (ok, error) = await _viiper.EnterModeAsync(mode, cancellationToken).ConfigureAwait(false);
        _activeMode = ok ? mode : null;

        await SendControlAsync(ok
            ? new WsControlMessage { T = WsMessageTypes.ModeReady, Mode = mode }
            : new WsControlMessage { T = WsMessageTypes.ModeError, Mode = mode, Message = error },
            cancellationToken).ConfigureAwait(false);
    }

    private async Task<bool> SendControlAsync(WsControlMessage message, CancellationToken cancellationToken)
    {
        var json = JsonSerializer.SerializeToUtf8Bytes(message, JsonDefaults.Options);
        return await SendFrameAsync(PhonePayloadType.Control, json, cancellationToken).ConfigureAwait(false);
    }

    private async Task<bool> SendFrameAsync(byte payloadType, ReadOnlyMemory<byte> body, CancellationToken cancellationToken)
    {
        if (_socket.State != WebSocketState.Open)
            return false;

        var plaintext = new byte[1 + body.Length];
        plaintext[0] = payloadType;
        body.CopyTo(plaintext.AsMemory(1));

        byte[] frame;
        try
        {
            frame = _sendCrypto.Seal(plaintext);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to encrypt outgoing frame");
            return false;
        }

        await _sendLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (_socket.State != WebSocketState.Open)
                return false;

            await _socket.SendAsync(frame, WebSocketMessageType.Binary, true, cancellationToken).ConfigureAwait(false);
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "Failed to send frame to phone");
            return false;
        }
        finally
        {
            _sendLock.Release();
        }
    }

    private async Task<(WebSocketMessageType Type, byte[] Data, int Count)> ReceiveAsync(
        CancellationToken cancellationToken)
    {
        // Input frames arrive dozens of times per second. Reusing the read
        // buffer keeps the hot path allocation-free for the common case of one
        // unfragmented WebSocket message; the caller consumes the bytes before
        // the next receive starts.
        var first = true;
        MemoryStream? fragment = null;

        while (true)
        {
            var result = await _socket.ReceiveAsync(_receiveChunk, cancellationToken).ConfigureAwait(false);
            if (result.MessageType == WebSocketMessageType.Close)
                return (WebSocketMessageType.Close, [], 0);

            if (result.EndOfMessage)
            {
                if (first)
                    return (result.MessageType, _receiveChunk, result.Count);

                fragment!.Write(_receiveChunk, 0, result.Count);
                var data = fragment.ToArray();
                return (result.MessageType, data, data.Length);
            }

            first = false;
            fragment ??= new MemoryStream();
            fragment.Write(_receiveChunk, 0, result.Count);
            if (fragment.Length > 2 * 1024 * 1024)
                throw new WebSocketException("Frame is too large.");
        }
    }

    private void FailPending(string error)
    {
        TaskCompletionSource<UnlockResult>? unlock;
        TaskCompletionSource<(bool Ok, string? Error)>? setPassword;

        lock (_pendingGate)
        {
            unlock = _pendingUnlock;
            setPassword = _pendingSetPassword;
            _pendingUnlockUser = null;
        }

        unlock?.TrySetResult(new UnlockResult(false, null, error));
        setPassword?.TrySetResult((false, error));
    }

    public async Task CloseAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            if (_socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
            {
                await _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "replaced", cancellationToken)
                    .ConfigureAwait(false);
            }
        }
        catch
        {
            // best effort
        }

        FailPending("手机连接已断开");
    }

    private string? _pendingUnlockId;

    private readonly record struct UnlockResult(bool Ok, string? Password, string? Error);
}

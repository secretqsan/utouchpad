using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Utochpad.Service.Crypto;
using Utochpad.Service.Protocol;

namespace Utochpad.Service;

/// <summary>
/// Single entry point for the phone connection. Performs pairing and session
/// handshakes, then hands the encrypted channel to <see cref="PhoneSession"/>.
/// </summary>
public sealed class PhoneHub
{
    private readonly ServiceConfig _config;
    private readonly DataPaths _paths;
    private readonly ViiperManager _viiper;
    private readonly ILoggerFactory _loggerFactory;
    private readonly ILogger<PhoneHub> _logger;

    private readonly object _gate = new();
    private PhoneSession? _session;
    private PairToken? _pairToken;
    private int _pairVersion;

    public PhoneHub(
        ServiceConfig config,
        DataPaths paths,
        ViiperManager viiper,
        ILoggerFactory loggerFactory,
        ILogger<PhoneHub> logger)
    {
        _config = config;
        _paths = paths;
        _viiper = viiper;
        _loggerFactory = loggerFactory;
        _logger = logger;
    }

    public bool Paired => _config.IsPaired;

    public int PairVersion
    {
        get { lock (_gate) return _pairVersion; }
    }

    public bool PhoneConnected
    {
        get { lock (_gate) return _session?.IsConnected == true; }
    }

    public bool PhoneHasPassword
    {
        get { lock (_gate) return _session?.PasswordSet == true; }
    }

    public string? ActiveMode
    {
        get { lock (_gate) return _session?.ActiveMode; }
    }

    public string? PendingUnlockUser
    {
        get { lock (_gate) return _session?.PendingUnlockUser; }
    }

    public UiStatus GetStatus() => new()
    {
        // Match the panel's three-part version string (1.0.0, not 1.0.0.0).
        Version = typeof(PhoneHub).Assembly.GetName().Version?.ToString(3) ?? "1.0.0",
        Paired = Paired,
        PairVersion = PairVersion,
        PhoneName = _config.PairedDeviceName,
        PhoneConnected = PhoneConnected,
        PhoneHasPassword = PhoneHasPassword,
        ViiperRunning = _viiper.IsProcessRunning,
        ActiveMode = ActiveMode,
        PendingUnlockUser = PendingUnlockUser,
        Port = _config.Port,
        Hosts = GetLanAddresses(),
        DataDirectory = _paths.DataDirectory,
        LogDirectory = _paths.LogDirectory,
    };

    public PairInfo BeginPairing()
    {
        var tokenBytes = RandomNumberGenerator.GetBytes(18);
        var token = Base64Url(tokenBytes);
        var expires = DateTimeOffset.UtcNow.AddMinutes(5);
        int pairVersion;

        lock (_gate)
        {
            _pairToken = new PairToken(token, expires);
            pairVersion = _pairVersion;
        }

        var payload = new PairQrPayload
        {
            V = UtochpadConstants.ProtocolVersion,
            Name = Environment.MachineName,
            Hosts = GetLanAddresses(),
            Port = _config.Port,
            Pub = Convert.ToBase64String(_config.GetPublicKey()),
            Token = token,
        };

        var qrJson = JsonSerializer.Serialize(payload, JsonDefaults.Options);

        return new PairInfo
        {
            QrJson = qrJson,
            ManualCode = Base64Url(Encoding.UTF8.GetBytes(qrJson)),
            ExpiresAt = expires,
            PairVersion = pairVersion,
        };
    }

    public void CancelPairing()
    {
        lock (_gate) _pairToken = null;
    }

    public async Task UnpairAsync(CancellationToken cancellationToken = default)
    {
        PhoneSession? session;
        lock (_gate)
        {
            _pairToken = null;
            session = _session;
            _session = null;
        }

        _config.ClearPairing(_paths.DataDirectory);

        if (session is not null)
        {
            using var closeTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            closeTimeout.CancelAfter(TimeSpan.FromSeconds(2));
            await session.CloseAsync(closeTimeout.Token).ConfigureAwait(false);
        }

        // Await teardown: a new pairing must not race an old leave that would
        // detach the fresh devices.
        await _viiper.LeaveAsync().ConfigureAwait(false);
        _logger.LogInformation("Pairing removed");
    }

    public async Task<(bool Ok, string? Password, string? Error)> RequestUnlockAsync(
        CpUnlockRequest request, CancellationToken cancellationToken)
    {
        if (!Paired)
            return (false, null, "电脑尚未与手机配对");

        PhoneSession? session;
        lock (_gate) session = _session;

        if (session is null || !session.IsConnected)
            return (false, null, "手机未连接。请打开 Utochpad App 并保持在前台。");

        try
        {
            return await session.RequestUnlockAsync(
                request.Username,
                request.Domain,
                Math.Clamp(request.TimeoutSeconds, 5, 120),
                cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Unlock request failed");
            return (false, null, "手机验证失败，请重试。");
        }
    }

    public async Task<(bool Ok, string? Error)> SetPasswordAsync(string password, CancellationToken cancellationToken)
    {
        PhoneSession? session;
        lock (_gate) session = _session;

        if (session is null || !session.IsConnected)
            return (false, "手机未连接。请打开 Utochpad App 并保持在前台。");

        try
        {
            return await session.SetPasswordAsync(password, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Password push failed");
            return (false, "发送密码失败，请重试。");
        }
    }

    public async Task HandleWebSocketAsync(WebSocket socket, CancellationToken cancellationToken)
    {
        try
        {
            var first = await ReceiveTextAsync(socket, TimeSpan.FromSeconds(10), cancellationToken).ConfigureAwait(false);
            if (first is null)
            {
                await CloseAsync(socket, "handshake timeout", cancellationToken).ConfigureAwait(false);
                return;
            }

            WsHandshakeMessage? message;
            try
            {
                message = JsonSerializer.Deserialize<WsHandshakeMessage>(first, JsonDefaults.Options);
            }
            catch
            {
                await CloseAsync(socket, "bad handshake", cancellationToken).ConfigureAwait(false);
                return;
            }

            if (message is null || message.V != UtochpadConstants.ProtocolVersion)
            {
                await RejectAsync(
                    socket,
                    "version",
                    "手机和电脑上的 Utochpad 版本不一致，请都更新到最新版本。",
                    cancellationToken).ConfigureAwait(false);
                return;
            }

            if (message.T == WsMessageTypes.Pair)
            {
                await HandlePairAsync(socket, message, cancellationToken).ConfigureAwait(false);
                return;
            }

            if (message.T != WsMessageTypes.Hello)
            {
                await CloseAsync(socket, "unknown handshake", cancellationToken).ConfigureAwait(false);
                return;
            }

            await HandleSessionAsync(socket, message, cancellationToken).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            // service shutdown
        }
        catch (WebSocketException ex)
        {
            _logger.LogDebug(ex, "WebSocket closed during handshake");
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Unexpected WebSocket error");
            try { await CloseAsync(socket, "error", cancellationToken).ConfigureAwait(false); } catch { }
        }
    }

    private async Task HandlePairAsync(
        WebSocket socket, WsHandshakeMessage message, CancellationToken cancellationToken)
    {
        var token = message.Token ?? "";
        var phonePubBase64 = message.PhonePub ?? "";
        var deviceId = message.DeviceId ?? "";
        var deviceName = message.DeviceName ?? "Android";

        PairToken? expected;
        lock (_gate) expected = _pairToken;

        if (expected is null || expected.ExpiresAt < DateTimeOffset.UtcNow ||
            !CryptoUtil.FixedTimeEquals(
                Encoding.UTF8.GetBytes(expected.Token),
                Encoding.UTF8.GetBytes(token)))
        {
            await RejectAsync(
                socket,
                "pair_token",
                "配对码无效或已过期，请在电脑上重新生成二维码",
                cancellationToken).ConfigureAwait(false);
            return;
        }

        if (deviceId.Length is < 8 or > 128 || phonePubBase64.Length == 0)
        {
            await RejectAsync(
                socket,
                "pair_request",
                "配对失败，请重新扫描二维码。",
                cancellationToken).ConfigureAwait(false);
            return;
        }

        byte[] phonePub;
        byte[] pairKey;
        try
        {
            phonePub = Convert.FromBase64String(phonePubBase64);
            var shared = CryptoUtil.DeriveSharedSecret(_config.GetPrivateKey(), phonePub);
            pairKey = CryptoUtil.DerivePairKey(shared);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Invalid phone public key");
            await RejectAsync(
                socket,
                "pair_key",
                "配对失败，请重新扫描二维码。",
                cancellationToken).ConfigureAwait(false);
            return;
        }

        // Proof of possession: the phone must know the private key matching phonePub.
        var proofText = "pair|" + token;
        var expectedProof = CryptoUtil.Hmac(pairKey, proofText);
        byte[] actualProof;
        try
        {
            actualProof = Convert.FromBase64String(message.Proof ?? "");
        }
        catch
        {
            actualProof = [];
        }

        if (!CryptoUtil.FixedTimeEquals(expectedProof, actualProof))
        {
            await RejectAsync(
                socket,
                "pair_proof",
                "配对失败，请重新扫描二维码。",
                cancellationToken).ConfigureAwait(false);
            return;
        }

        _config.SavePairedPhone(Convert.ToBase64String(phonePub), deviceId, deviceName, _paths.DataDirectory);

        PhoneSession? previousSession;
        lock (_gate)
        {
            _pairToken = null;
            _pairVersion++;
            previousSession = _session;
            _session = null;
        }

        if (previousSession is not null)
        {
            using var closeTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            await previousSession.CloseAsync(closeTimeout.Token).ConfigureAwait(false);
        }

        await SendHandshakeAsync(socket, new WsHandshakeMessage
        {
            T = WsMessageTypes.PairOk,
            V = UtochpadConstants.ProtocolVersion,
            ServicePub = Convert.ToBase64String(_config.GetPublicKey()),
            Message = Environment.MachineName,
        }, cancellationToken).ConfigureAwait(false);

        _logger.LogInformation("Paired with {DeviceName} ({DeviceId})", deviceName, deviceId);
        await CloseAsync(socket, "paired", cancellationToken).ConfigureAwait(false);
    }

    private async Task HandleSessionAsync(
        WebSocket socket, WsHandshakeMessage hello, CancellationToken cancellationToken)
    {
        if (!_config.IsPaired || hello.DeviceId != _config.PairedDeviceId)
        {
            await RejectAsync(
                socket,
                "not_paired",
                "电脑没有这台手机的配对记录",
                cancellationToken).ConfigureAwait(false);
            return;
        }

        byte[] nonceP;
        try
        {
            nonceP = Convert.FromBase64String(hello.NonceP ?? "");
        }
        catch
        {
            nonceP = [];
        }

        if (nonceP.Length != 16)
        {
            await CloseAsync(socket, "bad nonce", cancellationToken).ConfigureAwait(false);
            return;
        }

        byte[] pairKey;
        try
        {
            var pairedPub = Convert.FromBase64String(_config.PairedPhonePublicKey!);
            var shared = CryptoUtil.DeriveSharedSecret(_config.GetPrivateKey(), pairedPub);
            pairKey = CryptoUtil.DerivePairKey(shared);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Cannot derive pairing key");
            await CloseAsync(socket, "pair key", cancellationToken).ConfigureAwait(false);
            return;
        }

        var nonceS = RandomNumberGenerator.GetBytes(16);
        var serverProof = CryptoUtil.Hmac(pairKey, CryptoUtil.ProofText("server", nonceP, nonceS));

        await SendHandshakeAsync(socket, new WsHandshakeMessage
        {
            T = WsMessageTypes.Challenge,
            V = UtochpadConstants.ProtocolVersion,
            NonceS = Convert.ToBase64String(nonceS),
            Proof = Convert.ToBase64String(serverProof),
        }, cancellationToken).ConfigureAwait(false);

        var authText = await ReceiveTextAsync(socket, TimeSpan.FromSeconds(10), cancellationToken).ConfigureAwait(false);
        if (authText is null)
        {
            await CloseAsync(socket, "auth timeout", cancellationToken).ConfigureAwait(false);
            return;
        }

        WsHandshakeMessage? auth;
        try
        {
            auth = JsonSerializer.Deserialize<WsHandshakeMessage>(authText, JsonDefaults.Options);
        }
        catch
        {
            auth = null;
        }

        var expectedClientProof = CryptoUtil.Hmac(pairKey, CryptoUtil.ProofText("client", nonceP, nonceS));
        byte[] actualClientProof;
        try
        {
            actualClientProof = Convert.FromBase64String(auth?.Proof ?? "");
        }
        catch
        {
            actualClientProof = [];
        }

        if (auth?.T != WsMessageTypes.Auth || !CryptoUtil.FixedTimeEquals(expectedClientProof, actualClientProof))
        {
            _logger.LogWarning("Rejected phone session with invalid proof");
            await RejectAsync(
                socket,
                "auth",
                "配对信息已失效，请重新配对。",
                cancellationToken).ConfigureAwait(false);
            return;
        }

        var (clientToServer, serverToClient) = CryptoUtil.DeriveSessionKeys(pairKey, nonceP, nonceS);

        var session = new PhoneSession(
            socket,
            _viiper,
            // The names are from the phone's point of view: data the PC sends is
            // sealed with the server-to-client key, data it receives is opened
            // with the client-to-server key.
            new AeadSession(serverToClient),
            new AeadSession(clientToServer),
            hello.PasswordSet == true,
            _loggerFactory.CreateLogger<PhoneSession>());

        PhoneSession? previous;
        lock (_gate)
        {
            previous = _session;
            _session = session;
        }

        if (previous is not null)
        {
            using var closeTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            await previous.CloseAsync(closeTimeout.Token).ConfigureAwait(false);
        }

        try
        {
            await SendHandshakeAsync(socket, new WsHandshakeMessage
            {
                T = WsMessageTypes.Ready,
                V = UtochpadConstants.ProtocolVersion,
            }, cancellationToken).ConfigureAwait(false);

            _logger.LogInformation("Phone session established ({DeviceName})", _config.PairedDeviceName);

            await session.RunAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            bool current;
            lock (_gate)
            {
                current = ReferenceEquals(_session, session);
                if (current)
                    _session = null;
            }

            if (current)
                await _viiper.LeaveAsync().ConfigureAwait(false);

            _logger.LogInformation("Phone session closed");
        }
    }

    /// <summary>Sends an error handshake and closes the socket.</summary>
    private static async Task RejectAsync(
        WebSocket socket, string code, string message, CancellationToken cancellationToken)
    {
        await SendHandshakeAsync(socket, new WsHandshakeMessage
        {
            T = WsMessageTypes.Error,
            Code = code,
            Message = message,
        }, cancellationToken).ConfigureAwait(false);

        // The close reason is only a diagnostic; the code, with underscores
        // replaced, is the readable form ("pair_token" -> "pair token").
        await CloseAsync(socket, code.Replace('_', ' '), cancellationToken).ConfigureAwait(false);
    }

    private static async Task SendHandshakeAsync(
        WebSocket socket, WsHandshakeMessage message, CancellationToken cancellationToken)
    {
        var json = JsonSerializer.SerializeToUtf8Bytes(message, JsonDefaults.Options);
        await socket.SendAsync(json, WebSocketMessageType.Text, true, cancellationToken).ConfigureAwait(false);
    }

    private static async Task<string?> ReceiveTextAsync(
        WebSocket socket, TimeSpan timeout, CancellationToken cancellationToken)
    {
        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeoutCts.CancelAfter(timeout);

        using var buffer = new MemoryStream();
        var chunk = new byte[8192];

        try
        {
            while (true)
            {
                var result = await socket.ReceiveAsync(chunk, timeoutCts.Token).ConfigureAwait(false);
                if (result.MessageType == WebSocketMessageType.Close)
                    return null;

                buffer.Write(chunk, 0, result.Count);
                if (buffer.Length > 64 * 1024)
                    return null;

                if (result.EndOfMessage)
                    return Encoding.UTF8.GetString(buffer.ToArray());
            }
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return null;
        }
    }

    private static async Task CloseAsync(WebSocket socket, string reason, CancellationToken cancellationToken)
    {
        if (socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
        {
            try
            {
                await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, reason, cancellationToken).ConfigureAwait(false);
            }
            catch
            {
                // best effort
            }
        }
    }

    private static string Base64Url(byte[] data) =>
        Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static string[] GetLanAddresses()
    {
        var addresses = new List<string>();

        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up)
                continue;
            if (nic.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
                continue;

            foreach (var unicast in nic.GetIPProperties().UnicastAddresses)
            {
                if (unicast.Address.AddressFamily != AddressFamily.InterNetwork)
                    continue;

                var value = unicast.Address.ToString();
                if (IPAddress.IsLoopback(unicast.Address) || value.StartsWith("169.254.", StringComparison.Ordinal))
                    continue;

                if (!addresses.Contains(value))
                    addresses.Add(value);
            }
        }

        if (addresses.Count == 0)
            addresses.Add("127.0.0.1");

        // The phone tries these in order. Prefer typical home/office LAN
        // ranges over virtual adapters and unusual networks.
        addresses.Sort((left, right) => LanScore(left).CompareTo(LanScore(right)));
        return addresses.ToArray();
    }

    private static int LanScore(string address)
    {
        if (address.StartsWith("192.168.", StringComparison.Ordinal)) return 0;
        if (address.StartsWith("10.", StringComparison.Ordinal)) return 1;
        if (address.StartsWith("172.", StringComparison.Ordinal))
        {
            var parts = address.Split('.');
            if (parts.Length > 1 && int.TryParse(parts[1], out var second) &&
                second is >= 16 and <= 31)
                return 2;
        }

        return 3;
    }

    private sealed record PairToken(string Token, DateTimeOffset ExpiresAt);
}

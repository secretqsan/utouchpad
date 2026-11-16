using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using Utochpad.Service.Protocol;

namespace Utochpad.Service;

/// <summary>
/// Owns the VIIPER server process and the currently attached virtual devices.
///
/// The phone exposes a touchpad surface and a radial knob on the same screen,
/// so both HID devices have to be usable at once. They are attached to a single
/// virtual USB bus, each with its own control stream. A mode switch tears the
/// bus down and recreates the devices it needs.
/// </summary>
public sealed class ViiperManager : IAsyncDisposable
{
    private readonly ServiceConfig _config;
    private readonly ILogger<ViiperManager> _logger;

    private readonly SemaphoreSlim _modeLock = new(1, 1);
    private readonly SemaphoreSlim _streamLock = new(1, 1);

    private readonly ConcurrentDictionary<string, DeviceStream> _devices = new(StringComparer.Ordinal);

    private Process? _process;
    private int? _busId;
    private string? _mode;
    private bool _disposed;

    public ViiperManager(ServiceConfig config, ILogger<ViiperManager> logger)
    {
        _config = config;
        _logger = logger;
    }

    public bool IsProcessRunning => _process is { HasExited: false };

    public string? LastError { get; private set; }

    /// <summary>True when a live device is attached for the given payload type.</summary>
    public bool CanWrite(byte payloadType) =>
        DevicesForPayload(payloadType) is { } type && HasLiveDevice(type);

    private bool HasLiveDevice(string type)
    {
        if (!_devices.TryGetValue(type, out var device))
            return false;

        try
        {
            return device.Stream.Socket is { Connected: true };
        }
        catch
        {
            // The stream may be disposed concurrently by LeaveCoreAsync.
            return false;
        }
    }

    public async Task<bool> StartAsync(CancellationToken cancellationToken = default)
    {
        if (IsProcessRunning && await PingAsync(cancellationToken).ConfigureAwait(false))
            return true;

        var exe = ResolveViiperPath();
        if (exe is null)
        {
            LastError = "电脑上的输入组件缺失，请重新安装 Utochpad。";
            _logger.LogError("{Error}", LastError);
            return false;
        }

        try
        {
            KillProcess();

            var psi = new ProcessStartInfo
            {
                FileName = exe,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                WorkingDirectory = Path.GetDirectoryName(exe)!,
            };

            psi.ArgumentList.Add("server");
            psi.ArgumentList.Add($"--api.addr=127.0.0.1:{_config.ViiperApiPort}");
            psi.ArgumentList.Add($"--usb.addr=127.0.0.1:{_config.ViiperUsbPort}");
            psi.ArgumentList.Add("--no-tray");
            psi.ArgumentList.Add("--update-notify=none");
            psi.ArgumentList.Add("--log.level=info");
            psi.ArgumentList.Add("--api.auto-attach-local-client");
            psi.ArgumentList.Add("--api.auto-attach-windows-native");

            var process = new Process { StartInfo = psi, EnableRaisingEvents = true };
            process.OutputDataReceived += (_, e) =>
            {
                if (!string.IsNullOrWhiteSpace(e.Data)) _logger.LogInformation("viiper: {Line}", e.Data);
            };
            process.ErrorDataReceived += (_, e) =>
            {
                if (!string.IsNullOrWhiteSpace(e.Data)) _logger.LogWarning("viiper: {Line}", e.Data);
            };
            process.Exited += (_, _) =>
            {
                if (!_disposed) _logger.LogWarning("VIIPER exited with code {Code}", process.ExitCode);
            };

            if (!process.Start())
            {
                process.Dispose();
                LastError = "无法启动输入组件，请重新安装 Utochpad。";
                return false;
            }

            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            _process = process;

            for (var i = 0; i < 20 && !cancellationToken.IsCancellationRequested; i++)
            {
                await Task.Delay(250, cancellationToken).ConfigureAwait(false);
                if (await PingAsync(cancellationToken).ConfigureAwait(false))
                {
                    LastError = null;
                    _logger.LogInformation("VIIPER is ready on API port {Port}", _config.ViiperApiPort);
                    return true;
                }

                if (process.HasExited)
                    break;
            }

            LastError = "输入组件启动超时，请稍后重试。";
            _logger.LogError("{Error}", LastError);
            return false;
        }
        catch (Exception ex)
        {
            LastError = "输入组件启动失败，请重新安装 Utochpad。";
            _logger.LogError(ex, "Failed to start VIIPER");
            return false;
        }
    }

    public async Task RestartAsync(CancellationToken cancellationToken = default)
    {
        await _modeLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            await LeaveCoreAsync().ConfigureAwait(false);
            KillProcess();
            _process = null;
            await StartAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _modeLock.Release();
        }
    }

    /// <summary>
    /// Creates the virtual device(s) requested by <paramref name="mode"/> and
    /// holds a control stream for each one.
    /// </summary>
    public async Task<(bool Ok, string? Error)> EnterModeAsync(string mode, CancellationToken cancellationToken = default)
    {
        var wanted = DevicesForMode(mode);
        if (wanted.Length == 0)
            return (false, "无法启动输入设备，请重试。");

        await _modeLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (_mode == mode && wanted.All(HasLiveDevice))
                return (true, null);

            await LeaveCoreAsync().ConfigureAwait(false);

            if (!await StartAsync(cancellationToken).ConfigureAwait(false))
                return (false, LastError ?? "输入组件未运行，请稍后重试。");

            var createResponse = await ApiRequestAsync("bus/create", cancellationToken).ConfigureAwait(false);
            if (!TryGetInt(createResponse, "busId", out var busId))
                return (false, "无法启动触控板，请重试。");

            _busId = busId;

            foreach (var deviceType in wanted)
            {
                var device = await AttachDeviceAsync(busId, deviceType, cancellationToken).ConfigureAwait(false);
                _devices[deviceType] = device;
            }

            _mode = mode;
            _logger.LogInformation(
                "Attached {Mode} device(s) on bus {BusId}: {Devices}",
                mode,
                busId,
                string.Join(", ", _devices.Values.Select(d => $"{d.Type}#{d.DeviceId}")));

            if (_devices.ContainsKey(UtochpadConstants.ViiperDeviceTouchpad))
                await WaitForPtpActiveAsync(busId, _devices[UtochpadConstants.ViiperDeviceTouchpad].DeviceId, cancellationToken)
                    .ConfigureAwait(false);

            return (true, null);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to enter mode {Mode}", mode);
            await LeaveCoreAsync().ConfigureAwait(false);
            return (false, "无法连接触控板，请重试。");
        }
        finally
        {
            _modeLock.Release();
        }
    }

    public async Task LeaveAsync()
    {
        await _modeLock.WaitAsync().ConfigureAwait(false);
        try
        {
            await LeaveCoreAsync().ConfigureAwait(false);
        }
        finally
        {
            _modeLock.Release();
        }
    }

    /// <summary>
    /// Writes one complete device frame. PTP frames go to the precision
    /// touchpad stream, radial frames to the radial controller stream, so both
    /// devices can receive input concurrently.
    /// </summary>
    public async Task<bool> WriteFrameAsync(byte payloadType, ReadOnlyMemory<byte> frame)
    {
        var expected = payloadType switch
        {
            PhonePayloadType.Ptp => UtochpadConstants.PtpWireSize,
            PhonePayloadType.Radial => UtochpadConstants.RadialWireSize,
            _ => -1,
        };

        if (expected < 0 || frame.Length != expected)
            return false;

        if (DevicesForPayload(payloadType) is not { } type)
            return false;

        await _streamLock.WaitAsync().ConfigureAwait(false);
        try
        {
            if (!_devices.TryGetValue(type, out var device))
                return false;

            var stream = device.Stream;
            if (stream.Socket is { Connected: false })
                return false;

            // NetworkStream has no user-space buffer; Flush is a no-op. Sending
            // each 32-byte frame immediately keeps input latency low.
            await stream.WriteAsync(frame).ConfigureAwait(false);
            return true;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to write {Type} frame to VIIPER", type);
            return false;
        }
        finally
        {
            _streamLock.Release();
        }
    }

    private async Task<DeviceStream> AttachDeviceAsync(
        int busId,
        string deviceType,
        CancellationToken cancellationToken)
    {
        var addResponse = await ApiRequestAsync(
            $"bus/{busId}/add {{\"type\":\"{deviceType}\"}}", cancellationToken).ConfigureAwait(false);

        if (!TryGetString(addResponse, "devId", out var deviceId))
            throw new InvalidOperationException($"创建虚拟设备失败: {addResponse}");

        // VIIPER drops a device if no control stream connects within 5 seconds.
        var client = new TcpClient { NoDelay = true };
        try
        {
            await client.ConnectAsync(IPAddress.Loopback, _config.ViiperApiPort, cancellationToken)
                .ConfigureAwait(false);
            var stream = client.GetStream();
            var handshake = Encoding.UTF8.GetBytes($"bus/{busId}/{deviceId}\0");
            await stream.WriteAsync(handshake, cancellationToken).ConfigureAwait(false);
            await stream.FlushAsync(cancellationToken).ConfigureAwait(false);

            return new DeviceStream(deviceType, deviceId, client, stream);
        }
        catch
        {
            client.Dispose();
            throw;
        }
    }

    private async Task LeaveCoreAsync()
    {
        foreach (var device in _devices.Values)
        {
            try { device.Stream.Dispose(); } catch { }
            try { device.Client.Dispose(); } catch { }
        }

        _devices.Clear();

        var busId = _busId;
        _busId = null;
        _mode = null;

        if (busId is not null)
        {
            try
            {
                await ApiRequestAsync($"bus/remove {busId}", CancellationToken.None).ConfigureAwait(false);
            }
            catch
            {
                // The device is already gone if VIIPER is not reachable.
            }
        }
    }

    private async Task WaitForPtpActiveAsync(int busId, string deviceId, CancellationToken cancellationToken)
    {
        for (var i = 0; i < 16 && !cancellationToken.IsCancellationRequested; i++)
        {
            await Task.Delay(250, cancellationToken).ConfigureAwait(false);
            try
            {
                var list = await ApiRequestAsync($"bus/{busId}/list", cancellationToken).ConfigureAwait(false);
                using var doc = JsonDocument.Parse(list);
                if (doc.RootElement.TryGetProperty("devices", out var devices))
                {
                    foreach (var device in devices.EnumerateArray())
                    {
                        if (device.TryGetProperty("devId", out var id) &&
                            id.GetString() == deviceId &&
                            device.TryGetProperty("deviceSpecific", out var specific) &&
                            specific.TryGetProperty("ptpActive", out var active) &&
                            active.GetBoolean())
                        {
                            _logger.LogInformation("Windows accepted the precision touchpad device");
                            return;
                        }
                    }
                }
            }
            catch
            {
                return;
            }
        }

        _logger.LogWarning("Precision touchpad was not reported active within 4 seconds");
    }

    private async Task<bool> PingAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            var response = await ApiRequestAsync("ping", cancellationToken).ConfigureAwait(false);
            return response.Contains("VIIPER", StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }

    private async Task<string> ApiRequestAsync(string request, CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(5));

        using var client = new TcpClient { NoDelay = true };
        await client.ConnectAsync(IPAddress.Loopback, _config.ViiperApiPort, timeout.Token).ConfigureAwait(false);
        await using var stream = client.GetStream();

        var bytes = Encoding.UTF8.GetBytes(request + "\0");
        await stream.WriteAsync(bytes, timeout.Token).ConfigureAwait(false);
        await stream.FlushAsync(timeout.Token).ConfigureAwait(false);

        using var buffer = new MemoryStream();
        var chunk = new byte[8192];
        while (true)
        {
            var read = await stream.ReadAsync(chunk, timeout.Token).ConfigureAwait(false);
            if (read == 0)
                break;
            buffer.Write(chunk, 0, read);
        }

        return Encoding.UTF8.GetString(buffer.ToArray()).Trim();
    }

    private static string[] DevicesForMode(string mode) =>
        mode == UtochpadConstants.ModeInput
            ? [UtochpadConstants.ViiperDeviceTouchpad, UtochpadConstants.ViiperDeviceRadial]
            : [];

    private static string? DevicesForPayload(byte payloadType) => payloadType switch
    {
        PhonePayloadType.Ptp => UtochpadConstants.ViiperDeviceTouchpad,
        PhonePayloadType.Radial => UtochpadConstants.ViiperDeviceRadial,
        _ => null,
    };

    private static bool TryGetInt(string json, string name, out int value)
    {
        value = 0;
        try
        {
            using var doc = JsonDocument.Parse(json);
            return doc.RootElement.TryGetProperty(name, out var element) && element.TryGetInt32(out value);
        }
        catch
        {
            return false;
        }
    }

    private static bool TryGetString(string json, string name, out string value)
    {
        value = "";
        try
        {
            using var doc = JsonDocument.Parse(json);
            if (doc.RootElement.TryGetProperty(name, out var element))
            {
                value = element.GetString() ?? "";
                return value.Length > 0;
            }
        }
        catch
        {
            // ignore
        }

        return false;
    }

    private string? ResolveViiperPath()
    {
        var candidates = new List<string>();
        if (!string.IsNullOrWhiteSpace(_config.ViiperPath))
            candidates.Add(_config.ViiperPath);

        candidates.Add(Path.Combine(AppContext.BaseDirectory, "viiper.exe"));
        candidates.Add(Path.Combine(AppContext.BaseDirectory, "viiper", "viiper.exe"));

        // Development layout: Windows/Service/Utochpad.Service/bin/... -> artifacts/viiper
        // (built by Windows/installer/build-viiper.ps1).
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        for (var i = 0; i < 8 && dir is not null; i++, dir = dir.Parent)
            candidates.Add(Path.Combine(dir.FullName, "artifacts", "viiper", "viiper.exe"));

        foreach (var candidate in candidates)
        {
            try
            {
                if (File.Exists(candidate))
                    return Path.GetFullPath(candidate);
            }
            catch
            {
                // ignore invalid path
            }
        }

        return null;
    }

    private void KillProcess()
    {
        try
        {
            if (_process is { HasExited: false })
            {
                _process.Kill(entireProcessTree: true);
                _process.WaitForExit(3000);
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to stop VIIPER");
        }
        finally
        {
            _process?.Dispose();
            _process = null;
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed) return;
        _disposed = true;
        await LeaveAsync().ConfigureAwait(false);
        KillProcess();
        _modeLock.Dispose();
        _streamLock.Dispose();
    }

    private sealed record DeviceStream(
        string Type,
        string DeviceId,
        TcpClient Client,
        NetworkStream Stream);
}

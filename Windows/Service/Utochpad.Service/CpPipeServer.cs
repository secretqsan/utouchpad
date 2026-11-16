using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;
using Utochpad.Service.Protocol;

namespace Utochpad.Service;

/// <summary>
/// Named-pipe endpoint used by the credential provider inside LogonUI.
/// The provider sends one unlock request and waits for one response.
/// </summary>
public sealed class CpPipeServer : BackgroundService
{
    private readonly PhoneHub _hub;
    private readonly ILogger<CpPipeServer> _logger;

    public CpPipeServer(PhoneHub hub, ILogger<CpPipeServer> logger)
    {
        _hub = hub;
        _logger = logger;
    }

    // The credential provider is normally loaded into LogonUI as SYSTEM, but
    // on some configurations it runs with the interactive user's token. The
    // default named-pipe DACL only grants the creating process's identity,
    // which made the CP fail with "service is not running" before it could
    // even send its request. An explicit DACL keeps both cases working.
    protected override Task ExecuteAsync(CancellationToken stoppingToken) =>
        PipeServerRunner.RunAsync(
            UtochpadConstants.PipeCp,
            "CP",
            16 * 1024,
            HandleAsync,
            _logger,
            stoppingToken);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetNamedPipeClientSessionId(SafePipeHandle pipe, out uint clientSessionId);

    [DllImport("kernel32.dll")]
    private static extern uint WTSGetActiveConsoleSessionId();

    /// <summary>
    /// The pipe DACL has to admit the interactive user because some
    /// configurations host the credential provider under that token. Restrict
    /// the accepted clients to the console session (the locked user) and
    /// session 0 (SYSTEM/services) so another logged-in user's session cannot
    /// ask the phone for the console user's password.
    /// </summary>
    private bool IsClientSessionAllowed(NamedPipeServerStream pipe)
    {
        try
        {
            if (!GetNamedPipeClientSessionId(pipe.SafePipeHandle, out var clientSession))
            {
                _logger.LogWarning(
                    "Could not read the CP pipe client session (error {Error}); allowing the request",
                    Marshal.GetLastWin32Error());
                return true;
            }

            var consoleSession = WTSGetActiveConsoleSessionId();
            if (clientSession == 0 || clientSession == consoleSession)
                return true;

            _logger.LogWarning(
                "Rejected CP pipe client from session {ClientSession}; active console session is {ConsoleSession}",
                clientSession,
                consoleSession);
            return false;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Could not verify the CP pipe client session");
            return true;
        }
    }

    private async Task HandleAsync(NamedPipeServerStream pipe, CancellationToken stoppingToken)
    {
        await using (pipe.ConfigureAwait(false))
        {
            try
            {
                if (!IsClientSessionAllowed(pipe))
                {
                    await PipeJson.WriteAsync(pipe, new CpUnlockResponse
                    {
                        Ok = false,
                        Error = "此会话无权请求解锁",
                    }, stoppingToken).ConfigureAwait(false);
                    return;
                }

                using var document = await PipeJson.ReadAsync(pipe, stoppingToken).ConfigureAwait(false);
                if (document is null)
                    return;

                var request = PipeJson.Deserialize<CpUnlockRequest>(document);
                if (request is null || request.Method != "unlock")
                {
                    await PipeJson.WriteAsync(pipe, new CpUnlockResponse
                    {
                        Ok = false,
                        Error = "无效的凭据请求",
                    }, stoppingToken).ConfigureAwait(false);
                    return;
                }

                _logger.LogInformation(
                    "Credential provider requested unlock for {Domain}\\{User}",
                    request.Domain,
                    request.Username);

                var (ok, password, error) = await _hub.RequestUnlockAsync(request, stoppingToken).ConfigureAwait(false);

                await PipeJson.WriteAsync(pipe, new CpUnlockResponse
                {
                    Id = request.Id,
                    Ok = ok,
                    PasswordB64 = password is null ? null : Convert.ToBase64String(Encoding.UTF8.GetBytes(password)),
                    Error = error,
                }, stoppingToken).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Credential provider pipe request failed");
            }
        }
    }
}

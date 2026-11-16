using System.IO.Pipes;
using Utochpad.Service.Protocol;

namespace Utochpad.Service;

/// <summary>
/// Local administration endpoint for the control panel. Authenticated users may
/// query status, start pairing and push the Windows password to the phone.
/// </summary>
public sealed class UiPipeServer : BackgroundService
{
    private readonly PhoneHub _hub;
    private readonly ViiperManager _viiper;
    private readonly ILogger<UiPipeServer> _logger;

    public UiPipeServer(
        PhoneHub hub,
        ViiperManager viiper,
        ILogger<UiPipeServer> logger)
    {
        _hub = hub;
        _viiper = viiper;
        _logger = logger;
    }

    protected override Task ExecuteAsync(CancellationToken stoppingToken) =>
        PipeServerRunner.RunAsync(
            UtochpadConstants.PipeUi,
            "UI",
            32 * 1024,
            HandleAsync,
            _logger,
            stoppingToken);

    private async Task HandleAsync(NamedPipeServerStream pipe, CancellationToken stoppingToken)
    {
        await using (pipe.ConfigureAwait(false))
        {
            try
            {
                using var document = await PipeJson.ReadAsync(pipe, stoppingToken).ConfigureAwait(false);
                if (document is null)
                    return;

                var request = PipeJson.Deserialize<PipeRequest>(document);
                if (request is null)
                    return;

                PipeResponse response = await DispatchAsync(request, stoppingToken).ConfigureAwait(false);
                await PipeJson.WriteAsync(pipe, response, stoppingToken).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "UI pipe request failed");
            }
        }
    }

    private async Task<PipeResponse> DispatchAsync(PipeRequest request, CancellationToken cancellationToken)
    {
        switch (request.Method)
        {
            case PipeMethods.Status:
                return PipeResponse.Success(request.Id, _hub.GetStatus());

            case PipeMethods.PairBegin:
                return PipeResponse.Success(request.Id, _hub.BeginPairing());

            case PipeMethods.PairCancel:
                _hub.CancelPairing();
                return PipeResponse.Success(request.Id);

            case PipeMethods.PairUnpair:
                await _hub.UnpairAsync(cancellationToken).ConfigureAwait(false);
                return PipeResponse.Success(request.Id);

            case PipeMethods.PasswordSet:
            {
                var password = request.StringParam("password");
                if (string.IsNullOrEmpty(password))
                    return PipeResponse.Failure(request.Id, "密码不能为空");

                var (ok, error) = await _hub.SetPasswordAsync(password, cancellationToken).ConfigureAwait(false);
                return ok
                    ? PipeResponse.Success(request.Id)
                    : PipeResponse.Failure(request.Id, error ?? "手机未确认");
            }

            case PipeMethods.ViiperRestart:
            {
                await _viiper.RestartAsync(cancellationToken).ConfigureAwait(false);
                return _viiper.IsProcessRunning
                    ? PipeResponse.Success(request.Id)
                    : PipeResponse.Failure(request.Id, _viiper.LastError ?? "VIIPER 未运行");
            }

            default:
                return PipeResponse.Failure(request.Id, $"未知方法: {request.Method}");
        }
    }
}

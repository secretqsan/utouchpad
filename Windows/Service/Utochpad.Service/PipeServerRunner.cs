using System.IO.Pipes;

namespace Utochpad.Service;

/// <summary>
/// Accept loop shared by the credential-provider and control-panel pipes.
/// Each connection is handled on its own task while the server immediately
/// waits for the next client.
/// </summary>
internal static class PipeServerRunner
{
    public static async Task RunAsync(
        string pipeName,
        string logName,
        int bufferSize,
        Func<NamedPipeServerStream, CancellationToken, Task> handle,
        ILogger logger,
        CancellationToken stoppingToken)
    {
        var security = PipeSecurityFactory.Create();

        while (!stoppingToken.IsCancellationRequested)
        {
            NamedPipeServerStream? pipe = null;
            try
            {
                pipe = NamedPipeServerStreamAcl.Create(
                    pipeName,
                    PipeDirection.InOut,
                    maxNumberOfServerInstances: 4,
                    PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous,
                    inBufferSize: bufferSize,
                    outBufferSize: bufferSize,
                    pipeSecurity: security);

                await pipe.WaitForConnectionAsync(stoppingToken).ConfigureAwait(false);
                _ = handle(pipe, stoppingToken);
                pipe = null;
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "{Name} pipe server error", logName);
                await Task.Delay(1000, stoppingToken).ConfigureAwait(false);
            }
            finally
            {
                pipe?.Dispose();
            }
        }
    }
}

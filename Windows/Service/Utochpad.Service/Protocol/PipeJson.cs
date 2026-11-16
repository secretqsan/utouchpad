using System.Text;
using System.Text.Json;

namespace Utochpad.Service.Protocol;

/// <summary>Newline-delimited JSON helper used by the service's named-pipe endpoints.</summary>
public static class PipeJson
{
    public const int MaxLineBytes = 1024 * 1024;

    public static async Task WriteAsync(Stream stream, object value, CancellationToken cancellationToken = default)
    {
        var json = JsonSerializer.SerializeToUtf8Bytes(value, JsonDefaults.Options);
        if (json.Length > MaxLineBytes)
            throw new InvalidOperationException("Pipe message is too large.");

        await stream.WriteAsync(json, cancellationToken).ConfigureAwait(false);
        await stream.WriteAsync("\n"u8.ToArray(), cancellationToken).ConfigureAwait(false);
        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    public static async Task<JsonDocument?> ReadAsync(Stream stream, CancellationToken cancellationToken = default)
    {
        var buffer = new MemoryStream();
        var one = new byte[1];
        while (true)
        {
            var read = await stream.ReadAsync(one, cancellationToken).ConfigureAwait(false);
            if (read == 0)
                return buffer.Length == 0 ? null : Parse(buffer);

            if (one[0] == (byte)'\n')
                return Parse(buffer);

            buffer.WriteByte(one[0]);
            if (buffer.Length > MaxLineBytes)
                throw new InvalidOperationException("Pipe message is too large.");
        }
    }

    public static T? Deserialize<T>(JsonDocument document) =>
        document.RootElement.Deserialize<T>(JsonDefaults.Options);

    private static JsonDocument Parse(MemoryStream buffer) =>
        JsonDocument.Parse(Encoding.UTF8.GetString(buffer.ToArray()));
}

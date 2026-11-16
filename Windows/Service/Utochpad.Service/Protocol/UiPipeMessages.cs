using System.Text.Json;

namespace Utochpad.Service.Protocol;

/// <summary>
/// One request over the UI named pipe; every connection carries one request and
/// one response.
/// </summary>
public sealed class PipeRequest
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Method { get; set; } = "";
    public JsonElement? Params { get; set; }

    public string? StringParam(string name)
    {
        if (Params is not { } p || p.ValueKind != JsonValueKind.Object) return null;
        return p.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;
    }
}

public sealed class PipeResponse
{
    public string Id { get; set; } = "";
    public bool Ok { get; set; }
    public JsonElement? Result { get; set; }
    public string? Error { get; set; }

    public static PipeResponse Success(string id, object? result = null) => new()
    {
        Id = id,
        Ok = true,
        Result = result is null ? null : JsonSerializer.SerializeToElement(result, JsonDefaults.Options),
    };

    public static PipeResponse Failure(string id, string error) => new()
    {
        Id = id,
        Ok = false,
        Error = error,
    };
}

public static class PipeMethods
{
    public const string Status = "status";
    public const string PairBegin = "pair.begin";
    public const string PairCancel = "pair.cancel";
    public const string PairUnpair = "pair.unpair";
    public const string PasswordSet = "password.set";
    public const string ViiperRestart = "viiper.restart";
}

public sealed class UiStatus
{
    public string Version { get; set; } = "";
    public bool Paired { get; set; }
    /// <summary>
    /// Increments every time a phone completes pairing. The panel watches it so
    /// an already-paired PC does not mistake its old pairing for the new one.
    /// </summary>
    public int PairVersion { get; set; }
    public string? PhoneName { get; set; }
    public bool PhoneConnected { get; set; }
    public bool PhoneHasPassword { get; set; }
    public bool ViiperRunning { get; set; }
    public string? ActiveMode { get; set; }
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
    /// <summary>Pairing generation the QR was issued for; see <see cref="UiStatus.PairVersion"/>.</summary>
    public int PairVersion { get; set; }
}

/// <summary>Payload the PC shows as a QR code and the phone scans.</summary>
public sealed class PairQrPayload
{
    public int V { get; set; } = 1;
    public string Name { get; set; } = "";
    public string[] Hosts { get; set; } = [];
    public int Port { get; set; }
    public string Pub { get; set; } = "";
    public string Token { get; set; } = "";
}

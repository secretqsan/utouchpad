namespace Utochpad.Service.Protocol;

/// <summary>Request written by the credential provider to the service.</summary>
public sealed class CpUnlockRequest
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Method { get; set; } = "unlock";
    public string Username { get; set; } = "";
    public string Domain { get; set; } = "";
    public int TimeoutSeconds { get; set; } = 30;
}

/// <summary>Response read by the credential provider.</summary>
public sealed class CpUnlockResponse
{
    public string Id { get; set; } = "";
    public bool Ok { get; set; }
    /// <summary>Base64 of the UTF-8 Windows account password.</summary>
    public string? PasswordB64 { get; set; }
    public string? Error { get; set; }
}

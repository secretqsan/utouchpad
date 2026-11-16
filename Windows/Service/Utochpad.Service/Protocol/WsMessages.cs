namespace Utochpad.Service.Protocol;

/// <summary>
/// Plaintext handshake messages exchanged before the encrypted channel is up.
/// The pairing key is never transmitted; it is derived with ECDH.
/// </summary>
public sealed class WsHandshakeMessage
{
    public string T { get; set; } = "";
    public int V { get; set; } = 1;
    public string? DeviceId { get; set; }
    public string? DeviceName { get; set; }
    public string? NonceP { get; set; }
    public string? NonceS { get; set; }
    public string? Proof { get; set; }
    public string? Token { get; set; }
    public string? PhonePub { get; set; }
    public string? ServicePub { get; set; }
    public bool? PasswordSet { get; set; }
    public string? Code { get; set; }
    public string? Message { get; set; }
}

/// <summary>Encrypted control messages (payload type 1).</summary>
public sealed class WsControlMessage
{
    public string T { get; set; } = "";
    public string? Id { get; set; }
    public string? Username { get; set; }
    public string? Domain { get; set; }
    public int TimeoutSeconds { get; set; }
    public bool? Ok { get; set; }
    public string? PasswordB64 { get; set; }
    public string? Error { get; set; }
    public string? Mode { get; set; }
    public string? Message { get; set; }
}

public static class WsMessageTypes
{
    public const string Hello = "hello";
    public const string Challenge = "challenge";
    public const string Auth = "auth";
    public const string Ready = "ready";
    public const string Pair = "pair";
    public const string PairOk = "pairOk";
    public const string Error = "error";

    public const string UnlockRequest = "unlockRequest";
    public const string UnlockResponse = "unlockResponse";
    public const string CancelUnlock = "cancelUnlock";
    public const string SetPassword = "setPassword";
    public const string SetPasswordResult = "setPasswordResult";
    public const string EnterMode = "enterMode";
    public const string ModeReady = "modeReady";
    public const string ModeError = "modeError";
}

public static class PhonePayloadType
{
    /// <summary>UTF-8 JSON control message.</summary>
    public const byte Control = 1;

    /// <summary>32-byte VIIPER precision-touchpad wire frame.</summary>
    public const byte Ptp = 2;

    /// <summary>3-byte VIIPER radial wire frame (dial:i16 LE, buttons:u8).</summary>
    public const byte Radial = 3;
}

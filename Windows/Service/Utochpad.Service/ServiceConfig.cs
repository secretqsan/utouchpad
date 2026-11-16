using System.Security.Cryptography;
using System.Text.Json;
using Utochpad.Service.Crypto;
using Utochpad.Service.Protocol;

namespace Utochpad.Service;

public sealed class ServiceConfig
{
    private static readonly byte[] Entropy = "utochpad-service-key-v1"u8.ToArray();
    // Keep existing installations readable after the brand rename. New keys
    // are always written with Entropy; this is only a one-way fallback.
    private static readonly byte[] LegacyEntropy = "utouchpad-service-key-v1"u8.ToArray();
    private readonly object _gate = new();

    public int Port { get; set; } = UtochpadConstants.DefaultPort;
    public int ViiperApiPort { get; set; } = UtochpadConstants.ViiperApiPort;
    public int ViiperUsbPort { get; set; } = UtochpadConstants.ViiperUsbPort;

    /// <summary>DPAPI(LocalMachine)-protected PKCS#8 private key.</summary>
    public string? PrivateKeyProtected { get; set; }
    public string? PublicKey { get; set; }

    public string? PairedPhonePublicKey { get; set; }
    public string? PairedDeviceId { get; set; }
    public string? PairedDeviceName { get; set; }

    public string? ViiperPath { get; set; }

    public bool IsPaired => !string.IsNullOrEmpty(PairedPhonePublicKey) && !string.IsNullOrEmpty(PairedDeviceId);

    public static string ConfigPath(string dataDirectory) => Path.Combine(dataDirectory, "config.json");

    public static ServiceConfig Load(string dataDirectory)
    {
        var path = ConfigPath(dataDirectory);
        ServiceConfig? config = null;

        if (File.Exists(path))
        {
            try
            {
                // The file is written with JsonDefaults (camelCase). Reading it
                // with the default options would leave every property null and
                // silently regenerate the identity, dropping the pairing.
                config = JsonSerializer.Deserialize<ServiceConfig>(
                    File.ReadAllText(path), JsonDefaults.Options);

                if (config is not null &&
                    (string.IsNullOrEmpty(config.PrivateKeyProtected) ||
                     string.IsNullOrEmpty(config.PublicKey)))
                {
                    throw new InvalidDataException("Config is missing the service key.");
                }
            }
            catch
            {
                // A corrupt config is moved aside rather than silently discarded.
                config = null;
                try { File.Move(path, path + ".bad", overwrite: true); } catch { }
            }
        }

        config ??= new ServiceConfig();

        if (string.IsNullOrEmpty(config.PrivateKeyProtected) || string.IsNullOrEmpty(config.PublicKey))
        {
            var (privateKey, publicKey) = CryptoUtil.GenerateKeyPair();
            config.PrivateKeyProtected = Convert.ToBase64String(ProtectedData.Protect(privateKey, Entropy, DataProtectionScope.LocalMachine));
            config.PublicKey = Convert.ToBase64String(publicKey);
            config.Save(dataDirectory);
        }

        return config;
    }

    public byte[] GetPrivateKey()
    {
        if (string.IsNullOrEmpty(PrivateKeyProtected))
            throw new InvalidOperationException("Service private key is missing.");

        var protectedBytes = Convert.FromBase64String(PrivateKeyProtected);
        try
        {
            return ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.LocalMachine);
        }
        catch (CryptographicException)
        {
            return ProtectedData.Unprotect(protectedBytes, LegacyEntropy, DataProtectionScope.LocalMachine);
        }
    }

    public byte[] GetPublicKey() =>
        Convert.FromBase64String(PublicKey ?? throw new InvalidOperationException("Service public key is missing."));

    public void Save(string dataDirectory)
    {
        lock (_gate)
        {
            var path = ConfigPath(dataDirectory);
            var temp = path + ".tmp";
            File.WriteAllText(temp, JsonSerializer.Serialize(this, JsonDefaults.Indented));
            File.Move(temp, path, overwrite: true);
        }
    }

    public void SavePairedPhone(string publicKeyBase64, string deviceId, string deviceName, string dataDirectory)
    {
        lock (_gate)
        {
            PairedPhonePublicKey = publicKeyBase64;
            PairedDeviceId = deviceId;
            PairedDeviceName = deviceName;
        }

        Save(dataDirectory);
    }

    public void ClearPairing(string dataDirectory)
    {
        lock (_gate)
        {
            PairedPhonePublicKey = null;
            PairedDeviceId = null;
            PairedDeviceName = null;
        }

        Save(dataDirectory);
    }
}

using System.Security.Cryptography;
using System.Text;

namespace Utochpad.Service.Crypto;

/// <summary>
/// Primitives shared by the PC and Android implementations.
/// Curve: NIST P-256 (secp256r1). KDF: HKDF-SHA256. AEAD: AES-256-GCM.
/// </summary>
public static class CryptoUtil
{
    public const int NonceSize = 12;
    public const int TagSize = 16;
    public const int KeySize = 32;

    public static (byte[] PrivatePkcs8, byte[] PublicSpki) GenerateKeyPair()
    {
        using var ecdh = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
        return (ecdh.ExportPkcs8PrivateKey(), ecdh.ExportSubjectPublicKeyInfo());
    }

    public static byte[] DeriveSharedSecret(byte[] privatePkcs8, byte[] peerPublicSpki)
    {
        using var mine = ECDiffieHellman.Create();
        mine.ImportPkcs8PrivateKey(privatePkcs8, out _);

        using var peer = ECDiffieHellman.Create();
        peer.ImportSubjectPublicKeyInfo(peerPublicSpki, out _);

        return mine.DeriveRawSecretAgreement(peer.PublicKey);
    }

    public static byte[] Hkdf(byte[] ikm, byte[] salt, string info, int length) =>
        HKDF.DeriveKey(HashAlgorithmName.SHA256, ikm, length, salt, Encoding.UTF8.GetBytes(info));

    public static byte[] Hmac(byte[] key, string text) =>
        HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(text));

    public static bool FixedTimeEquals(byte[] a, byte[] b) =>
        CryptographicOperations.FixedTimeEquals(a, b);

    public static string ProofText(string role, byte[] nonceP, byte[] nonceS) =>
        $"{role}|{Convert.ToBase64String(nonceP)}|{Convert.ToBase64String(nonceS)}";

    /// <summary>Pairing key: ECDH output expanded with a fixed domain separation string.</summary>
    public static byte[] DerivePairKey(byte[] sharedSecret) =>
        Hkdf(sharedSecret, Encoding.UTF8.GetBytes("utochpad-pair-v1"), "utochpad-pair-key", KeySize);

    /// <summary>
    /// Derives the two directional session keys from the pairing key and the
    /// two handshake nonces. Using separate keys per direction means both
    /// sides can start their AES-GCM counters at zero without nonce reuse.
    /// </summary>
    public static (byte[] ClientToServer, byte[] ServerToClient) DeriveSessionKeys(
        byte[] pairKey, byte[] nonceP, byte[] nonceS)
    {
        var salt = new byte[nonceP.Length + nonceS.Length];
        Array.Copy(nonceP, 0, salt, 0, nonceP.Length);
        Array.Copy(nonceS, 0, salt, nonceP.Length, nonceS.Length);

        return (
            Hkdf(pairKey, salt, "utochpad-c2s", KeySize),
            Hkdf(pairKey, salt, "utochpad-s2c", KeySize));
    }

    public static byte[] BuildNonce(long counter)
    {
        var nonce = new byte[NonceSize];
        var value = (ulong)counter;
        for (var i = NonceSize - 1; i >= 0 && value != 0; i--)
        {
            nonce[i] = (byte)value;
            value >>= 8;
        }
        return nonce;
    }
}

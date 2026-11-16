using System.Security.Cryptography;

namespace Utochpad.Service.Crypto;

/// <summary>
/// One direction of an AES-256-GCM session:
/// [12-byte counter nonce][ciphertext][16-byte tag]. Fresh keys per session and
/// per direction let the counter start at zero without nonce reuse.
/// </summary>
public sealed class AeadSession
{
    private readonly AesGcm _aes;
    // AesGcm is not documented as thread-safe; the lock is cheap and a reused
    // instance avoids GC churn on 32-byte touch frames.
    private readonly object _gate = new();
    private long _counter;
    private long _lastReceived = -1;

    public AeadSession(byte[] key)
    {
        if (key.Length != CryptoUtil.KeySize)
            throw new ArgumentException("AES-256 key must be 32 bytes.", nameof(key));
        _aes = new AesGcm(key, CryptoUtil.TagSize);
    }

    public byte[] Seal(ReadOnlySpan<byte> plaintext)
    {
        lock (_gate)
        {
            var nonce = CryptoUtil.BuildNonce(_counter++);
            var output = new byte[CryptoUtil.NonceSize + plaintext.Length + CryptoUtil.TagSize];
            nonce.CopyTo(output, 0);

            _aes.Encrypt(
                nonce,
                plaintext,
                output.AsSpan(CryptoUtil.NonceSize, plaintext.Length),
                output.AsSpan(CryptoUtil.NonceSize + plaintext.Length, CryptoUtil.TagSize));

            return output;
        }
    }

    public byte[] Open(ReadOnlySpan<byte> frame)
    {
        if (frame.Length < CryptoUtil.NonceSize + CryptoUtil.TagSize)
            throw new CryptographicException("Frame is too short.");

        lock (_gate)
        {
            var expectedNonce = CryptoUtil.BuildNonce(_lastReceived + 1);
            if (!CryptographicOperations.FixedTimeEquals(frame[..CryptoUtil.NonceSize], expectedNonce))
                throw new CryptographicException("Unexpected frame counter.");

            var cipherLength = frame.Length - CryptoUtil.NonceSize - CryptoUtil.TagSize;
            var plaintext = new byte[cipherLength];

            _aes.Decrypt(
                frame[..CryptoUtil.NonceSize],
                frame.Slice(CryptoUtil.NonceSize, cipherLength),
                frame.Slice(CryptoUtil.NonceSize + cipherLength, CryptoUtil.TagSize),
                plaintext);

            _lastReceived++;
            return plaintext;
        }
    }
}

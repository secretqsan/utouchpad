using System.Security.Cryptography;

namespace Utochpad.Service.Crypto;

/// <summary>
/// One direction of an AES-256-GCM session. Frame layout:
/// [12-byte counter nonce][ciphertext][16-byte tag].
/// The counter starts at zero for every session, which is safe because each
/// session uses freshly derived keys and each direction has its own key.
/// </summary>
public sealed class AeadSession
{
    private readonly AesGcm _aes;
    // AesGcm is not documented as thread-safe. Each direction normally has a
    // single producer, but the lock is cheap and keeps misuse from producing
    // interleaved counter values. The instance itself is reused: allocating a
    // new AesGcm per 32-byte touch frame was a measurable source of GC churn.
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

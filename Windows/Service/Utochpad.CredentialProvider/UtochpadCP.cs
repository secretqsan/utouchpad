namespace Utochpad.CredentialProvider;

using Lithnet.CredentialProvider;
using Serilog;
using System.Drawing;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security;
using System.Text;

[ComVisible(true)]
[ClassInterface(ClassInterfaceType.None)]
[ProgId("UtochpadCP")]
[Guid("FDF19A17-3800-34D6-B49A-CAE0D53ACA8F")]
public class UtochpadCP : CredentialProviderBase
{
    public UtochpadCP()
    {
        CpLogging.EnsureInitialized();
        Log.Information("UtochpadCP initialized");
    }

    public override bool IsUsageScenarioSupported(UsageScenario cpus, CredUIWinFlags dwFlags)
    {
        switch (cpus)
        {
            case UsageScenario.Logon:
            case UsageScenario.UnlockWorkstation:
            case UsageScenario.CredUI:
                return true;
            default:
                return false;
        }
    }

    public override IEnumerable<ControlBase> GetControls(UsageScenario cpus)
    {
        var imagePath = "Utochpad.CredentialProvider.Resources.TileIconBlack.png";
        if (cpus == UsageScenario.Logon)
            imagePath = "Utochpad.CredentialProvider.Resources.TileIconWhite.png";

        var image = new Bitmap(
            Assembly.GetExecutingAssembly().GetManifestResourceStream(imagePath)
            ?? throw new Exception($"Failed to load embedded resource: {imagePath}"));

        yield return new CredentialProviderLogoControl(
            "ImageCredentialProvider", "Credential provider logo", image);

        yield return new CredentialProviderLabelControl(
            "CredProviderLabel", "Utochpad 手机指纹");
    }

    public override bool ShouldIncludeUserTile(CredentialProviderUser user) => true;

    public override bool ShouldIncludeGenericTile() => false;

    public override CredentialTile CreateGenericTile() => new UtochpadTile(this);

    public override CredentialTile2 CreateUserTile(CredentialProviderUser user) => new UtochpadTile(this, user);
}

public class UtochpadTile : CredentialTile3
{
    private const int UnlockTimeoutSeconds = 30;

    public UtochpadTile(CredentialProviderBase credentialProvider)
        : base(credentialProvider)
    {
    }

    public UtochpadTile(CredentialProviderBase credentialProvider, CredentialProviderUser user)
        : base(credentialProvider, user)
    {
    }

    /// <summary>
    /// The tile only contains a logo and a label: there is no text field and no
    /// submit button for the user to press. Selecting the tile is therefore the
    /// complete user action, so tell the host to serialize the credential right
    /// away. GetCredentials then sends the unlock request to the phone.
    /// </summary>
    protected override bool OnSelectedShouldAutoLogon() => true;

    protected override void OnSelected()
    {
        Log.Information("Utochpad tile selected; requesting phone verification");
    }

    protected override CredentialResponseBase GetCredentials()
    {
        CpLogging.EnsureInitialized();

        var qualifiedName = this.User?.QualifiedUserName;
        SplitUserName(qualifiedName, out var domain, out var username);

        Log.Information("Requesting phone unlock for {Domain}\\{User}", domain, username);

        var response = CpIpcClient.RequestUnlock(username, domain, UnlockTimeoutSeconds);

        if (!response.Ok || string.IsNullOrEmpty(response.PasswordB64))
        {
            var message = response.Error ?? "手机验证失败";
            Log.Information("Phone unlock failed: {Message}", message);

            return Failed(domain, username, message);
        }

        SecureString password;
        try
        {
            var plaintext = Encoding.UTF8.GetString(Convert.FromBase64String(response.PasswordB64));
            password = new SecureString();
            foreach (var character in plaintext)
                password.AppendChar(character);
            password.MakeReadOnly();
        }
        catch (Exception ex)
        {
            Log.Error(ex, "Failed to decode the password returned by the service");
            return Failed(domain, username, "无法读取手机返回的密码，请重试。");
        }

        Log.Information("Phone unlock succeeded for {Domain}\\{User}", domain, username);

        return new CredentialResponseSecure
        {
            IsSuccess = true,
            Password = password,
            Domain = domain,
            Username = username,
        };
    }

    private static CredentialResponseSecure Failed(string domain, string username, string message) => new()
    {
        IsSuccess = false,
        StatusText = message,
        StatusIcon = StatusIcon.Error,
        Domain = domain,
        Username = username,
    };

    private static void SplitUserName(string? qualifiedName, out string domain, out string username)
    {
        if (!string.IsNullOrWhiteSpace(qualifiedName))
        {
            var separator = qualifiedName.IndexOf('\\');
            if (separator > 0 && separator < qualifiedName.Length - 1)
            {
                domain = qualifiedName[..separator];
                username = qualifiedName[(separator + 1)..];
                return;
            }

            if (qualifiedName.Contains('@'))
            {
                domain = "MicrosoftAccount";
                username = qualifiedName;
                return;
            }

            username = qualifiedName;
            domain = Environment.UserDomainName;
            return;
        }

        domain = Environment.UserDomainName;
        username = Environment.UserName;
    }
}

internal static class CpLogging
{
    private static readonly object Gate = new();
    private static bool _initialized;

    public static void EnsureInitialized()
    {
        if (_initialized)
            return;

        lock (Gate)
        {
            if (_initialized)
                return;

            var logDirectory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
                "Utochpad",
                "logs");

            // The installed service hardens this directory to SYSTEM and
            // Administrators. If the provider is hosted under a different
            // token, fall back to the temp directory instead of failing to
            // construct the credential tile.
            Log.Logger = CreateFileLogger(logDirectory)
                ?? CreateFileLogger(Path.GetTempPath())
                ?? new LoggerConfiguration().MinimumLevel.Fatal().CreateLogger();

            _initialized = true;
        }
    }

    private static Serilog.ILogger? CreateFileLogger(string directory)
    {
        try
        {
            Directory.CreateDirectory(directory);
            return new LoggerConfiguration()
                .MinimumLevel.Information()
                .WriteTo.File(
                    Path.Combine(directory, "cp-.log"),
                    rollingInterval: RollingInterval.Day,
                    retainedFileCountLimit: 14,
                    shared: true)
                .CreateLogger();
        }
        catch
        {
            return null;
        }
    }
}

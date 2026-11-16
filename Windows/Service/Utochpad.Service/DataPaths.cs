using System.Security.AccessControl;
using System.Security.Principal;

namespace Utochpad.Service;

public sealed class DataPaths
{
    public DataPaths()
    {
        DataDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            "Utochpad");
        LogDirectory = Path.Combine(DataDirectory, "logs");

        Directory.CreateDirectory(DataDirectory);
        Directory.CreateDirectory(LogDirectory);
        SecureDirectory(DataDirectory);
    }

    public string DataDirectory { get; }
    public string LogDirectory { get; }

    public string ServiceLogPath => Path.Combine(LogDirectory, "service-.log");

    /// <summary>
    /// ProgramData is readable by every local user by default. The config holds
    /// the service key that can impersonate the PC to the phone, so restrict it
    /// to SYSTEM and Administrators.
    /// </summary>
    private static void SecureDirectory(string path)
    {
        try
        {
            // Running from a developer console as a normal user must not lock the
            // current user out of the directory. The installed service runs as
            // LocalSystem and does harden it.
            using var identity = WindowsIdentity.GetCurrent();
            var isSystem = identity.User?.IsWellKnown(WellKnownSidType.LocalSystemSid) == true;
            var isAdmin = new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
            if (!isSystem && !isAdmin)
                return;

            var directory = new DirectoryInfo(path);
            var security = new DirectorySecurity();
            security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);

            security.AddAccessRule(new FileSystemAccessRule(
                new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
                FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                PropagationFlags.None,
                AccessControlType.Allow));

            security.AddAccessRule(new FileSystemAccessRule(
                new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
                FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                PropagationFlags.None,
                AccessControlType.Allow));

            directory.SetAccessControl(security);
        }
        catch
        {
            // ACL hardening is best effort; the service still runs if a policy blocks it.
        }
    }
}

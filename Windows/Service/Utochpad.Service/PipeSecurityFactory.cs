using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;

namespace Utochpad.Service;

internal static class PipeSecurityFactory
{
    /// <summary>
    /// DACL shared by the UI and credential-provider pipes. The panel runs as
    /// the interactive user, and the credential provider is loaded into
    /// LogonUI under either SYSTEM or that user's token, so Authenticated Users
    /// get read/write while the service and administrators keep full control.
    /// </summary>
    public static PipeSecurity Create()
    {
        var security = new PipeSecurity();

        security.AddAccessRule(new PipeAccessRule(
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            PipeAccessRights.FullControl,
            AccessControlType.Allow));

        security.AddAccessRule(new PipeAccessRule(
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            PipeAccessRights.FullControl,
            AccessControlType.Allow));

        security.AddAccessRule(new PipeAccessRule(
            new SecurityIdentifier(WellKnownSidType.AuthenticatedUserSid, null),
            PipeAccessRights.ReadWrite,
            AccessControlType.Allow));

        return security;
    }
}

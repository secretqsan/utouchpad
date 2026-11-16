using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;

namespace Utochpad.Service;

internal static class PipeSecurityFactory
{
    /// <summary>
    /// Shared by the UI and CP pipes: the panel runs as the interactive user and
    /// the CP may run under SYSTEM or that user, so Authenticated Users get
    /// read/write while the service and administrators keep full control.
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

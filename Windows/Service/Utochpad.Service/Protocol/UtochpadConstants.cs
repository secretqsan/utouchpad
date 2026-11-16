namespace Utochpad.Service.Protocol;

public static class UtochpadConstants
{
    public const int ProtocolVersion = 1;
    public const int PtpWireSize = 32;
    public const int RadialWireSize = 3;

    // Pipe names are shared: the credential provider connects to the CP pipe,
    // the panel connects to the UI pipe, and the service hosts both.
    public const string PipeCp = "utochpad-cp";
    public const string PipeUi = "utochpad-ui";

    public const string ServiceName = "UtochpadService";
    public const int DefaultPort = 42800;
    public const int ViiperApiPort = 32420;
    public const int ViiperUsbPort = 32421;

    /// <summary>
    /// Both virtual devices live on one virtual bus at the same time. The phone
    /// shows the touchpad surface and the radial knob on a single page, so the
    /// two input streams have to be usable concurrently.
    /// </summary>
    public const string ModeInput = "touchpad+radial";

    // Mode names are what the phone sends over the session; these are the
    // device type identifiers VIIPER expects in bus/<id>/add.
    public const string ViiperDeviceTouchpad = "ptp";
    public const string ViiperDeviceRadial = "radial";
}

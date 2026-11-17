using Microsoft.UI.Xaml;
using Utochpad.Panel.Services;

namespace Utochpad.Panel;

public partial class App : Application
{
    private Window? _window;

    public static UiPipeClient Service { get; } = new();

    public App()
    {
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        _window = new MainWindow();
        _window.Activate();
    }
}

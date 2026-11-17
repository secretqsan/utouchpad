using Microsoft.UI;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Utochpad.Panel.Services;
using Windows.Graphics;
using WinRT.Interop;

namespace Utochpad.Panel;

public sealed partial class MainWindow : Window
{
    private readonly DispatcherQueueTimer _pollTimer;
    private readonly AppWindow _appWindow;
    private bool _initialSizeApplied;

    public MainWindow()
    {
        InitializeComponent();
        App.Service.Updated += OnServiceUpdated;

        ApplySystemBackdrop();

        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);

        var windowId = Win32Interop.GetWindowIdFromWindow(WindowNative.GetWindowHandle(this));
        _appWindow = AppWindow.GetFromWindowId(windowId);

        if (AppWindowTitleBar.IsCustomizationSupported())
        {
            var titleBar = _appWindow.TitleBar;
            titleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
            titleBar.ButtonBackgroundColor = Colors.Transparent;
            titleBar.ButtonInactiveBackgroundColor = Colors.Transparent;
        }

        _pollTimer = DispatcherQueue.CreateTimer();
        _pollTimer.Interval = TimeSpan.FromSeconds(2);
        _pollTimer.Tick += async (_, _) => await App.Service.RefreshAsync();
        Closed += (_, _) =>
        {
            _pollTimer.Stop();
            App.Service.Updated -= OnServiceUpdated;
        };
    }

    private void ApplySystemBackdrop()
    {
        // 云母（Mica）需要 Windows 11；旧系统依次降级为亚克力、纯色背景
        if (MicaController.IsSupported())
            SystemBackdrop = new MicaBackdrop();
        else if (DesktopAcrylicController.IsSupported())
            SystemBackdrop = new DesktopAcrylicBackdrop();
        else
            RootGrid.Background = (Brush)Application.Current.Resources["ApplicationPageBackgroundThemeBrush"];
    }

    private void ApplyInitialSize()
    {
        var scale = RootNavigation.XamlRoot?.RasterizationScale ?? 1.0;
        var area = DisplayArea.GetFromWindowId(_appWindow.Id, DisplayAreaFallback.Nearest).WorkArea;
        var width = Math.Min((int)Math.Round(1040 * scale), area.Width);
        var height = Math.Min((int)Math.Round(700 * scale), area.Height);
        _appWindow.MoveAndResize(new RectInt32(
            area.X + Math.Max(0, (area.Width - width) / 2),
            area.Y + Math.Max(0, (area.Height - height) / 2),
            width,
            height));
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (!_initialSizeApplied)
        {
            _initialSizeApplied = true;
            ApplyInitialSize();
        }

        RootNavigation.SelectedItem = RootNavigation.MenuItems[0];
        NavigateTo("pairing");
        await App.Service.RefreshAsync();
        _pollTimer.Start();
    }

    private void OnNavigationSelectionChanged(
        NavigationView sender,
        NavigationViewSelectionChangedEventArgs args)
    {
        if (args.SelectedItemContainer?.Tag is string page)
            NavigateTo(page);
    }

    private void OnServiceInfoBarClose(InfoBar sender, object args) =>
        App.Service.DismissError();

    private void OnServiceUpdated(object? sender, EventArgs e)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            var message = App.Service.Error;
            ServiceInfoBar.Message = message ?? "";
            ServiceInfoBar.IsOpen = !string.IsNullOrEmpty(message);
        });
    }

    private void NavigateTo(string page)
    {
        var pageType = page switch
        {
            "pairing" => typeof(Pages.PairingPage),
            "password" => typeof(Pages.PasswordPage),
            "maintenance" => typeof(Pages.MaintenancePage),
            "about" => typeof(Pages.AboutPage),
            _ => typeof(Pages.PairingPage),
        };

        if (ContentFrame.CurrentSourcePageType != pageType)
            ContentFrame.Navigate(pageType);
    }
}

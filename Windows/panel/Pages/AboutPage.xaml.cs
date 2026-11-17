using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Utochpad.Panel.Services;

namespace Utochpad.Panel.Pages;

public sealed partial class AboutPage : Page
{
    public AboutPage()
    {
        InitializeComponent();
        NavigationCacheMode = Microsoft.UI.Xaml.Navigation.NavigationCacheMode.Required;
        var version = typeof(App).Assembly.GetName().Version;
        PanelVersionText.Text = $"v{version?.ToString(3) ?? "2.0.0"}";
        UpdateStatus(App.Service.Status);
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    private void OnLoaded(object sender, RoutedEventArgs e) =>
        App.Service.Updated += OnServiceUpdated;

    private void OnUnloaded(object sender, RoutedEventArgs e) =>
        App.Service.Updated -= OnServiceUpdated;

    private void OnServiceUpdated(object? sender, EventArgs e) =>
        DispatcherQueue.TryEnqueue(() => UpdateStatus(App.Service.Status));

    private void UpdateStatus(UiPipeClient.UiStatus? status) =>
        ServiceVersionText.Text = status is null ? "未运行" : $"v{status.Version}";
}

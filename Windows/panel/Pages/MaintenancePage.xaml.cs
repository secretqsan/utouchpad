using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Utochpad.Panel.Controls;
using Utochpad.Panel.Services;

namespace Utochpad.Panel.Pages;

public sealed partial class MaintenancePage : Page
{
    private bool _busy;

    public MaintenancePage()
    {
        InitializeComponent();
        NavigationCacheMode = Microsoft.UI.Xaml.Navigation.NavigationCacheMode.Required;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        App.Service.Updated += OnServiceUpdated;
        UpdateStatus(App.Service.Status);
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) =>
        App.Service.Updated -= OnServiceUpdated;

    private void OnServiceUpdated(object? sender, EventArgs e) =>
        DispatcherQueue.TryEnqueue(() => UpdateStatus(App.Service.Status));

    private async void OnRefreshClick(object sender, RoutedEventArgs e) =>
        await App.Service.RefreshAsync();

    private async void OnRestartClick(object sender, RoutedEventArgs e)
    {
        if (_busy)
            return;

        _busy = true;
        UpdateStatus(App.Service.Status);
        NoticeBar.IsOpen = false;
        try
        {
            await App.Service.RestartViiperAsync();
            ShowNotice("输入组件已重启。", InfoBarSeverity.Success);
        }
        catch (Exception ex) when (ex is IOException or InvalidOperationException or TimeoutException or System.Text.Json.JsonException)
        {
            ShowNotice(ex.Message, InfoBarSeverity.Error);
        }
        finally
        {
            _busy = false;
            UpdateStatus(App.Service.Status);
        }
    }

    private async void OnUnpairClick(object sender, RoutedEventArgs e)
    {
        if (_busy)
            return;

        var dialog = new ContentDialog
        {
            Title = "解除配对",
            Content = "解除后手机会清除配对记录和保存的密码，需要重新配对才能连接。确定继续吗？",
            PrimaryButtonText = "解除配对",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary)
            return;

        _busy = true;
        UpdateStatus(App.Service.Status);
        try
        {
            await App.Service.UnpairAsync();
            ShowNotice("已解除配对。", InfoBarSeverity.Informational);
        }
        catch (Exception ex) when (ex is IOException or InvalidOperationException or TimeoutException or System.Text.Json.JsonException)
        {
            ShowNotice(ex.Message, InfoBarSeverity.Error);
        }
        finally
        {
            _busy = false;
            UpdateStatus(App.Service.Status);
        }
    }

    private void UpdateStatus(UiPipeClient.UiStatus? status)
    {
        ServiceStatus.SetStatus(
            status is null ? "服务已停止" : "服务正常",
            status is null ? StatusTone.Critical : StatusTone.Positive);
        ComponentStatus.SetStatus(
            status is null
                ? "检查中"
                : status.ViiperRunning ? "组件运行中" : "组件未运行",
            status is null
                ? StatusTone.Neutral
                : status.ViiperRunning ? StatusTone.Positive : StatusTone.Caution);

        DiagnosticInfo.Text = status is null
            ? "服务未运行，请重新安装 Utochpad 或重启电脑。"
            : $"端口     {status.Port}\n" +
              $"地址     {string.Join(", ", status.Hosts)}\n" +
              $"数据目录 {status.DataDirectory}\n" +
              $"日志目录 {status.LogDirectory}" +
              (string.IsNullOrEmpty(status.PendingUnlockUser)
                  ? ""
                  : $"\n等待手机验证：{status.PendingUnlockUser}");

        RestartButton.IsEnabled = !_busy && status is not null;
        UnpairButton.IsEnabled = !_busy && status?.Paired == true;
    }

    private void ShowNotice(string message, InfoBarSeverity severity)
    {
        NoticeBar.Message = message;
        NoticeBar.Severity = severity;
        NoticeBar.IsOpen = true;
    }
}

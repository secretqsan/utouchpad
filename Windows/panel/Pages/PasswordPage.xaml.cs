using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Utochpad.Panel.Controls;
using Utochpad.Panel.Services;

namespace Utochpad.Panel.Pages;

public sealed partial class PasswordPage : Page
{
    private bool _sending;

    public PasswordPage()
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

    private void OnPasswordChanged(object sender, RoutedEventArgs e) =>
        UpdateStatus(App.Service.Status);

    private async void OnSendClick(object sender, RoutedEventArgs e) =>
        await SendPasswordAsync();

    private async void OnPasswordKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != Windows.System.VirtualKey.Enter)
            return;

        e.Handled = true;
        await SendPasswordAsync();
    }

    private async Task SendPasswordAsync()
    {
        var password = PasswordInput.Password;
        if (_sending || string.IsNullOrEmpty(password) || App.Service.Status?.PhoneConnected != true)
            return;

        _sending = true;
        NoticeBar.Severity = InfoBarSeverity.Informational;
        NoticeBar.Message = "请在手机上确认指纹…";
        NoticeBar.IsOpen = true;
        UpdateStatus(App.Service.Status);

        try
        {
            await App.Service.SetPasswordAsync(password);
            PasswordInput.Password = "";
            NoticeBar.Severity = InfoBarSeverity.Success;
            NoticeBar.Message = "已发送到手机";
        }
        catch (Exception ex) when (ex is IOException or InvalidOperationException or TimeoutException or System.Text.Json.JsonException)
        {
            NoticeBar.Severity = InfoBarSeverity.Error;
            NoticeBar.Message = ex.Message;
        }
        finally
        {
            _sending = false;
            UpdateStatus(App.Service.Status);
        }
    }

    private void UpdateStatus(UiPipeClient.UiStatus? status)
    {
        var connected = status?.PhoneConnected == true;
        PasswordStatus.Visibility = status is null || !connected
            ? Visibility.Collapsed
            : Visibility.Visible;
        if (status is not null && connected)
        {
            PasswordStatus.SetStatus(
                status.PhoneHasPassword ? "已保存" : "未保存",
                status.PhoneHasPassword ? StatusTone.Positive : StatusTone.Neutral);
        }
        HelpText.Text = connected
            ? "请填写 Windows 账户密码，不是 PIN 码。"
            : "手机未连接，请打开手机上的 Utochpad。";
        SendButton.IsEnabled = !_sending && connected && !string.IsNullOrEmpty(PasswordInput.Password);
        SendButton.Content = _sending ? "等待手机确认…" : "发送到手机";
        PasswordInput.IsEnabled = !_sending;
    }
}

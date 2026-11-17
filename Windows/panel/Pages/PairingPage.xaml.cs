using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using QRCoder;
using Utochpad.Panel.Controls;
using Utochpad.Panel.Services;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage.Streams;

namespace Utochpad.Panel.Pages;

public sealed partial class PairingPage : Page
{
    private readonly DispatcherQueueTimer _expiryTimer;
    private bool _pairing;
    private bool _busy;
    private string _manualCode = "";
    private int _pairVersionAtStart;
    private int _pairingEpochAtStart;
    private DateTimeOffset _expiresAt;

    public PairingPage()
    {
        InitializeComponent();
        NavigationCacheMode = Microsoft.UI.Xaml.Navigation.NavigationCacheMode.Required;
        _expiryTimer = DispatcherQueue.CreateTimer();
        _expiryTimer.Interval = TimeSpan.FromSeconds(1);
        _expiryTimer.Tick += OnExpiryTimerTick;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        App.Service.Updated += OnServiceUpdated;
        UpdateStatus(App.Service.Status);
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) =>
        App.Service.Updated -= OnServiceUpdated;

    private void OnServiceUpdated(object? sender, EventArgs e)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            var status = App.Service.Status;
            UpdateStatus(status);

            if (!_pairing)
                return;
            if (status is null)
            {
                ResetPairing();
            }
            else if (App.Service.PairingEpoch != _pairingEpochAtStart)
            {
                ResetPairing();
            }
            else if (status.PairVersion != _pairVersionAtStart)
            {
                ResetPairing();
                ShowNotice("配对成功。", InfoBarSeverity.Success);
            }
        });
    }

    private async void OnPairButtonClick(object sender, RoutedEventArgs e)
    {
        if (_busy)
            return;

        if (_pairing)
        {
            _busy = true;
            UpdateStatus(App.Service.Status);
            try
            {
                await App.Service.CancelPairingAsync();
            }
            catch (Exception ex) when (ex is IOException or InvalidOperationException or TimeoutException or System.Text.Json.JsonException)
            {
                ShowNotice(ex.Message, InfoBarSeverity.Error);
            }
            finally
            {
                _busy = false;
                ResetPairing();
                UpdateStatus(App.Service.Status);
            }
            return;
        }

        _busy = true;
        UpdateStatus(App.Service.Status);
        NoticeBar.IsOpen = false;

        try
        {
            var info = await App.Service.BeginPairingAsync();
            _manualCode = info.ManualCode;
            _pairVersionAtStart = info.PairVersion;
            _pairingEpochAtStart = App.Service.PairingEpoch;
            _expiresAt = info.ExpiresAt;
            ManualCodeBox.Text = _manualCode;

            using var generator = new QRCodeGenerator();
            using var qrData = generator.CreateQrCode(info.QrJson, QRCodeGenerator.ECCLevel.Q);
            var png = new PngByteQRCode(qrData).GetGraphic(12);
            await SetQrImageAsync(png);

            _pairing = true;
            HintText.Text = "用手机 Utochpad App 扫描二维码。";
            UpdatePairingVisibility();
            _expiryTimer.Start();
        }
        catch (Exception ex) when (ex is IOException or InvalidOperationException or TimeoutException or System.Text.Json.JsonException)
        {
            ResetPairing();
            ShowNotice(ex.Message, InfoBarSeverity.Error);
        }
        finally
        {
            _busy = false;
            UpdateStatus(App.Service.Status);
        }
    }

    private void OnCopyCodeClick(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrEmpty(_manualCode))
            return;

        try
        {
            var data = new DataPackage();
            data.SetText(_manualCode);
            Clipboard.SetContent(data);
            ShowNotice("已复制配对码。", InfoBarSeverity.Success);
        }
        catch (Exception ex) when (
            ex is InvalidOperationException or UnauthorizedAccessException or System.Runtime.InteropServices.COMException)
        {
            ShowNotice($"复制失败，请手动选择文本复制：{ex.Message}", InfoBarSeverity.Error);
        }

    }

    private async Task SetQrImageAsync(byte[] png)
    {
        using var stream = new InMemoryRandomAccessStream();
        using var writer = new DataWriter(stream);
        writer.WriteBytes(png);
        await writer.StoreAsync();
        writer.DetachStream();
        stream.Seek(0);

        var bitmap = new BitmapImage();
        await bitmap.SetSourceAsync(stream);
        QrImage.Source = bitmap;
    }

    private void OnExpiryTimerTick(DispatcherQueueTimer sender, object args)
    {
        if (!_pairing)
            return;
        if (DateTimeOffset.UtcNow >= _expiresAt)
        {
            ResetPairing();
            HintText.Text = "二维码已过期，请重新配对。";
            return;
        }

        ExpiryText.Text = $"有效期至 {_expiresAt.ToLocalTime():HH:mm:ss}";
    }

    private void UpdateStatus(UiPipeClient.UiStatus? status)
    {
        PairStatus.SetStatus(
            _pairing
                ? "等待扫码"
                : status is null
                    ? "检查中"
                    : status.Paired ? "已配对" : "未配对",
            _pairing
                ? StatusTone.Caution
                : status is null
                    ? StatusTone.Neutral
                    : status.Paired ? StatusTone.Positive : StatusTone.Neutral);
        ConnectionStatus.SetStatus(
            status is null
                ? "检查中"
                : status.PhoneConnected ? "手机已连接" : "手机未连接",
            status is null
                ? StatusTone.Neutral
                : status.PhoneConnected ? StatusTone.Positive : StatusTone.Neutral);
        PairButton.Content = _busy
            ? "请稍候…"
            : _pairing
                ? "取消配对"
                : status?.Paired == true ? "重新配对" : "开始配对";
        PairButton.IsEnabled = !_busy && (_pairing || status is not null);
    }

    private void UpdatePairingVisibility()
    {
        QrImage.Visibility = _pairing ? Visibility.Visible : Visibility.Collapsed;
        QrPlaceholder.Visibility = _pairing ? Visibility.Collapsed : Visibility.Visible;
        ExpiryText.Visibility = _pairing ? Visibility.Visible : Visibility.Collapsed;
        ManualCodeHint.Visibility = _pairing ? Visibility.Visible : Visibility.Collapsed;
        ManualCodeBox.Visibility = _pairing ? Visibility.Visible : Visibility.Collapsed;
        CopyCodeButton.Visibility = _pairing ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ResetPairing()
    {
        _pairing = false;
        _manualCode = "";
        _expiryTimer.Stop();
        QrImage.Source = null;
        ManualCodeBox.Text = "";
        ExpiryText.Text = "";
        UpdatePairingVisibility();
        UpdateStatus(App.Service.Status);
    }

    private void ShowNotice(string message, InfoBarSeverity severity)
    {
        NoticeBar.Message = message;
        NoticeBar.Severity = severity;
        NoticeBar.IsOpen = true;
    }
}

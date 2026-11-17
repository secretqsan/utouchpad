using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Utochpad.Panel.Controls;

public enum StatusTone
{
    Neutral,
    Positive,
    Caution,
    Critical,
}

public sealed partial class StatusIndicator : UserControl
{
    public StatusIndicator()
    {
        InitializeComponent();
    }

    public string Text
    {
        get => Label.Text;
        set => Label.Text = value;
    }

    public void SetStatus(string text, StatusTone tone)
    {
        Label.Text = text;
        Dot.Fill = (Brush)Resources[$"Status{tone}Brush"];
    }
}

using Serilog;
using Serilog.Events;
using Utochpad.Service;
using Utochpad.Service.Protocol;

var paths = new DataPaths();
var config = ServiceConfig.Load(paths.DataDirectory);

Log.Logger = new LoggerConfiguration()
    .MinimumLevel.Information()
    .MinimumLevel.Override("Microsoft.AspNetCore", LogEventLevel.Warning)
    .Enrich.FromLogContext()
    .WriteTo.Console()
    .WriteTo.File(
        paths.ServiceLogPath,
        rollingInterval: RollingInterval.Day,
        retainedFileCountLimit: 14,
        shared: true)
    .CreateLogger();

try
{
    Log.Information("Utochpad service starting (data: {DataDir})", paths.DataDirectory);

    var builder = WebApplication.CreateBuilder(new WebApplicationOptions
    {
        Args = args,
        ContentRootPath = AppContext.BaseDirectory,
    });

    builder.Host.UseWindowsService(options => options.ServiceName = UtochpadConstants.ServiceName);
    // Clear the default providers first; UseSerilog installs the Serilog
    // provider and must run after it.
    builder.Logging.ClearProviders();
    builder.Host.UseSerilog();

    builder.Services.AddSingleton(paths);
    builder.Services.AddSingleton(config);
    builder.Services.AddSingleton<ViiperManager>();
    builder.Services.AddSingleton<PhoneHub>();
    builder.Services.AddHostedService<CpPipeServer>();
    builder.Services.AddHostedService<UiPipeServer>();

    builder.WebHost.ConfigureKestrel(kestrel =>
    {
        kestrel.ListenAnyIP(config.Port);
        kestrel.AddServerHeader = false;
    });

    var app = builder.Build();

    app.UseWebSockets(new WebSocketOptions
    {
        KeepAliveInterval = TimeSpan.FromSeconds(20),
    });

    app.Map("/ws", async context =>
    {
        if (!context.WebSockets.IsWebSocketRequest)
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }

        var hub = context.RequestServices.GetRequiredService<PhoneHub>();
        using var socket = await context.WebSockets.AcceptWebSocketAsync();
        await hub.HandleWebSocketAsync(socket, context.RequestAborted);
    });

    var viiper = app.Services.GetRequiredService<ViiperManager>();
    app.Lifetime.ApplicationStarted.Register(() =>
    {
        _ = Task.Run(async () =>
        {
            try
            {
                await viiper.StartAsync();
            }
            catch (Exception ex)
            {
                Log.Error(ex, "Failed to start VIIPER at service startup");
            }
        });
    });

    Log.Information("Listening for phone connections on port {Port}", config.Port);
    await app.RunAsync();
}
catch (Exception ex)
{
    Log.Fatal(ex, "Utochpad service terminated unexpectedly");
    Environment.ExitCode = 1;
}
finally
{
    await Log.CloseAndFlushAsync();
}

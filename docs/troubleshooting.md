# 故障排查

日志统一位于 `%ProgramData%\Utochpad\logs`（`service-*.log`、`cp-*.log`）。该目录默认只允许 SYSTEM 与管理员读取，请用管理员权限的终端查看：

```powershell
Get-Content "$env:ProgramData\Utochpad\logs\service-*.log" -Tail 80
```

## 锁屏看不到 Utochpad 磁贴

- 确认以管理员身份运行过安装包或 `install.ps1`，且 `HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{FDF19A17-3800-34D6-B49A-CAE0D53ACA8F}` 存在。
- 确认 `%ProgramFiles%\Utochpad\CP\Utochpad.CredentialProvider.comhost.dll` 存在，并安装了 .NET 10 Runtime。
- 注销或重启后再看；LogonUI 会缓存已加载的 CP。

## 控制面板没有安装或无法启动

- `install.ps1` 默认使用 bun + Rust 构建 Tauri 面板；缺少工具链时会提示。可安装 bun 与 Rust（MSVC toolchain）后重试，或用 `-PanelExe` 指定已构建的 `Utochpad.exe`。
- 确认 `%ProgramFiles%\Utochpad\Panel\Utochpad.exe` 存在；Windows 11 自带 WebView2 Runtime，未自带 WebView2 的系统需要单独安装。
- 查看 `%ProgramData%\Utochpad\logs\service-*.log` 中的服务侧错误。

## 面板显示“服务未运行”

```powershell
Get-Service UtochpadService
Get-Content "$env:ProgramData\Utochpad\logs\service-*.log" -Tail 50
```

## 锁屏凭据提供程序显示“Utochpad 服务未运行”，但服务确实在运行

- 这几乎总是 CP 打不开 `\\.\pipe\utochpad-cp`，而不是服务进程没启动。CP 通常以 SYSTEM 身份加载进 LogonUI，但部分配置下会以当前交互用户的身份运行。管道的 DACL 为 SYSTEM/Administrators 完全控制、Authenticated Users 读写，连接超时为 5 秒；安装不完整或服务未重新注册时，CP 仍可能在 `Connect` 阶段失败，只能显示“服务未运行”。
- 请以管理员身份重新运行安装包或 `install.ps1`（或至少重新安装服务）后再试。
- 服务还会拒绝来自非活动控制台会话的连接（远程桌面或快速用户切换下的其他会话）。如果是在 RDP 会话里解锁，请在控制台会话中操作。
- 仍失败时，CP 会在 `%ProgramData%\Utochpad\logs\cp-*.log` 里记录管道名、用户名与会话 ID 的异常，可按该信息继续排查。

## 手机连不上电脑

- **Android 16/17 的“本地网络”权限**：从 Android 16 开始，应用访问 192.168.x.x 等局域网地址需要 `ACCESS_LOCAL_NETWORK` 权限；未授权时系统会静默丢弃数据包，表现为约 6 秒的连接超时，但手机浏览器却能打开电脑端口。
  - 首次启动 App 时请允许“本地网络 / 附近的设备”权限；
  - 若已拒绝：设置 → 应用 → Utochpad → 权限 → 本地网络（或“附近的设备”）→ 允许。
- 手机与电脑是否同一网段；路由器是否开启 AP 隔离/访客网络。
- 防火墙是否放行 TCP 42800（专用/域网络）。
- 在面板上重新“开始配对”，确认二维码中的 IP 是电脑当前局域网地址。
- 手机 App 必须在前台；切后台会主动断开。

## 触控板 / Radial 无法启动

- 是否安装了 usbip-win2 驱动。
- 面板里点“重启 VIIPER”，或看 `service-*.log` 中 `viiper:` 开头的行。
- 触控板的 `ptp` 与旋钮的 `radial` 会同时挂到同一条虚拟总线上；总线在进入输入页时创建一次，断开手机或退出时销毁。
- 手势不生效时，先确认 Windows 已把设备识别为精密触控板：日志出现 `Windows accepted the precision touchpad device`，或 VIIPER 设备状态中 `ptpActive=true`。
- 只有指针能动、双指滚动或三指手势没反应，通常说明报告描述符里后续触点没有重新声明 `USAGE_PAGE (Digitizers)`（见 [architecture.md](architecture.md#触控板--radial) 中关于 `USAGE_PAGE (Digitizers)` 的说明）。

## 发送密码失败

- 手机是否已配对并连接（面板“手机”状态为绿色）。
- 手机上是否已录入指纹；系统指纹不可用时无法保存/解出密码。
- 若手机提示“指纹已变更”，重新在面板发送一次密码即可。

## 解除配对后手机仍显示旧电脑

- 手机端“设置 → 解除配对”会清空本机配对与密码；电脑端“解除配对”会删除服务端记录。两端都执行一次最干净。
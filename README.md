<p align="center">
  <img src="brand/utochpad-logo.svg" width="128" alt="Utochpad">
</p>

<h1 align="center">Utochpad</h1>

<p align="center">
  <strong>把 Android 手机变成 Windows 的指纹解锁器和精密触控板。</strong>
</p>

<p align="center">
  电脑亮着，它就是一块 Precision Touchpad，外加一枚 Surface Dial；锁屏时，它还是你的指纹。
</p>

<p align="center">
  <a href="#为什么是-utochpad">为什么是 Utochpad</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#使用">使用</a> ·
  <a href="docs/architecture.md">工作原理</a> ·
  <a href="docs/building.md">从源码构建</a> ·
  <a href="#故障排查">故障排查</a>
</p>

---

## 这是什么

Utochpad 由两部分组成：一个 Windows 服务和驱动组件，以及一个 Android 应用。手机与电脑在同一局域网内直连，电脑端不需要任何手机厂商的客户端、云账号或互联网连接。

它把手机接入了 Windows 最底层的输入路径：

- **指纹解锁**——锁屏界面上出现 Utochpad 磁贴，点击后手机弹出指纹，验证通过即完成登录。
- **精密触控板**——App 在前台时整屏就是触控板（固定横屏）。每一根手指的原始触点都被原样交给 Windows，双指滚动、捏合、三指切换桌面等手势全部由 Windows 自己识别。
- **Surface Dial**——屏幕右侧的旋钮作为 Windows Radial 控制器使用，可调音量、画笔大小或任意支持 Radial 的应用。

## 为什么是 Utochpad

| | |
| --- | --- |
| 🔐 **密码只留在手机** | Windows 密码用指纹密钥加密保存，电脑端不落盘；没有你的指纹，密码无法被取出。 |
| 🖐 **不是模拟手势，是真实触控板** | 手机发送标准 PTP 报文，Windows 把它当作精密触控板。手势能力与实体触控板一致，而不是在 App 里猜手势。 |
| 🎛 **真的能用 Surface Dial** | 基于 Windows Radial Controller 协议，不是自定义快捷键。 |
| 🔒 **端到端加密** | ECDH 配对、AES-256-GCM 会话加密、严格计数器，局域网内也不裸奔。 |
| 🧩 **无需 root，无需刷机** | 手机端就是普通 APK；电脑端通过 VIIPER + usbip-win2 提供虚拟 USB 设备。 |
| 🇨🇳 **中文安装向导** | 自动安装服务、凭据提供程序、防火墙规则，并提示缺失的系统组件。 |

## 快速开始

### 准备

- Windows 11 x64（安装与解锁需要管理员/系统权限）
- Android 8.1（API 27）及以上，已录入指纹
- 手机与电脑处于同一局域网，路由器未开启 AP 隔离

> 触控板与旋钮需要电脑安装 [usbip-win2](https://github.com/vadimgrn/usbip-win2) 驱动；解锁功能需要 .NET 10 Runtime。安装包会检测并提示。

### 安装

在仓库根目录运行：

```powershell
.\Windows\installer\build-installer.ps1
```

生成的 `.build\installer\Utochpad-Setup-<version>.exe` 是带中文向导的安装包，运行它会注册服务、凭据提供程序和控制面板；无人值守安装用 `/VERYSILENT /SUPPRESSMSGBOXES`，卸载在“设置 → 应用”里。

详细的构建要求、参数以及 VIIPER、控制面板和 Android APK 的构建方式见 [docs/building.md](docs/building.md)。

### 配对

1. 打开开始菜单里的 **Utochpad** 控制面板，确认服务状态正常。
2. 点击 **开始配对**，用手机 App 扫描二维码（扫不了也可以粘贴配对码）。
3. 配对成功后，在面板中输入当前 Windows 账户的**密码**并发送；手机验证指纹后保存。

### 解锁与输入

1. 按 `Win+L` 锁定 Windows，点击锁屏上的 **Utochpad** 磁贴。
2. 手机 App 保持前台，指纹通过后自动登录。
3. 登录后手机保持连接，直接继续当触控板用——连接只取决于 App 是否在前台，与锁屏状态无关。

## 使用

连上电脑后，触控板与旋钮会一直可用，和 Windows 是否锁屏无关；只有在手机 App 切到后台、网络中断或解除配对时才会断开。

**触控板**

- 单指移动，双指滚动/捏合，三指及以上手势交给 Windows。
- 没有左/右键实体按键：点击由 Windows 从触点流中识别，和实体触控板一致。
- 声明的物理表面约为横屏手机的 **6.40 × 3.00 英寸**。

**旋钮**

- 位于屏幕右侧、上下居中。
- 按住即按下 Radial 按钮，绕中心旋转调节，松开确认。

**设置**

- 右上角“设置”打开独立设置页；打开或返回设置页不会断开电脑连接。

## 控制面板

控制面板是 Utochpad 在 Windows 上的桌面入口，也是 C# 服务的本地管理客户端。它不参与解锁，负责：

- **配对手机**：显示配对与连接状态，生成二维码和手动配对码，可复制、取消或重新配对。
- **Windows 密码**：通过命名管道把密码发送到手机，等待手机指纹确认。
- **维护**：查看服务与输入组件状态、端口、地址、数据目录与日志目录，重启输入组件或解除配对。
- **关于**：查看应用说明与控制面板、后台服务版本。

## 安全

- 局域网内的所有业务数据都用 **AES-256-GCM** 加密；配对使用 ECDH 与双向 HMAC 证明。
- Windows 密码**只在手机**保存，由 Android Keystore 中的指纹密钥保护；电脑端不落盘。
- 配对 token 一次性、5 分钟有效；手机校验服务公钥，防止连到假服务器。
- 未配对的设备无法建立会话；服务只接受活动控制台会话的解锁请求。

完整的安全模型与边界见 [docs/security.md](docs/security.md)。

## 工作原理

手机把原始触点和旋钮旋转加密后发给电脑，电脑通过 VIIPER + usbip-win2 把它们变成两块标准 USB HID 设备。Windows 因此用原生触控板与 Radial 栈识别手势，而不是在应用层模拟。协议与实现细节见 [docs/architecture.md](docs/architecture.md)。

## 已知限制

- 手机 App 仅在前台工作，锁屏或切到后台即断开会话（Android 生物识别与隐私限制）。
- 触控板手势由 Windows 解释，不同 Windows 版本的手势集合可能略有差异。
- 依赖 VIIPER 与 usbip-win2；Windows 大版本更新后如失效，优先更新这两个组件。
- 不支持多手机、多用户或公网远程访问。
- 首次配对要求手机能直接访问电脑的局域网 IP。
- 解锁需要账户**密码**（本地账户或 Microsoft 账户），Windows Hello PIN 不适用。

## 故障排查

日志位于 `%ProgramData%\Utochpad\logs`（`service-*.log`、`cp-*.log`），默认只允许 SYSTEM 与管理员读取，请用管理员权限的终端查看：

```powershell
Get-Content "$env:ProgramData\Utochpad\logs\service-*.log" -Tail 80
```

### 锁屏看不到 Utochpad 磁贴

- 确认以管理员身份运行过安装包，且 `HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{FDF19A17-3800-34D6-B49A-CAE0D53ACA8F}` 存在。
- 确认 `%ProgramFiles%\Utochpad\CP\Utochpad.CredentialProvider.comhost.dll` 存在，并安装了 .NET 10 Runtime。
- 注销或重启后再看；LogonUI 会缓存已加载的 CP。

### 控制面板没有安装或无法启动

- `%ProgramFiles%\Utochpad\Panel\Utochpad.exe` 缺失时重新运行安装包。
- Windows 11 自带 WebView2 Runtime，未自带的系统需要单独安装。
- 查看 `%ProgramData%\Utochpad\logs\service-*.log` 中的服务侧错误。

### 面板显示“服务未运行”

```powershell
Get-Service UtochpadService
Get-Content "$env:ProgramData\Utochpad\logs\service-*.log" -Tail 50
```

### 锁屏凭据提供程序显示“Utochpad 服务未运行”，但服务确实在运行

- 多数是 CP 打不开 `\\.\pipe\utochpad-cp`（服务未重新注册、安装不完整，或 CP 没有以 SYSTEM 身份加载），而不是服务进程没起来。以管理员身份重跑安装包后再试。
- 服务会拒绝非活动控制台会话（远程桌面、快速用户切换下的其他会话），在 RDP 里解锁请在控制台会话操作。
- 仍失败时看 `%ProgramData%\Utochpad\logs\cp-*.log`，里面有管道名、用户名与会话 ID。

### 手机连不上电脑

- **Android 16/17 的“本地网络”权限**：从 Android 16 开始，应用访问 192.168.x.x 等局域网地址需要 `ACCESS_LOCAL_NETWORK` 权限；未授权时系统会静默丢弃数据包，表现为约 6 秒的连接超时，但手机浏览器却能打开电脑端口。
  - 首次启动 App 时请允许“本地网络 / 附近的设备”权限；
  - 若已拒绝：设置 → 应用 → Utochpad → 权限 → 本地网络（或“附近的设备”）→ 允许。
- 手机与电脑是否同一网段；路由器是否开启 AP 隔离/访客网络。
- 防火墙是否放行 TCP 42800（专用/域网络）。
- 在面板上重新“开始配对”，确认二维码中的 IP 是电脑当前局域网地址。
- 手机 App 必须在前台；切后台会主动断开。

### 触控板 / Radial 无法启动

- 是否安装了 usbip-win2 驱动。
- 面板里点“重启 VIIPER”，或看 `service-*.log` 中 `viiper:` 开头的行。
- 手势不生效时，先在设备管理器确认 Windows 已把它识别为精密触控板。

### 发送密码失败

- 手机是否已配对并连接（面板“手机”状态为绿色）。
- 手机上是否已录入指纹；系统指纹不可用时无法保存/解出密码。
- 若手机提示“指纹已变更”，重新在面板发送一次密码即可。

### 解除配对后手机仍显示旧电脑

- 手机端“设置 → 解除配对”会清空本机配对与密码；电脑端“解除配对”会删除服务端记录。两端都执行一次最干净。

## 第三方组件

- [VIIPER](https://github.com/Alia5/VIIPER) — 虚拟 USB 设备服务器（GPLv3）。自定义设备源码位于 `Windows/viiper/`，与固定版本的上游源码一起编译、分发。
- [Lithnet.CredentialProvider](https://github.com/lithnet/windows-credential-provider) — 凭据提供程序封装，以 git 子模块引入。
- [usbip-win2](https://github.com/vadimgrn/usbip-win2) — Windows 的 USB/IP 驱动。
- [QRCoder](https://github.com/codebude/QRCoder)、[OkHttp](https://square.github.io/okhttp/)、[ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded)。

## 图标与版权

- 品牌标识：`brand/utochpad-logo.svg`。
- 锁屏磁贴图标以 Microsoft 的 [Fluent UI System Icons](https://github.com/microsoft/fluentui-system-icons) 为基础重新组合：

```
Fluent UI System Icons
Copyright (c) 2020 Microsoft Corporation
Licensed under the MIT License.
```

MIT 全文随安装包分发，见 `Windows/installer/THIRD-PARTY-NOTICES.txt`。MIT 不授予商标许可；Utochpad 与 Microsoft 无关联，`Windows`、`Surface`、`Segoe`、`Fluent` 为其商标。
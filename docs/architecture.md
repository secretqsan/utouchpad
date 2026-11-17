# 工作原理

组件划分、配对与会话协议、解锁流程，以及触控板和旋钮如何表现为真实 USB 设备。

## 组件划分

```
┌─────────────────────────── Windows ────────────────────────────┐
│                                                                │
│  LogonUI ── 凭据提供程序 (CP, x64 COM)                          │
│      │  named pipe: utochpad-cp                                │
│      ▼                                                         │
│  UtochpadService (SYSTEM 服务)                                  │
│      ├── Kestrel WebSocket :42800  ◄──── 手机 (局域网)           │
│      ├── named pipe: utochpad-ui  ◄──── 控制面板                │
│      └── ViiperManager ──► viiper.exe (127.0.0.1:32420/32421)   │
│                                    │                           │
│                                    └── USB/IP ──► usbip-win2    │
│                                                   │            │
│                                    同一虚拟总线上：PTP + Radial  │
└────────────────────────────────────────────────────────────────┘
```

| 目录 | 作用 |
| --- | --- |
| `Windows/Service/Utochpad.CredentialProvider` | 凭据提供程序，在锁屏/登录界面显示用户磁贴 |
| `Windows/Service/Utochpad.Service` | SYSTEM 服务，持有手机连接、CP IPC、VIIPER 进程与虚拟设备 |
| `Windows/Service/Utochpad.Shared` | PC 端共享的协议常量、CP 管道消息与 IPC 工具 |
| `Windows/Service/Lithnet.CredentialProvider` | 凭据提供程序封装，git 子模块 |
| `Windows/panel` | C# / WinUI 3 控制面板：配对二维码、推送密码、维护与关于 |
| `Windows/viiper` | 对 VIIPER 的自定义设备（`ptp`、`radial`），构建时覆盖到上游最新源码 |
| `Windows/installer` | Inno Setup 安装包（`Utochpad.iss`、`build-installer.ps1`）与 `build-viiper.ps1` |
| `Android` | Compose 手机应用：配对、指纹解锁、横屏触控板 + Radial、独立设置页（除触控板外均由 Miuix 实现） |
| `brand` | 品牌资源 |

| 组件 | 技术 |
| --- | --- |
| Android 应用 | Kotlin · Jetpack Compose · Miuix · Android Keystore |
| Windows 服务 | C# · .NET 10 · LocalSystem Windows 服务 |
| 凭据提供程序 | COM · Lithnet.CredentialProvider · .NET 10 |
| 控制面板 | C# · WinUI 3 · Windows App SDK |
| 虚拟 USB 设备 | Go · VIIPER · usbip-win2 |

服务以 `LocalSystem` 运行，因此锁屏界面（LogonUI，SYSTEM 会话）也能通过命名管道请求手机验证。控制面板只是普通用户界面，不参与解锁。

## 配对

1. 服务在首次启动时生成一对 **P-256 ECDH** 密钥，私钥用 DPAPI（LocalMachine）保护，保存在 `%ProgramData%\Utochpad\config.json`。
2. 控制面板点击“开始配对”，服务生成 5 分钟有效的一次性 token，并返回二维码内容：

   ```json
   {"v":1,"name":"PC-NAME","hosts":["192.168.1.10"],"port":42800,"pub":"<SPKI base64>","token":"..."}
   ```

   二维码无法扫描时，也可以复制同一段 JSON 的 base64url 编码手动粘贴。
3. 手机生成自己的 P-256 密钥，双方用 ECDH 得到配对密钥 `pairKey = HKDF-SHA256(ECDH(shared), salt="utochpad-pair-v1", info="utochpad-pair-key", 32)`。
4. 手机发送 `pair` 请求，附上 `HMAC(pairKey, "pair|" + token)` 作为持有证明；服务校验 token 与证明后保存手机公钥，并返回自己的公钥。手机核对返回的公钥与二维码中的一致，防止被重定向到假服务器。

## 会话

```
手机 → hello    {deviceId, nonceP, passwordSet}
服务 → challenge{nonceS, HMAC(pairKey, "server|nonceP|nonceS")}
手机 → auth     {HMAC(pairKey, "client|nonceP|nonceS")}
服务 → ready
```

之后所有消息都是二进制帧：`12 字节计数器 nonce || AES-256-GCM 密文 || 16 字节 tag`，明文为 `[类型 1 字节][内容]`，类型 1 = JSON 控制消息、2 = 32 字节 PTP、3 = 3 字节 Radial。两个方向使用不同会话密钥（HKDF，salt = `nonceP || nonceS`），计数器从 0 递增并严格校验，乱序或重放会直接断开连接。

## 解锁

1. 用户在锁屏点击 Utochpad 磁贴 → CP 的 `GetCredentials()` 通过 `utochpad-cp` 管道请求服务。
2. 服务向已连接手机发送 `unlockRequest`。
3. 手机检查自己在前台，然后用 **Android Keystore 指纹密钥**解开保存的密码，发送 `unlockResponse`。
4. 服务通过管道返回密码，CP 用 `SecureString` 构造 `CredentialResponseSecure` 完成登录。

## 控制面板（UI 管道）

面板是服务的本地管理客户端，不包含服务、凭据提供程序或解锁逻辑，通过 `\\.\pipe\utochpad-ui` 通信：

- 换行分隔 JSON，每次连接一请求一响应，字段为 camelCase。
- 方法：`status`、`pair.begin`、`pair.cancel`、`pair.unpair`、`password.set`、`viiper.restart`。

## 触控板 / Radial

手机固定横屏，只有一页：整屏触控板 + 右侧垂直居中的旋钮。连接后手机发送一次 `enterMode`，模式为 `touchpad+radial`。服务在**同一条虚拟 USB 总线**上创建两个设备，并各自保持一条控制流：

- `ptp`：Windows Precision Touchpad，最多 5 个同时触点；
- `radial`：Windows Radial Controller（Surface Dial），按键 + 相对旋转。

两个设备由 VIIPER 自动附加到 usbip-win2，因此手指和旋钮可以同时使用。之后手机直接发送原始设备帧：PTP 触点快照（最多 5 个触点）与 Radial 相对旋转量。

PTP 帧是**完整状态快照**：手机每次采样都发出所有手指的 ID、坐标和 Tip/Confidence，服务不做手势识别也不改写触点。点击、滚动、捏合、三指切换桌面等手势全部由 Windows 从触点流判定。

输入模式与锁屏状态无关：`enterMode` 只要求手机已连接，锁屏、登录、切换用户都不会拆除设备；只有手机断线或解除配对时 `PhoneHub` 才调用 `LeaveAsync`。

界面没有左/右键实体按键，点击由 Windows 从触点流判定。声明的物理表面约为横屏手机的 **6.40 × 3.00 英寸**。设备侧的帧格式与不可随意改动的时序约束见 `Windows/viiper/device/ptp`、`Windows/viiper/device/radial` 的源码注释。

## 端口、文件与日志

| 项目 | 值 |
| --- | --- |
| 手机 WebSocket | `ws://<PC>:42800/ws`（入站 TCP 42800） |
| VIIPER API | `127.0.0.1:32420` |
| VIIPER USB/IP | `127.0.0.1:32421` |
| CP 管道 | `\\.\pipe\utochpad-cp`（SYSTEM/Administrators 完全控制，Authenticated Users 读写） |
| UI 管道 | `\\.\pipe\utochpad-ui`（Authenticated Users） |
| 数据目录 | `%ProgramData%\Utochpad` |
| 配置 | `%ProgramData%\Utochpad\config.json` |
| 日志 | `%ProgramData%\Utochpad\logs\service-*.log`、`cp-*.log` |

日志目录默认只允许 SYSTEM 与管理员读取，查看时请用管理员权限的终端：

```powershell
Get-Content "$env:ProgramData\Utochpad\logs\service-*.log" -Tail 80
```

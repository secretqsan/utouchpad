# 工作原理

本文是 Utochpad 的内部设计说明：组件划分、配对与会话协议、解锁流程，以及触控板 / Radial 如何在 Windows 上表现为真实 USB 设备。面向想了解实现细节或参与开发的读者。

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
| `Windows/Service/Lithnet.CredentialProvider` | 凭据提供程序封装，git 子模块（`lithnet/windows-credential-provider`） |
| `Windows/Service/Directory.Build.targets` | 仓库级 MSBuild 覆盖（Lithnet NuGet 输出路径） |
| `Windows/panel` | Tauri 2 + Vue 3 控制面板：配对二维码、推送密码、维护与关于 |
| `Windows/viiper` | 对 VIIPER 的自定义设备（`ptp`、`radial`），构建时覆盖到固定版本的上游源码 |
| `Windows/installer` | Inno Setup 安装包（`Utochpad.iss`、`build-installer.ps1`）与开发用 `install.ps1` / `uninstall.ps1` |
| `Windows/installer/build-viiper.ps1` | 下载固定版本 VIIPER、应用覆盖层并编译 `viiper.exe` |
| `Android` | Compose 手机应用：配对、指纹解锁、横屏触控板 + Radial、独立设置页 |

服务以 `LocalSystem` 运行，因此锁屏界面（LogonUI，SYSTEM 会话）也能通过命名管道请求手机验证。控制面板只是普通用户界面，不参与解锁。

## 配对

1. 服务在首次启动时生成一对 **P-256 ECDH** 密钥，私钥用 DPAPI（LocalMachine）保护，保存在 `%ProgramData%\Utochpad\config.json`。
2. 控制面板点击“开始配对”，服务生成 5 分钟有效的一次性 token，并返回二维码内容：

   ```json
   {"v":1,"name":"PC-NAME","hosts":["192.168.1.10"],"port":42800,"pub":"<SPKI base64>","token":"..."}
   ```

   二维码无法扫描时，也可以复制同一段 JSON 的 base64url 编码手动粘贴。
3. 手机扫描后生成自己的 P-256 密钥，双方用 ECDH 得到共享密钥：

   ```
   pairKey = HKDF-SHA256(ECDH(shared), salt="utochpad-pair-v1", info="utochpad-pair-key", 32)
   ```

4. 手机发送 `pair` 请求，附上 `HMAC(pairKey, "pair|" + token)` 作为持有证明；服务校验 token 与证明后保存手机公钥，并返回自己的公钥。手机核对返回的公钥与二维码中的一致，防止被重定向到假服务器。

## 会话

```
手机 → hello    {deviceId, nonceP, passwordSet}
服务 → challenge{nonceS, HMAC(pairKey, "server|nonceP|nonceS")}
手机 → auth     {HMAC(pairKey, "client|nonceP|nonceS")}
服务 → ready
```

之后所有消息都是二进制帧：

```
帧 = 12 字节计数器 nonce || AES-256-GCM 密文 || 16 字节 tag
明文 = [类型 1 字节][内容]
类型：1 = JSON 控制消息，2 = 32 字节 PTP，3 = 3 字节 Radial
```

两个方向使用不同会话密钥（HKDF，salt = `nonceP || nonceS`），计数器从 0 开始并严格校验，乱序/重放会直接断开连接。

## 解锁

1. 用户在锁屏点击 Utochpad 磁贴 → CP 的 `GetCredentials()` 通过 `utochpad-cp` 管道请求服务。
2. 服务向已连接手机发送 `unlockRequest`。
3. 手机检查自己在前台，然后用 **Android Keystore 指纹密钥**解开保存的密码，发送 `unlockResponse`。
4. 服务通过管道返回密码，CP 用 `SecureString` 构造 `CredentialResponseSecure` 完成登录。

## 控制面板（UI 管道）

控制面板是 `UtochpadService` 的本地管理客户端，不包含服务、凭据提供程序或解锁逻辑。它通过 `\\.\pipe\utochpad-ui` 与服务通信：

- 协议：换行分隔 JSON，每次连接一请求一响应，字段为 camelCase。
- 方法：`status`、`pair.begin`、`pair.cancel`、`pair.unpair`、`password.set`、`viiper.restart`。
- C# 侧定义：`Windows/Service/Utochpad.Service/Protocol/UiPipeMessages.cs`。
- 服务未运行时，面板会显示错误并禁用操作。

## 触控板 / Radial

手机固定横屏，只有一页：整屏触控板 + 右侧垂直居中的旋钮。连接后手机发送一次 `enterMode`，模式为 `touchpad+radial`。服务在**同一条虚拟 USB 总线**上创建两个设备，并各自保持一条控制流：

- `ptp`：Windows Precision Touchpad，最多 5 个同时触点；
- `radial`：Windows Radial Controller（Surface Dial），按键 + 相对旋转。

两个设备由 VIIPER 自动附加到 usbip-win2，因此手指和旋钮可以同时使用。之后手机直接发送原始设备帧：

- PTP（32 字节）：`contactCount(1) | buttons(1) | 5 × [id(1) flags(1) x(u16 LE) y(u16 LE)]`
- Radial（3 字节）：`dial(i16 LE, 0.1°) | buttons(1)`

PTP 帧是**完整状态快照**：手机每个采样都把当前所有手指的 ID、坐标和 Tip/Confidence 原样发出，服务不做手势识别，也不改写触点。手机端只负责把 Compose 的原始指针事件编码成 PTP，没有任何点击/滑动/缩放识别器；Windows 收到的是标准多点触控数据，所以导航、点击、滚动、捏合、三指切换桌面等手势与实体精密触控板一致。

触点 ID 在手指接触期间保持稳定，最多 5 个。报告描述符与 Microsoft 的 sample descriptor 一致：Tip/Confidence 后补齐 6 位，再使用完整的 8 位 Contact Identifier（不是把 ID 塞进剩余 3 位的非标准布局）。

**每个手指的 collection 都必须重新声明 `USAGE_PAGE (Digitizers)`。** HID 的 usage page 是全局状态：上一根手指的 X/Y 会把它切到 Generic Desktop，若下一根手指不切回来，它声明的就是 `0x01:0x47`（Confidence）、`0x01:0x42`（Tip）、`0x01:0x51`（Contact Identifier）而不是 `0x0D:0x47/0x42/0x51`。Windows 于是只认第一个触点：指针能动、双指滚动失效、多指手势完全不可能。`descriptor_test.go` 会断言每个 finger collection 都重新声明了 `USAGE_PAGE (Digitizers)`。

手指抬起时，手机先发送一帧 **Tip=0** 的快照并保持约 32ms，再把触点从报告中移除。这样 Windows 能看到明确的“离开”事件，触点也不会残留占用槽位。

**报告节奏。** USBIP 服务器在设备一回答就完成 URB，主机随即重提，于是设备每秒被轮询 1200–2000 次——比端点声明的 `BInterval=1ms` 还快，真实硬件做不到。Windows 的触控板栈按“每 8ms 一帧”调校，喂它两倍于物理上限的帧率会丢帧，而丢到“按下 / Tip 变化”这种只出现一次的过渡帧上，一次点击就没了：实测 100ms 轻点的点击命中率只有 60–78%，且与触点 ID、坐标、时长、Tip=0 保持时间、帧间隔都无关。`ptp` 设备因此按固定周期发放报告（`DefaultScanPeriod = 4ms`，即 250Hz，可用 `VIIPER_PTP_SCAN_MS` 覆盖，0 关闭），并发的中断传输排队后每个周期只服务一个。同一套注入脚本的实测结果：

| 报告周期 | 主机实际帧率 | 轻点命中 | 抬起→点击延迟 |
|---|---|---|---|
| 不节流 | ~1500 Hz | 60–78% | 1–2 ms |
| 1 ms | ~870 Hz | 100% | 4 ms |
| 2 ms | ~515 Hz | 100% | 5 ms |
| 4 ms | ~265 Hz | 100% | 9 ms |
| 8 ms | ~145 Hz | 100% | 20 ms |

选 4ms 是因为手机端本身约 120Hz 出一帧（8.3ms），250Hz 留了两倍余量，不会被手机采样追上；按住 500ms 仍然不算轻点（0/10），与真实触控板一致。

HID 中断端点使用 1ms 轮询间隔（`BInterval=1`），USB 层在手机没有新帧时重复上一帧而不是发空帧，因此指针移动不会因为等待下一帧而停顿。

PTP 快照在整个链路上只允许合并“触点集合相同”的帧：手指只移动位置时，新快照可以直接顶掉尚未发出的旧快照；但触点出现、抬起（Tip 变化）或消失的那一帧必须逐帧送达。Windows 只在看到“触点仍在报告中、Tip 由 1 变为 0”的那一帧时才判定为点击，而这一帧只出现一次——如果被后续帧顶掉（Wi-Fi 把十几毫秒内的帧攒成一批送达时很容易发生），这次点击就丢了。手机发送队列和 VIIPER 的 `ptp` 设备队列都按这个规则合并。手机端因此不需要 250Hz 节流：队列本身已经合并掉冗余的移动帧。

Radial 的拨盘量是**相对值**，所以 VIIPER 的 `radial` 设备在合并待发帧时把 `dial` 相加而不是替换，否则一次旋转会被悄悄吞掉。

界面上没有左/右键实体按键：单指轻点、双指轻点等点击行为由 Windows 从触点流中自行判定。声明的物理表面为横屏手机的约 **6.40 × 3.00 英寸**，与横屏触控区域的比例一致。

设置页是独立的 `SettingsActivity`，打开它不会断开手机会话：控制器挂在 `Application` 上，WebSocket 和虚拟设备随整个应用的前后台状态启停，而不是随某一个 Activity 启停。

VIIPER 为每个上报帧填写基于真实时间的 Scan Time（单位 100µs）。Windows 用它计算触点的移动速度；如果时间戳与实际采样节奏不符，滑动惯性和手势阈值都会失真，因此这里不能用固定的每帧增量。

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

服务、CP 与控制面板的日志都在同一目录。该目录默认只允许 SYSTEM 与管理员读取，查看时请使用管理员权限的终端，例如：

```powershell
Get-Content "$env:ProgramData\Utochpad\logs\service-*.log" -Tail 80
```
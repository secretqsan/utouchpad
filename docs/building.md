# 环境要求与构建

面向从源码构建或参与开发 Utochpad 的读者。只使用发布版安装包的话，看 [README 的快速开始](../README.md#快速开始) 即可。

## 环境要求

### Windows

- Windows 11 x64；安装与卸载需要管理员权限。
- **.NET 10 SDK**（构建）。服务自包含发布；控制面板与凭据提供程序框架依赖发布。目标电脑需自行安装 .NET 10 Runtime、Windows App SDK 2.6.5 Runtime 和 Visual C++ Redistributable；安装器不会检测或安装这些运行时。
- **Go 1.27+**：仅在构建 VIIPER 时需要。
- **Inno Setup 7**：仅在构建安装包时需要，需自行安装：`winget install --id JRSoftware.InnoSetup.7 -e`。构建脚本只查找已安装的 `ISCC.exe`（PATH 与默认安装目录），**不会**下载或随仓库提供 Inno Setup 的安装程序；装在非默认位置时用 `-Iscc` 指定。
- **usbip-win2 驱动**：触控板与旋钮的必要条件，需自行安装：<https://github.com/vadimgrn/usbip-win2>。

### Android

- Android 8.1（API 27）及以上，已录入指纹，与电脑处于同一局域网且路由器未开启 AP 隔离。

## 构建与安装（Windows）

### 安装包（推荐）

在仓库根目录运行：

```powershell
.\Windows\installer\build-installer.ps1
```

输出 `.build\installer\Utochpad-Setup-<version>.exe`。中文向导会写入服务、凭据提供程序、控制面板与 `viiper.exe`，注册服务与 COM 宿主、添加防火墙规则（TCP 42800，专用/域网络），并仅检测 USBip 驱动。构建需要 .NET 10 SDK、Go 1.27+ 与 Inno Setup 7；首次构建会还原 WinUI 依赖并下载 VIIPER 上游源码（默认拉取上游最新 commit），之后复用 `.build\` 缓存。

安装器只检测 USBip 驱动；不会检测、下载或安装 .NET、Windows App SDK、Visual C++ 或其他运行时。目标电脑需在安装前自行准备这些运行时。USBip 驱动本身也需用户单独安装。Inno Setup 是构建机上的前置工具，脚本不会替你下载，安装包本身也不包含它。

常用参数：

```powershell
.\Windows\installer\build-installer.ps1 -PanelPublishDir "D:\build\panel"  # 复用已发布的面板目录
.\Windows\installer\build-installer.ps1 -SkipPanel                         # 复用 .build\panel-publish
.\Windows\installer\build-installer.ps1 -SkipViiper                        # 复用 .build\viiper\viiper.exe
.\Windows\installer\build-installer.ps1 -Version 1.1.0
```

安装包由 `Windows/installer/Utochpad.iss` 定义；第三方组件声明与 GPLv3 文本随安装包一起安装。

### 本地安装（开发机）

先构建安装包，再运行它——仓库没有另外的脚本安装途径：

```powershell
.\Windows\installer\build-installer.ps1
.\.build\installer\Utochpad-Setup-<version>.exe                               # 向导安装
.\.build\installer\Utochpad-Setup-<version>.exe /VERYSILENT /SUPPRESSMSGBOXES  # 无人值守
```

- 安装与卸载都走安装包：卸载在“设置 → 应用 → Utochpad”，会询问是否保留配对数据；命令行可用 `"%ProgramFiles%\Utochpad\unins000.exe" /VERYSILENT`。
- 反复构建时用 `-SkipPanel` / `-SkipViiper` 复用已有产物，省掉面板与 VIIPER 的编译。
- `Windows/Service/Lithnet.CredentialProvider` 是 git 子模块，新克隆后若该目录为空，先执行 `git submodule update --init --recursive`。
- 安装或卸载后建议注销/重启一次，让 LogonUI 重新加载凭据提供程序。

## 构建 VIIPER（含 ptp / radial）

仓库只保存覆盖层（`Windows/viiper/`）。脚本下载上游最新 commit，应用覆盖层后编译：

```powershell
.\Windows\installer\build-viiper.ps1                          # 输出 .build\viiper\viiper.exe（拉取上游最新 commit）
.\Windows\installer\build-viiper.ps1 -Test                    # 同时运行 ptp / radial 设备测试
.\Windows\installer\build-viiper.ps1 -UpstreamSha <完整 SHA>   # 固定到指定 commit
```

- 每次构建都会用 `git ls-remote` 解析上游最新 commit（需要网络与 git）；该 commit 已有 `.build\` 缓存时直接复用。
- 升级 VIIPER：重新构建即拉取上游最新源码；需要固定到某个 commit 时用 `-UpstreamSha <完整 SHA>`。覆盖层是新增文件，不改上游源码。
- 所有构建产物集中在仓库根目录 `.build/`，已忽略，不会进版本库。

## 控制面板（WinUI 3）

开发调试（需要目标机已安装 Windows App SDK 2.6.5 Runtime）：

```powershell
dotnet run --project Windows\panel\Utochpad.Panel.csproj
```

- 面板使用框架依赖发布：只分发应用文件；目标电脑需自行安装 .NET 10 与 Windows App SDK Runtime。
- 安装包构建会生成 `.build\panel-publish\`，并将其中的发布文件安装到 `%ProgramFiles%\Utochpad\Panel`。
- 项目里的 `EnableMsixTooling` 必须保持开启：这是 `dotnet publish` 把 `Utochpad.pri`（内含编译后的 XAML）写进发布目录的前提。缺少它时发布不会报错，但面板启动几秒后会在 `Microsoft.UI.Xaml.dll` 中以 `0xc000027b` 崩溃；构建脚本会检查发布目录是否包含该文件。
- 面板通过 `\\.\pipe\utochpad-ui` 调用 C# 服务，协议见 [architecture.md](architecture.md#控制面板ui-管道)。
- WinUI 3 提供原生 Fluent 控件与系统主题；二维码使用 QRCoder 在本地生成。

## 仅构建（不安装）

```powershell
dotnet build Windows\Service\Utochpad.slnx -c Release -p:Platform=x64
```

## Android APK

用 Android Studio 打开 `Android/`，或命令行：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # 按实际安装位置调整
cd Android
.\gradlew.bat :app:assembleDebug
```

输出 `.build\android\app\outputs\apk\debug\app-debug.apk`（debug 签名，可直装，但不可用于分发）。

### 配置发布签名

发布版必须用自己的密钥签名，且该密钥一旦用于上架就不能更换（否则已安装用户无法升级）。仓库**不包含**密钥，第一次发布前在本机生成并妥善离线备份：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd Android
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore utochpad-release.jks `
  -alias utochpad -keyalg RSA -keysize 2048 -validity 10000
```

然后在 `Android/keystore.properties` 写入密钥信息（相对路径以 `Android/` 为基准；该文件与 `*.jks` 已被 `.gitignore` 忽略，**不要提交**）：

```properties
storeFile=utochpad-release.jks
storePassword=<密钥库口令>
keyAlias=utochpad
keyPassword=<密钥口令>
```

构建发布 APK：

```powershell
cd Android
.\gradlew.bat :app:assembleRelease
```

输出 `.build\android\app\outputs\apk\release\app-release.apk`，已用上述密钥签名。没有 `keystore.properties` 时 `assembleRelease` 仍可执行，但产物**未签名**，不能安装或分发。

密钥与口令丢失后无法再发布同名应用的更新，务必备份 `utochpad-release.jks` 并保管口令。

## 相关文档

- 组件划分与协议：[architecture.md](architecture.md)
- 安装后遇到问题：见 [README 的故障排查](../README.md#故障排查)

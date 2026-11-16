# 环境要求与构建

面向从源码构建或参与开发 Utochpad 的读者。只使用发布版安装包的话，看 [README 的快速开始](../README.md#快速开始) 即可。

## 环境要求

### Windows

- Windows 11 x64；安装与卸载需要管理员权限。
- **.NET 10 SDK**（构建）与 **.NET 10 Runtime**（目标机器）。服务自包含发布，但凭据提供程序必须框架依赖（COM 托管不支持自包含），缺运行时时应用会在运行时提示：`winget install Microsoft.DotNet.Runtime.10`。
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

输出 `.build\installer\Utochpad-Setup-<version>.exe`。中文向导会写入服务、凭据提供程序、控制面板与 `viiper.exe`，注册服务与 COM 宿主、添加防火墙规则（TCP 42800，专用/域网络），并检测 USBip 驱动。构建需要 .NET 10 SDK、bun、Rust（MSVC）、Go 1.27+ 与 Inno Setup 7；首次构建会下载固定版本的 VIIPER 上游源码，之后复用 `.build\` 缓存。

安装包**不内置** WebView2、.NET 10 Runtime 和 USBip 驱动：Windows 11 自带 WebView2，其余缺失时会在运行时或安装完成页提示。Inno Setup 同理：它只是构建机上的前置工具，脚本不会替你下载，安装包本身也不包含它。

常用参数：

```powershell
.\Windows\installer\build-installer.ps1 -PanelExe "D:\build\Utochpad.exe"   # 复用已构建的面板
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

仓库只保存覆盖层（`Windows/viiper/`）。脚本按 `Windows/viiper/VIIPER_VERSION` 下载上游 commit，应用覆盖层后编译：

```powershell
.\Windows\installer\build-viiper.ps1          # 输出 .build\viiper\viiper.exe
.\Windows\installer\build-viiper.ps1 -Test    # 同时运行 ptp / radial 设备测试
```

- 首次构建需要网络，之后复用 `.build\` 缓存。
- 升级 VIIPER：把 `VIIPER_VERSION` 改成新的完整 commit SHA 重新构建即可；覆盖层是新增文件，不改上游源码。
- 所有构建产物集中在仓库根目录 `.build/`，已忽略，不会进版本库。

## 控制面板（Tauri）

开发时：

```powershell
cd Windows\panel
bun install
bun run tauri dev            # 需要 UtochpadService 已运行
bun run build                # 只构建前端到 ../../.build/panel-dist/
bun run tauri build --no-bundle
```

- 需要 Rust 1.85+（MSVC）、bun 与 WebView2 Runtime（Windows 11 自带）。
- 构建产物：`.build\panel-target\release\Utochpad.exe`。`bundle.active=false`，不生成 MSI/NSIS，面板由安装包负责安装。
- 面板通过 `\\.\pipe\utochpad-ui` 调用 C# 服务，协议见 [architecture.md](architecture.md#控制面板ui-管道)。
- 应用图标由 `brand/utochpad-logo.svg` 通过 `bun run tauri icon` 生成；界面固定浅色（Tailwind CSS 4），不做深色模式。

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

# 环境要求与构建

面向从源码构建或参与开发 Utochpad 的读者。只使用发布版安装包的话，看 [README 的快速开始](../README.md#快速开始) 即可。

## 环境要求

### Windows

- Windows 11 x64；安装与卸载需要管理员权限。
- **.NET 10 SDK**（构建）与 **.NET 10 Runtime**（目标机器）。服务自包含发布，但凭据提供程序必须框架依赖（COM 托管不支持自包含），缺运行时时应用会在运行时提示：`winget install Microsoft.DotNet.Runtime.10`。
- **Go 1.27+**：仅在构建 VIIPER 时需要。
- **Inno Setup 7**：仅在构建安装包时需要：`winget install --id JRSoftware.InnoSetup.7 -e`。
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

安装包**不内置** WebView2、.NET 10 Runtime 和 USBip 驱动：Windows 11 自带 WebView2，其余缺失时会在运行时或安装完成页提示。

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

输出 `.build\android\app\outputs\apk\debug\app-debug.apk`。发布前先在 `Android/app/build.gradle.kts` 里配置自己的签名，再执行 `assembleRelease`。

## 相关文档

- 组件划分与协议：[architecture.md](architecture.md)
- 安装后遇到问题：见 [README 的故障排查](../README.md#故障排查)

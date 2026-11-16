# 环境要求与构建

本页面向从源码构建或参与开发 Utochpad 的读者。只使用发布版安装包的话，看 [README 的快速开始](../README.md#快速开始) 即可。

## 环境要求

### Windows

- Windows 11 x64。
- **Go 1.27+**：仅在构建 VIIPER（`Windows/installer/build-viiper.ps1`）时需要。
- **.NET 10 SDK**：用于构建；服务使用自包含发布，但凭据提供程序必须框架依赖（.NET 的 COM 托管不支持自包含），所以目标机器还需要 **.NET 10 Runtime**。
  - 安装：`winget install Microsoft.DotNet.Runtime.10`
  - 安装包不内置运行时；缺少时应用会在运行时提示。
- **usbip-win2 驱动**：触控板与 Radial 的必要条件。安装包会检测并在安装完成时提示，需要用户自行安装：
  <https://github.com/vadimgrn/usbip-win2>
- **Inno Setup 7**：仅在构建安装包时需要：`winget install --id JRSoftware.InnoSetup.7 -e`
- 安装/卸载需要管理员权限。

### Android

- Android 8.1（API 27）及以上。
- 已录入指纹（或设备支持的其他强生物识别）。
- 与电脑处于同一局域网，且路由器未开启 AP 隔离。
- 输入页会持有一个低延迟 Wi-Fi 锁（API 29+ 用 `WIFI_MODE_FULL_LOW_LATENCY`），避免 Wi-Fi 省电把触控帧攒到唤醒时成批送达——那会表现为偶发的几十毫秒延迟。需要 `WAKE_LOCK` 权限。

## 构建与安装（Windows）

### 安装包（推荐）

在仓库根目录运行：

```powershell
.\Windows\installer\build-installer.ps1
```

输出：`artifacts\installer\Utochpad-Setup-<version>.exe`。构建需要 .NET 10 SDK、bun、Rust（MSVC toolchain）、Go 1.27+ 和 Inno Setup 7；首次构建会下载固定版本的 VIIPER 上游源码，之后复用 `.build\` 缓存。

安装包是普通的中文向导程序，会自动：

1. 停止正在运行的面板、服务和 `viiper.exe`，清理已有的程序文件；
2. 写入服务、凭据提供程序、控制面板与 `viiper.exe`；
3. 注册并启动 `UtochpadService`（LocalSystem，开机自启，失败自动重启）；
4. `regsvr32` 注册 CP 的 COM 宿主并写入凭据提供程序注册表项；
5. 添加入站防火墙规则（默认 TCP 42800，专用/域网络）；
6. 检测 USBip 驱动；缺失时在安装完成页提示用户自行安装，并给出下载地址；
7. 创建开始菜单快捷方式（可选桌面快捷方式），安装完成后可启动面板；
8. 在“设置 → 应用 → 已安装的应用”中提供卸载入口，卸载时询问是否删除配对数据。

安装包**不内置** WebView2、.NET 10 Runtime 和 USBip 驱动：Windows 11 自带 WebView2；缺少 .NET 10 Runtime 时应用会在运行时提示；USBip 缺失时安装包会提示用户自行安装。需要解锁功能的机器请先安装：

```powershell
winget install Microsoft.DotNet.Runtime.10
```

常用构建参数：

```powershell
.\Windows\installer\build-installer.ps1 -PanelExe "D:\build\Utochpad.exe"   # 复用已构建的面板
.\Windows\installer\build-installer.ps1 -SkipViiper                        # 复用 artifacts\viiper\viiper.exe
.\Windows\installer\build-installer.ps1 -Version 1.1.0
```

安装包编译脚本是 `Windows/installer/Utochpad.iss`；第三方组件声明与 GPLv3 文本会随安装包一起安装。

### PowerShell 脚本（开发机）

在仓库根目录以**管理员身份**运行：

```powershell
.\Windows\installer\build-viiper.ps1   # 生成 artifacts\viiper\viiper.exe（首次或升级 VIIPER 后）
.\Windows\installer\install.ps1
```

> `Windows/Service/Lithnet.CredentialProvider` 是 git 子模块。新克隆仓库后如果该目录为空，先执行 `git submodule update --init --recursive`。

`install.ps1` 会自动：

1. 停止正在运行的控制面板进程与服务；
2. 发布 `Utochpad.Service`（自包含）与 `Utochpad.CredentialProvider`（框架依赖）到 `%ProgramFiles%\Utochpad`；
3. 在 `Windows\panel` 中执行 `bun install` 与 `bun run tauri build --no-bundle`，把 `Utochpad.exe` 复制到 `Panel` 目录；
4. 复制 `artifacts\viiper\viiper.exe` 到服务目录（也可用 `-ViiperExe` 指定）；
5. 注册并启动 `UtochpadService`（LocalSystem，开机自启，失败自动重启）；
6. `regsvr32` 注册 CP 的 COM 宿主，并写入凭据提供程序注册表项；
7. 添加入站防火墙规则（TCP 42800，专用/域网络）；
8. 创建开始菜单快捷方式并启动控制面板。

常用参数：

```powershell
.\Windows\installer\install.ps1 -ViiperExe "D:\tools\viiper.exe"
.\Windows\installer\install.ps1 -PanelExe "D:\build\Utochpad.exe"  # 复用已构建的面板
.\Windows\installer\install.ps1 -SkipBuild          # 只重新注册，不重新发布
.\Windows\installer\install.ps1 -NoStart            # 安装后不自动打开面板
.\Windows\installer\install.ps1 -InstallDir "D:\Utochpad"
```

卸载：

```powershell
.\Windows\installer\uninstall.ps1            # 保留 %ProgramData%\Utochpad
.\Windows\installer\uninstall.ps1 -PurgeData # 同时删除配对数据与日志
```

> 安装或卸载后建议注销/重启一次，让 LogonUI 重新加载凭据提供程序。

## 构建 VIIPER（含 ptp / radial）

仓库只保存 VIIPER 的自定义设备覆盖层，放在 `Windows/viiper/`。构建脚本会下载 `Windows/viiper/VIIPER_VERSION` 指定的上游 commit，把覆盖层复制进去后编译：

```powershell
.\Windows\installer\build-viiper.ps1          # 输出 artifacts\viiper\viiper.exe
.\Windows\installer\build-viiper.ps1 -Test    # 同时运行 ptp / radial 设备测试
```

- 首次构建需要网络（下载上游源码与 Go 模块），之后会复用 `.build\` 中的缓存。
- 升级 VIIPER：把 `Windows\viiper\VIIPER_VERSION` 改成新的完整 commit SHA，重新构建即可；覆盖层通过新增的 `internal/registry/zz_utochpad.go` 注册设备，不修改任何上游文件。
- 修改 `ptp` / `radial` 请直接编辑 `Windows/viiper/`。
- `artifacts/`、`.build/` 均已忽略，不会进入版本库。

## 控制面板（Tauri）

控制面板是 Tauri 2 + Vue 3 应用，安装在 `%ProgramFiles%\Utochpad\Panel`。开发时：

```powershell
cd Windows\panel
bun install
bun run tauri dev            # 需要 UtochpadService 已运行
bun run build                # 只构建前端到 dist/
bun run tauri build --no-bundle
```

开发模式会启动 Vite（`http://localhost:1420`）和 Tauri 窗口。

- 依赖 Rust 1.85+（MSVC toolchain）、bun 和 WebView2 Runtime（Windows 11 自带）。
- `install.ps1` 和 `build-installer.ps1` 会调用同样的构建命令；没有 Rust/bun 时可用 `-PanelExe` 指定已构建的 `Utochpad.exe`。
- 面板通过 `\\.\pipe\utochpad-ui` 调用 C# 服务；协议定义见 [architecture.md](architecture.md#控制面板ui-管道)。
- `bundle.active=false`，不生成 MSI/NSIS；安装由 `install.ps1` 负责。
- 构建产物在 `Windows\panel\src-tauri\target\release\Utochpad.exe`。
- 样式使用 Tailwind CSS 4 + Vue 组件（`NeoCard`、`NeoButton`、`StatusPill`、`InlineNotice` 等），固定浅色界面，不做深色模式；全局 CSS 只保留基础样式、滚动条和 Vue 对话框过渡。
- 新拟物采用同色背景 + 低对比双向阴影（`shadow-neo`/`shadow-neo-sm`），输入框与二维码槽轻微内凹；图标由 `brand/utochpad-logo.svg` 通过 `bun run tauri icon` 生成。

## 仅构建（不安装）

```powershell
dotnet build Windows\Service\Utochpad.slnx -c Release -p:Platform=x64
```

## Android APK

用 Android Studio 打开 `Android/`，或命令行：

```powershell
# 指向 Android Studio 自带的 JDK（按实际安装位置调整）
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd Android
.\gradlew.bat :app:assembleDebug
```

APK 输出：`Android\app\build\outputs\apk\debug\app-debug.apk`。

发布给其他人使用时，请在 `Android/app/build.gradle.kts` 中配置自己的签名后执行 `assembleRelease`。

## 相关文档

- 组件划分与协议细节：[architecture.md](architecture.md)
- 安装后遇到问题：[troubleshooting.md](troubleshooting.md)
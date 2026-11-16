; Utochpad Windows installer.
;
; Built by build-installer.ps1. The staging directory must contain:
;   Service\   self-contained Utochpad.Service publish output
;   CP\        framework-dependent credential provider publish output
;   Panel\     Utochpad.exe (Tauri control panel)
;   Viiper\    viiper.exe
;
; .NET Runtime and WebView2 are intentionally NOT bundled: Windows normally
; has WebView2, and the app reports a missing .NET Runtime at runtime.

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef AppVersionQuad
  #define AppVersionQuad "1.0.0.0"
#endif
#ifndef StagingDir
  #define StagingDir "..\..\.build\installer\staging"
#endif
#ifndef OutputDir
  #define OutputDir "..\..\.build\installer"
#endif

#define AppName "Utochpad"
#define ServiceName "UtochpadService"
#define CpGuid "FDF19A17-3800-34D6-B49A-CAE0D53ACA8F"
#define FirewallRule "Utochpad Phone (TCP)"

[Setup]
AppId={{692D4AD8-1595-4146-876F-58C8BD05355E}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Utochpad
DefaultDirName={autopf}\Utochpad
DefaultGroupName=Utochpad
DisableProgramGroupPage=yes
AllowNoIcons=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.19041
OutputDir={#OutputDir}
OutputBaseFilename=Utochpad-Setup-{#AppVersion}
SetupIconFile=..\panel\src-tauri\icons\icon.ico
UninstallDisplayIcon={app}\Panel\Utochpad.exe
UninstallDisplayName={#AppName} {#AppVersion}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
SetupLogging=yes
CloseApplications=no
RestartApplications=no
AppMutex=Global\Utochpad.Setup
UsePreviousAppDir=yes
UsePreviousTasks=yes
VersionInfoDescription={#AppName} Setup
VersionInfoCompany=Utochpad
VersionInfoProductName={#AppName}
VersionInfoProductVersion={#AppVersion}
VersionInfoVersion={#AppVersionQuad}

[Languages]
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
chinesesimplified.AppComment=Utochpad 手机触控板与指纹解锁
chinesesimplified.CreateDesktopIcon=创建桌面快捷方式
chinesesimplified.OptionalTasks=附加任务：
chinesesimplified.LaunchApp=启动 Utochpad
chinesesimplified.RegisteringCP=正在注册凭据提供程序...
chinesesimplified.StartingService=正在启动 Utochpad 服务...
chinesesimplified.ServiceStartFailed=Utochpad 服务未能启动。%n%n请用“服务”管理器检查 UtochpadService，或查看 %ProgramData%\Utochpad\logs 中的日志。
chinesesimplified.CpRegisterFailed=凭据提供程序注册失败，锁屏界面的 Utochpad 磁贴可能不可用。%n%n请重启电脑后重新运行安装程序。
chinesesimplified.DotNetMissing=未检测到 .NET 10 运行时，锁屏界面的 Utochpad 磁贴将不可用。%n%n请安装 .NET 10 Runtime 后重新运行安装程序：%nhttps://dotnet.microsoft.com/download/dotnet/10.0
chinesesimplified.PurgeData=是否同时删除配对数据和日志？%n%n选择“否”将保留它们，方便以后重新安装或重新配对。
chinesesimplified.UsbipReadyNote=未检测到 USBip 驱动。触控板和旋钮需要它；安装完成后请手动安装：%nhttps://github.com/vadimgrn/usbip-win2
chinesesimplified.UsbipMissing=未检测到 USBip 驱动，触控板和旋钮将无法使用。%n%n请手动安装 USBip：%nhttps://github.com/vadimgrn/usbip-win2%n%n安装后建议重启电脑，或在“服务”中重启 UtochpadService。
chinesesimplified.UpgradeNote=检测到旧版本。升级后可能需要重新配对手机并重新发送 Windows 密码。
chinesesimplified.AppDescription=手机触控板、Surface Dial 与指纹解锁

english.AppComment=Utochpad phone touchpad and fingerprint unlock
english.CreateDesktopIcon=Create a desktop shortcut
english.OptionalTasks=Additional tasks:
english.LaunchApp=Launch Utochpad
english.RegisteringCP=Registering credential provider...
english.StartingService=Starting the Utochpad service...
english.ServiceStartFailed=The Utochpad service could not be started.%n%nCheck UtochpadService in Services, or see the logs in %ProgramData%\Utochpad\logs.
english.CpRegisterFailed=The credential provider could not be registered. The Utochpad tile may be missing from the sign-in screen.%n%nRestart Windows and run the installer again.
english.DotNetMissing=.NET 10 Runtime was not detected. The Utochpad tile on the sign-in screen will not work.%n%nInstall .NET 10 Runtime and run the installer again:%nhttps://dotnet.microsoft.com/download/dotnet/10.0
english.PurgeData=Delete pairing data and logs as well?%n%nChoose No to keep them for a future reinstall or re-pair.
english.UsbipReadyNote=The USBip driver was not detected. The touchpad and dial require it; install it manually after setup:%nhttps://github.com/vadimgrn/usbip-win2
english.UsbipMissing=The USBip driver was not detected. The touchpad and dial will not work.%n%nInstall USBip manually:%nhttps://github.com/vadimgrn/usbip-win2%n%nRestart Windows (or UtochpadService) after installing it.
english.UpgradeNote=An older installation was detected. You may need to pair the phone and send the Windows password again after upgrading.
english.AppDescription=Phone touchpad, Surface Dial and fingerprint unlock

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:OptionalTasks}"

[Files]
Source: "{#StagingDir}\Service\*"; DestDir: "{app}\Service"; Flags: ignoreversion recursesubdirs createallsubdirs restartreplace
Source: "{#StagingDir}\CP\*"; DestDir: "{app}\CP"; Flags: ignoreversion recursesubdirs createallsubdirs restartreplace
Source: "{#StagingDir}\Panel\*"; DestDir: "{app}\Panel"; Flags: ignoreversion recursesubdirs createallsubdirs restartreplace
Source: "{#StagingDir}\Viiper\viiper.exe"; DestDir: "{app}\Service\viiper"; Flags: ignoreversion
Source: "{#StagingDir}\THIRD-PARTY-NOTICES.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#StagingDir}\GPL-3.0.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\Utochpad"; Filename: "{app}\Panel\Utochpad.exe"; WorkingDir: "{app}\Panel"; IconFilename: "{app}\Panel\Utochpad.exe"; Comment: "{cm:AppComment}"
Name: "{autodesktop}\Utochpad"; Filename: "{app}\Panel\Utochpad.exe"; WorkingDir: "{app}\Panel"; IconFilename: "{app}\Panel\Utochpad.exe"; Comment: "{cm:AppComment}"; Tasks: desktopicon

[Registry]
; LogonUI enumerates credential providers through this key.
Root: HKLM; Subkey: "SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{{FDF19A17-3800-34D6-B49A-CAE0D53ACA8F}"; ValueType: string; ValueName: ""; ValueData: "{#AppName}"; Flags: uninsdeletekey

[Run]
Filename: "{sys}\regsvr32.exe"; Parameters: "/s ""{app}\CP\Utochpad.CredentialProvider.comhost.dll"""; StatusMsg: "{cm:RegisteringCP}"; Flags: runhidden waituntilterminated
Filename: "{app}\Panel\Utochpad.exe"; Description: "{cm:LaunchApp}"; Flags: nowait postinstall skipifsilent

[Code]
const
  ServiceNameConst = '{#ServiceName}';
  CpGuidConst = '{#CpGuid}';
  CpClsidKey = 'SOFTWARE\Classes\CLSID\{' + CpGuidConst + '}';
  CpProviderKey = 'SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{' + CpGuidConst + '}';
  FirewallRuleConst = '{#FirewallRule}';
  DefaultPort = '42800';
  CurrentComHost = 'Utochpad.CredentialProvider.comhost.dll';
  LegacyComHost = 'Utouchpad.Cp.comhost.dll';

function IsUsbipInstalled(): Boolean;
begin
  Result := RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\usbip2_ude') and
            RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\usbip2_filter');
end;

function HasDotNet10Runtime(): Boolean;
var
  FindRec: TFindRec;
begin
  Result := False;
  if FindFirst(ExpandConstant('{commonpf64}\dotnet\shared\Microsoft.NETCore.App\10.*'), FindRec) then
  try
    Result := (FindRec.Attributes and FILE_ATTRIBUTE_DIRECTORY) <> 0;
  finally
    FindClose(FindRec);
  end;
end;

function ServiceExists(const Name: String): Boolean;
begin
  Result := RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\' + Name);
end;

function QueryServiceRunning(const Name: String): Boolean;
var
  TempFile: String;
  Lines: TArrayOfString;
  I, ResultCode: Integer;
begin
  Result := False;
  TempFile := ExpandConstant('{tmp}\utochpad-sc-query.txt');
  if Exec(ExpandConstant('{sys}\cmd.exe'), '/C sc query "' + Name + '" > "' + TempFile + '" 2>&1',
          '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
  begin
    if LoadStringsFromFile(TempFile, Lines) then
    begin
      for I := 0 to GetArrayLength(Lines) - 1 do
      begin
        if Pos('RUNNING', Uppercase(Lines[I])) > 0 then
        begin
          Result := True;
          Break;
        end;
      end;
    end;
  end;
  DeleteFile(TempFile);
end;

procedure StopService(const Name: String);
var
  I, ResultCode: Integer;
begin
  if not ServiceExists(Name) then
    Exit;

  Exec(ExpandConstant('{sys}\net.exe'), 'stop "' + Name + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  for I := 0 to 19 do
  begin
    if not QueryServiceRunning(Name) then
      Break;
    Sleep(250);
  end;
  if QueryServiceRunning(Name) then
    Log('Service ' + Name + ' did not stop in time');
end;

procedure DeleteService(const Name: String);
var
  I, ResultCode: Integer;
begin
  if not ServiceExists(Name) then
    Exit;

  Exec(ExpandConstant('{sys}\sc.exe'), 'delete "' + Name + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  for I := 0 to 39 do
  begin
    if not ServiceExists(Name) then
      Break;
    Sleep(250);
  end;
  if ServiceExists(Name) then
    Log('Service ' + Name + ' is still marked for deletion');
end;

function StartService(const Name: String): Boolean;
var
  I, ResultCode: Integer;
begin
  Result := QueryServiceRunning(Name);
  if Result then
    Exit;

  Exec(ExpandConstant('{sys}\sc.exe'), 'start "' + Name + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  if ResultCode <> 0 then
    Log('sc start ' + Name + ' exited with ' + IntToStr(ResultCode));

  // sc start returns as soon as the SCM accepts the request; wait for the
  // service to actually reach RUNNING before reporting success.
  for I := 0 to 59 do
  begin
    if QueryServiceRunning(Name) then
    begin
      Result := True;
      Exit;
    end;
    Sleep(250);
  end;
  Log('Service ' + Name + ' did not reach RUNNING after sc start');
end;

procedure KillProcess(const ImageName: String);
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM "' + ImageName + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

procedure KillProcesses();
begin
  KillProcess('Utochpad.exe');
  KillProcess('utochpad-settings.exe');
  KillProcess('Utouchpad.Winapp.exe');
  KillProcess('viiper.exe');
  KillProcess('Utochpad.Service.exe');
  KillProcess('Utouchpad.Service.exe');
end;

procedure UnregisterComHost(const FileName: String);
var
  Path: String;
  ResultCode: Integer;
begin
  Path := ExpandConstant('{app}\CP\') + FileName;
  if FileExists(Path) then
  begin
    Exec(ExpandConstant('{sys}\regsvr32.exe'), '/u /s "' + Path + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Log('Unregistered ' + Path + ' (exit ' + IntToStr(ResultCode) + ')');
  end;
end;

procedure CleanInstallDir();
begin
  DelTree(ExpandConstant('{app}\Service'), True, True, True);
  DelTree(ExpandConstant('{app}\CP'), True, True, True);
  DelTree(ExpandConstant('{app}\Panel'), True, True, True);
  DelTree(ExpandConstant('{app}\Winapp'), True, True, True);
  DelTree(ExpandConstant('{app}\viiper'), True, True, True);
  DeleteFile(ExpandConstant('{app}\Utouchpad.Service.exe'));
  DeleteFile(ExpandConstant('{app}\Utochpad.Service.exe'));
  DeleteFile(ExpandConstant('{app}\createdump.exe'));
end;

procedure CreateService();
var
  ResultCode: Integer;
  BinPath: String;
begin
  BinPath := ExpandConstant('{app}\Service\Utochpad.Service.exe');
  Exec(ExpandConstant('{sys}\sc.exe'),
       'create ' + ServiceNameConst + ' binPath= "' + BinPath + '" start= auto DisplayName= "Utochpad Service"',
       '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  if ResultCode = 1073 then
    // Already exists (for example sc delete was still pending): point it at
    // the new binary instead of leaving the old path behind.
    Exec(ExpandConstant('{sys}\sc.exe'),
         'config ' + ServiceNameConst + ' binPath= "' + BinPath + '" start= auto DisplayName= "Utochpad Service"',
         '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  if ResultCode <> 0 then
    Log('sc create/config exited with ' + IntToStr(ResultCode));

  Exec(ExpandConstant('{sys}\sc.exe'),
       'description ' + ServiceNameConst + ' "Utochpad 手机解锁、触控板与 Surface Dial 桥接服务"',
       '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\sc.exe'),
       'failure ' + ServiceNameConst + ' reset= 86400 actions= restart/5000/restart/5000/restart/5000',
       '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

function GetConfiguredPort(): String;
var
  Lines: TArrayOfString;
  I, P, StartPos, Port: Integer;
  Line: String;
begin
  Result := DefaultPort;
  if not FileExists(ExpandConstant('{commonappdata}\Utochpad\config.json')) then
    Exit;
  if not LoadStringsFromFile(ExpandConstant('{commonappdata}\Utochpad\config.json'), Lines) then
    Exit;

  for I := 0 to GetArrayLength(Lines) - 1 do
  begin
    Line := Lines[I];
    P := Pos('"port"', Line);
    if P > 0 then
    begin
      P := P + Length('"port"');
      while (P <= Length(Line)) and (Line[P] <> ':') do
        Inc(P);
      if P > Length(Line) then
        Exit;
      Inc(P);
      while (P <= Length(Line)) and ((Line[P] = ' ') or (Line[P] = #9)) do
        Inc(P);
      StartPos := P;
      while (P <= Length(Line)) and (Line[P] >= '0') and (Line[P] <= '9') do
        Inc(P);
      if P > StartPos then
      begin
        Port := StrToIntDef(Copy(Line, StartPos, P - StartPos), 0);
        if (Port > 0) and (Port <= 65535) then
          Result := IntToStr(Port);
      end;
      Exit;
    end;
  end;
end;

procedure ConfigureFirewall();
var
  Port: String;
  ResultCode: Integer;
begin
  Port := GetConfiguredPort();
  Exec(ExpandConstant('{sys}\netsh.exe'),
       'advfirewall firewall delete rule name="' + FirewallRuleConst + '"',
       '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\netsh.exe'),
       'advfirewall firewall add rule name="' + FirewallRuleConst + '" dir=in action=allow protocol=TCP localport=' +
       Port + ' profile=private,domain description="允许同一局域网内的手机连接 Utochpad 服务"',
       '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  if ResultCode <> 0 then
    Log('netsh firewall add exited with ' + IntToStr(ResultCode));
end;

procedure RemoveFirewallRule();
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\netsh.exe'),
       'advfirewall firewall delete rule name="' + FirewallRuleConst + '"',
       '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  CpPath, ExpectedCpPath: String;
begin
  if CurStep = ssInstall then
  begin
    StopService(ServiceNameConst);
    KillProcesses();
    UnregisterComHost(LegacyComHost);
    UnregisterComHost(CurrentComHost);
    DeleteService(ServiceNameConst);
    Sleep(500);
    CleanInstallDir();
  end
  else if CurStep = ssPostInstall then
  begin
    CreateService();
    ConfigureFirewall();
  end
  else if CurStep = ssDone then
  begin
    // [Run] is processed before ssPostInstall, so the service is created
    // after [Run] has finished. Start it here instead of from [Run]; the
    // panel launched from [Run] polls and will pick it up.
    if not StartService(ServiceNameConst) then
    begin
      Log('UtochpadService is not running after installation.');
      if not WizardSilent then
        MsgBox(CustomMessage('ServiceStartFailed'), mbError, MB_OK);
    end;

    ExpectedCpPath := ExpandConstant('{app}\CP\Utochpad.CredentialProvider.comhost.dll');
    if (not RegQueryStringValue(HKLM, CpClsidKey + '\InprocServer32', '', CpPath)) or
       (CompareText(CpPath, ExpectedCpPath) <> 0) then
    begin
      Log('Credential provider is not registered at ' + ExpectedCpPath);
      if not WizardSilent then
        MsgBox(CustomMessage('CpRegisterFailed'), mbError, MB_OK);
    end;

    if not HasDotNet10Runtime() then
    begin
      Log('The .NET 10 runtime was not found.');
      if not WizardSilent then
        MsgBox(CustomMessage('DotNetMissing'), mbError, MB_OK);
    end;

    if not IsUsbipInstalled() then
    begin
      Log('The USBip driver was not found.');
      if not WizardSilent then
        MsgBox(CustomMessage('UsbipMissing'), mbInformation, MB_OK);
    end;
  end
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if CurUninstallStep = usUninstall then
  begin
    StopService(ServiceNameConst);
    KillProcesses();
    UnregisterComHost(CurrentComHost);
    UnregisterComHost(LegacyComHost);
    DeleteService(ServiceNameConst);
    RemoveFirewallRule();

    RegDeleteKeyIncludingSubkeys(HKLM, CpClsidKey);
    RegDeleteKeyIncludingSubkeys(HKLM, 'SOFTWARE\WOW6432Node\Classes\CLSID\{' + CpGuidConst + '}');
    RegDeleteKeyIncludingSubkeys(HKLM, CpProviderKey);

    DataDir := ExpandConstant('{commonappdata}\Utochpad');
    if DirExists(DataDir) and (not UninstallSilent) then
    begin
      if MsgBox(CustomMessage('PurgeData'), mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
      begin
        DelTree(DataDir, True, True, True);
        Log('Deleted ' + DataDir);
      end;
    end;
  end;
end;

function UpdateReadyMemo(Space, NewLine, MemoUserInfoInfo, MemoDirInfo, MemoTypeInfo,
  MemoComponentsInfo, MemoGroupInfo, MemoTasksInfo: String): String;
begin
  Result := '';
  if MemoDirInfo <> '' then
    Result := Result + MemoDirInfo + NewLine + NewLine;
  if MemoTasksInfo <> '' then
    Result := Result + MemoTasksInfo + NewLine + NewLine;

  if not IsUsbipInstalled() then
    Result := Result + CustomMessage('UsbipReadyNote') + NewLine + NewLine;

  if FileExists(ExpandConstant('{app}\CP\Utouchpad.Cp.comhost.dll')) or
     DirExists(ExpandConstant('{commonappdata}\UtouchpadCP')) then
    Result := Result + CustomMessage('UpgradeNote') + NewLine + NewLine;
end;

; ============================================================================
; mpkg 壁纸提取器 —— Windows 安装包脚本（Inno Setup 6）
;
; 编译方式（二选一）：
;   1) Gradle：  .\gradlew.bat :composeApp:packageWindowsInstaller
;   2) 手动：    ISCC packaging\windows\setup.iss
;
; 前置产物：app-image（Compose createDistributable 的输出）
;   composeApp/build/compose/binaries/main/app/mpkg-tool/
;
; 安装特性：
;   - 全中文安装向导（Inno Setup 官方简体中文翻译，6.5.0+）
;   - 默认安装到 C:/Program Files\mpkg 壁纸提取器，向导内可浏览修改
;   - 桌面快捷方式（默认勾选自动创建）+ 开始菜单快捷方式（可选）
;   - 支持正常卸载：系统"设置-应用"里可见，开始菜单也有卸载入口
;   - 升级覆盖：AppId 固定，重装同版本自动覆盖旧版
;
; 注意：本文件必须保存为 UTF-8 with BOM，否则 ISCC 按本机 GBK 解码会乱码。
; ============================================================================

#define MyAppName     "mpkg 壁纸提取器"
#define MyAppNameEn   "mpkg-tool"
#define MyAppVersion  "1.0.1"
#define MyAppExe      "mpkg-tool.exe"
#define MyAppURL      "https://github.com/piaomiaozhishang/mpkg-convert-desktop"

[Setup]
; AppId 与 build.gradle.kts 中 windows.upgradeUuid 保持一致
AppId={{6F1D2B4E-7A3C-4E5B-9C8D-1A2B3C4D5E6F}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher=piaomiaozhishang
AppComments=解析 Wallpaper Engine 导出的 .mpkg / .pkg 壁纸包，提取 4K 原图与 MP4
AppSupportURL=https://b23.tv/JWQpitx
AppUpdatesURL={#MyAppURL}
VersionInfoVersion={#MyAppVersion}
VersionInfoProductName={#MyAppName}
VersionInfoDescription=Wallpaper Engine mpkg/pkg wallpaper extractor
; 默认安装目录：{autopf} 在 64 位安装模式下即 C:/Program Files
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
UninstallDisplayName={#MyAppName}
UninstallDisplayIcon={app}\{#MyAppExe}
; 许可协议页（MIT）
LicenseFile=安装许可.txt
; 安装包自身的图标
SetupIconFile=..\..\composeApp\src\main\resources\icons\app.ico
OutputDir=..\..\composeApp\build\compose\binaries\main\dist
OutputBaseFilename={#MyAppNameEn}-setup-{#MyAppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
; x64os：仅 64 位 x64 系统（app-image 的 JRE 是 x64；ARM64 上无原生支持）
ArchitecturesAllowed=x64os
ArchitecturesInstallIn64BitMode=x64os
; 覆盖安装时若旧版正在运行，先自动关闭再替换文件（Inno Setup 6.4+）
CloseApplications=yes
RestartApplications=no

[Languages]
; 官方简体中文翻译（Inno Setup 6.5.0+；文件同时备份在本目录，供 Gradle 任务同步）
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[CustomMessages]
chinesesimplified.CreateStartMenuShortcut=创建开始菜单快捷方式

[Tasks]
; 桌面快捷方式：默认勾选（安装完成即自动创建）
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
; 开始菜单快捷方式：可勾选
Name: "startmenuicon"; Description: "{cm:CreateStartMenuShortcut}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
; app-image 整目录：mpkg-tool.exe 启动器 + app/（jar 与资源）+ runtime/（私有 JRE）
Source: "..\..\composeApp\build\compose\binaries\main\app\mpkg-tool\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "..\..\LICENSE"; DestDir: "{app}"; DestName: "LICENSE.txt"; Flags: ignoreversion
Source: "THIRD-PARTY-NOTICES.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "安装许可.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\..\docs\场景特效可行性与本次修复.md"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExe}"; Tasks: startmenuicon
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"; Tasks: startmenuicon
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExe}"; Tasks: desktopicon

[Run]
; 安装完成页提供"立即运行"勾选项
Filename: "{app}\{#MyAppExe}"; Description: "{cm:LaunchProgram,{#MyAppName}}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; 卸载后清理安装根目录（仅当目录已空时才删，避免误删用户放进去的文件）
Type: dirifempty; Name: "{app}"

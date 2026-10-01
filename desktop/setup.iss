#define AppName "JPS Tunnel Desktop"
#define AppVersion "1.4.1"
#define AppPublisher "JhopanStore"
#define AppExeName "JPS-Tunnel-Desktop.exe"

[Setup]
AppId={{B54E42E4-C4B7-4918-B9FC-3DDA99A1B6A1}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\JPS Tunnel Desktop
DefaultGroupName=JPS Tunnel Desktop
DisableProgramGroupPage=yes
OutputDir=output
OutputBaseFilename=JPS-Tunnel-Desktop-Setup-v{#AppVersion}
SetupIconFile=assets\app.ico
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
UninstallDisplayIcon={app}\{#AppExeName}
WizardStyle=modern

[Files]
Source: "bin\{#AppExeName}"; DestDir: "{app}"; Flags: ignoreversion
Source: "bin\JPS-Tunnel-Core-windows-amd64.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "assets\app.ico"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\JPS Tunnel Desktop"; Filename: "{app}\{#AppExeName}"; IconFilename: "{app}\{#AppExeName}"
Name: "{autodesktop}\JPS Tunnel Desktop"; Filename: "{app}\{#AppExeName}"; Tasks: desktopicon; IconFilename: "{app}\{#AppExeName}"

[Tasks]
Name: "desktopicon"; Description: "Buat shortcut Desktop"; GroupDescription: "Shortcut:"

[UninstallDelete]
Type: files; Name: "{app}\*.json"

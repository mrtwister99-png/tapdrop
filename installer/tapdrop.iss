; Instalátor TapDrop pro Windows (Inno Setup 6)
; Sestavení: ISCC.exe installer\tapdrop.iss   ->  dist\TapDrop-Setup.exe

#define AppName "TapDrop"
#define AppVersion "1.0.0"
#define AppExe "TapDrop.exe"

[Setup]
AppId={{7B1E5C52-3D0A-4F6B-9C4E-7A2D1F8E9B10}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=TapDrop
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\{#AppExe}
OutputDir=..\dist
OutputBaseFilename=TapDrop-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "czech"; MessagesFile: "compiler:Languages\Czech.isl"

[Tasks]
Name: "desktopicon"; Description: "Vytvořit zástupce na ploše"; GroupDescription: "Zástupci:"
Name: "autostart"; Description: "Spouštět TapDrop při startu Windows (do lišty u hodin)"; GroupDescription: "Další:"
Name: "firewall"; Description: "Povolit TapDrop ve Windows Firewallu (soukromé sítě)"; GroupDescription: "Další:"; Check: IsAdminInstallMode

[Files]
Source: "..\dist\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\Odinstalovat {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "TapDrop"; ValueData: """{app}\{#AppExe}"" --minimized"; Tasks: autostart; Flags: uninsdeletevalue

[Run]
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""TapDrop"" dir=in action=allow program=""{app}\{#AppExe}"" profile=private enable=yes"; Flags: runhidden; Tasks: firewall
Filename: "{app}\{#AppExe}"; Description: "Spustit TapDrop"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""TapDrop"""; Flags: runhidden; RunOnceId: "DelFirewall"

[UninstallDelete]
Type: filesandordirs; Name: "{userappdata}\TapDrop\logs"

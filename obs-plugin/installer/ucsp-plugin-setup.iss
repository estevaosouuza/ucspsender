; Inno Setup script for the UCSP Camera Source OBS plugin installer.
; Build locally with: ISCC.exe ucsp-plugin-setup.iss
; (needs obs-plugin/build_x64 already built, see docs/roadmap.md)
;
; Installs to %ProgramData%\obs-studio\plugins\ucsp-source -- the same per-machine,
; no-admin-required convention OBS's own first-party NDI plugin (distroav) uses, so
; PrivilegesRequired=lowest works correctly and users don't need to run this as
; Administrator or point it at their OBS install folder.

#define MyAppName "UCSP Camera Source"
#define MyAppVersion "0.1.3"
#define MyAppPublisher "Estevao Souza"
#define MyAppURL "https://github.com/estevaosouuza/ucspsender"
#define BuildDir "..\build_x64\rundir\RelWithDebInfo"
#define VcpkgBin "C:\Users\Lenovo\vcpkg\installed\x64-windows\bin"

[Setup]
AppId={{9C6E7E9F-2E3E-4A9A-9B1B-UCSPCAMSRC01}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={commonappdata}\obs-studio\plugins\ucsp-source
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\..\dist
OutputBaseFilename=UCSP-OBS-Plugin-Setup-{#MyAppVersion}
Compression=lzma
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
CloseApplicationsFilter=obs64.exe
RestartApplications=no
UninstallDisplayName={#MyAppName} for OBS Studio
UninstallDisplayIcon={app}\bin\64bit\ucsp-source.dll

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Files]
Source: "{#BuildDir}\ucsp-source.dll"; DestDir: "{app}\bin\64bit"; Flags: ignoreversion
Source: "{#BuildDir}\ucsp-source\locale\*"; DestDir: "{app}\data\locale"; Flags: ignoreversion recursesubdirs
Source: "{#VcpkgBin}\avcodec-62.dll"; DestDir: "{app}\bin\64bit"; Flags: ignoreversion
Source: "{#VcpkgBin}\avformat-62.dll"; DestDir: "{app}\bin\64bit"; Flags: ignoreversion
Source: "{#VcpkgBin}\avutil-60.dll"; DestDir: "{app}\bin\64bit"; Flags: ignoreversion
Source: "{#VcpkgBin}\swscale-9.dll"; DestDir: "{app}\bin\64bit"; Flags: ignoreversion
Source: "{#VcpkgBin}\swresample-6.dll"; DestDir: "{app}\bin\64bit"; Flags: ignoreversion

[Code]
function InitializeSetup(): Boolean;
begin
  Result := True;
  if not DirExists(ExpandConstant('{pf}\obs-studio')) and not DirExists(ExpandConstant('{autopf}\obs-studio')) then
  begin
    MsgBox('Não foi possível encontrar o OBS Studio instalado neste computador. Instale o OBS Studio antes de continuar.' + #13#10 +
           'Could not find OBS Studio installed on this computer. Please install OBS Studio first.',
           mbInformation, MB_OK);
  end;
end;

[Messages]
FinishedLabel=O plugin UCSP Camera Source foi instalado.%n%nAbra (ou reabra) o OBS Studio e adicione uma fonte "UCSP Camera Source" numa cena para começar a receber o vídeo do app Android.

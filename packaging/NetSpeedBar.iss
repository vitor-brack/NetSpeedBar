; Instalador do NetSpeedBar (Inno Setup 6).
; Não precisa compilar este arquivo à mão: o gerar-instalador.ps1 faz tudo e passa AppVersion, AppImage e OutputDir.

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef AppImage
  #define AppImage "..\target\installer\image\NetSpeedBar"
#endif
#ifndef OutputDir
  #define OutputDir "..\dist"
#endif

#define AppName "NetSpeedBar"
#define AppExe "NetSpeedBar.exe"
#define AppPublisher "Vitor Brackmann"
; Identifica o programa entre versões. NÃO mude: senão uma atualização vira uma segunda instalação.
#define AppGuid "{8E83CF34-B216-4349-9261-01E35E802ACE}"
; Classe da janela invisível do programa (NetSpeedBar.java), usada para fechá-lo antes de atualizar/desinstalar
#define ControllerClass "NetSpeedBar.Controller"
#define RunKey "Software\Microsoft\Windows\CurrentVersion\Run"

[Setup]
AppId={#StringChange(AppGuid, "{", "{{")}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
VersionInfoVersion={#AppVersion}
; Instala só para o usuário atual, em %LOCALAPPDATA%\Programs: não pede senha de administrador
PrivilegesRequired=lowest
DefaultDirName={autopf}\{#AppName}
DisableDirPage=yes
DisableProgramGroupPage=yes
ShowLanguageDialog=no
OutputDir={#OutputDir}
OutputBaseFilename={#AppName}-Setup-{#AppVersion}
SetupIconFile=NetSpeedBar.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Windows 10 1809 ou mais novo (feito para o Windows 11)
MinVersion=10.0.17763
RestartApplications=no

[Languages]
Name: "brazilianportuguese"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[InstallDelete]
; Numa atualização, troca o Java embutido e o programa inteiros (não deixa arquivos velhos misturados)
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\app"

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"

[Registry]
; Inicia junto com o Windows. Numa atualização, respeita quem desligou essa opção pelo menu do widget.
Root: HKCU; Subkey: "{#RunKey}"; ValueType: string; ValueName: "{#AppName}"; ValueData: """{app}\{#AppExe}"""; Flags: uninsdeletevalue; Check: ShouldStartWithWindows

[Run]
Filename: "{app}\{#AppExe}"; Description: "Abrir o {#AppName} agora"; Flags: nowait postinstall; Check: not IsAdmin
; Instalador aberto com "Executar como administrador": abre pelo Explorer, para o widget não rodar como administrador
Filename: "{win}\explorer.exe"; Parameters: """{app}\{#AppExe}"""; Description: "Abrir o {#AppName} agora"; Flags: nowait postinstall; Check: IsAdmin

[UninstallDelete]
; Configurações e log
Type: filesandordirs; Name: "{localappdata}\NetSpeedBar"

[Code]
const
  WM_CLOSE = $0010;
  SYNCHRONIZE = $00100000;

var
  IsUpgrade: Boolean;

function GetWindowThreadProcessId(Wnd: HWND; var ProcessId: DWORD): DWORD;
  external 'GetWindowThreadProcessId@user32.dll stdcall';
function OpenProcess(DesiredAccess: DWORD; InheritHandle: BOOL; ProcessId: DWORD): THandle;
  external 'OpenProcess@kernel32.dll stdcall';
function WaitForSingleObject(Handle: THandle; Milliseconds: DWORD): DWORD;
  external 'WaitForSingleObject@kernel32.dll stdcall';
function CloseHandle(Handle: THandle): BOOL;
  external 'CloseHandle@kernel32.dll stdcall';

{ Pede para o widget fechar (como o "Sair" do menu) e espera o processo terminar, para os arquivos ficarem livres.
  Normalmente há uma só instância aberta, mas fecha todas que encontrar. }
procedure CloseRunningApp();
var
  Wnd: HWND;
  Pid: DWORD;
  Process: THandle;
  Attempt: Integer;
begin
  for Attempt := 1 to 5 do
  begin
    Wnd := FindWindowByClassName('{#ControllerClass}');
    if Wnd = 0 then
      Exit;
    GetWindowThreadProcessId(Wnd, Pid);
    Process := OpenProcess(SYNCHRONIZE, False, Pid);
    PostMessage(Wnd, WM_CLOSE, 0, 0);
    if Process <> 0 then
    begin
      WaitForSingleObject(Process, 10000);
      CloseHandle(Process);
    end
    else
      Sleep(2000);
    { O launcher do jpackage (processo pai do Java) termina logo em seguida }
    Sleep(500);
  end;
end;

function InitializeSetup(): Boolean;
begin
  IsUpgrade := RegKeyExists(HKCU, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{#AppGuid}_is1');
  Result := True;
end;

function ShouldStartWithWindows(): Boolean;
begin
  Result := (not IsUpgrade) or RegValueExists(HKCU, '{#RunKey}', '{#AppName}');
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  CloseRunningApp();
  Result := '';
end;

function InitializeUninstall(): Boolean;
begin
  CloseRunningApp();
  Result := True;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  { Garante que não sobra a entrada de inicialização, mesmo se o usuário a recriou pelo menu }
  if CurUninstallStep = usPostUninstall then
    RegDeleteValue(HKCU, '{#RunKey}', '{#AppName}');
end;

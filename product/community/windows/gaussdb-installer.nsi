; Unsigned, per-user Windows x86_64 preview installer.
; PAYLOAD and REMOVE_MANIFEST are generated/validated by assemble.mjs.
Unicode true
!include "MUI2.nsh"
!include "x64.nsh"
!ifndef RELEASE
  !error "Define RELEASE"
!endif
Name "DBeaver GaussDB ${RELEASE} (Preview)"
OutFile "${OUTPUT}"
InstallDir "$LOCALAPPDATA\Programs\DBeaver-GaussDB-${RELEASE}"
RequestExecutionLevel user
SetCompressor /SOLID lzma
ShowInstDetails show
ShowUninstDetails show
!define REGKEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\DBeaver-GaussDB-${RELEASE}"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "${PAYLOAD}/LICENSE.md"
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "SimpChinese"

Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP "This package requires 64-bit Windows."
    Abort
  ${EndIf}
  SetRegView 64
  ; Never merge a preview into an existing application directory.
  IfFileExists "$INSTDIR\*" 0 fresh
    MessageBox MB_ICONSTOP "Installation directory already exists. Uninstall this preview first, or use the ZIP in a new directory. Your workspace is not removed."
    Abort
  fresh:
FunctionEnd

Section "DBeaver GaussDB"
  SetShellVarContext current
  SetOutPath "$INSTDIR"
  File /r "${PAYLOAD}/*"
  WriteUninstaller "$INSTDIR\uninstall.exe"
  CreateDirectory "$SMPROGRAMS\DBeaver GaussDB ${RELEASE}"
  CreateShortCut "$SMPROGRAMS\DBeaver GaussDB ${RELEASE}\DBeaver.lnk" "$INSTDIR\dbeaver.exe" '-data "$LOCALAPPDATA\DBeaver-GaussDB\workspace"'
  CreateShortCut "$SMPROGRAMS\DBeaver GaussDB ${RELEASE}\Uninstall.lnk" "$INSTDIR\uninstall.exe"
  WriteRegStr HKCU "${REGKEY}" "DisplayName" "DBeaver GaussDB ${RELEASE} (Preview)"
  WriteRegStr HKCU "${REGKEY}" "DisplayVersion" "26.2.0-${RELEASE}"
  WriteRegStr HKCU "${REGKEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${REGKEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegDWORD HKCU "${REGKEY}" "NoModify" 1
  WriteRegDWORD HKCU "${REGKEY}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  SetShellVarContext current
  SetRegView 64
  ; Delete only files shipped in this package. Never recursively remove user data.
  !include "${REMOVE_MANIFEST}"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"
  Delete "$SMPROGRAMS\DBeaver GaussDB ${RELEASE}\DBeaver.lnk"
  Delete "$SMPROGRAMS\DBeaver GaussDB ${RELEASE}\Uninstall.lnk"
  RMDir "$SMPROGRAMS\DBeaver GaussDB ${RELEASE}"
  DeleteRegKey HKCU "${REGKEY}"
SectionEnd

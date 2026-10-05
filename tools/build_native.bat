@echo off
rem Builds src\native\windows\*.cpp into %1\dlss_bridge.dll with MSVC (x64, static CRT).
rem Needs the NVIDIA DLSS SDK, the AMD FidelityFX SDK (v1.1.4, headers only) and Vulkan-Headers in
rem %DLSS_SDK% / %FFX_SDK% / %VULKAN_HEADERS%.
setlocal
set OUT=%~1
set ROOT=%~dp0..
set SRC=%ROOT%\src\native\windows
if "%DLSS_SDK%"=="" set DLSS_SDK=%ROOT%\_deps\dlss-sdk
if "%FFX_SDK%"=="" set FFX_SDK=%ROOT%\_deps\ffx-sdk
if "%VULKAN_HEADERS%"=="" set VULKAN_HEADERS=%ROOT%\_deps\vulkan-headers
for /f "usebackq tokens=*" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set VSDIR=%%i
call "%VSDIR%\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
if not exist "%OUT%" mkdir "%OUT%"
cl /nologo /LD /EHsc /MT /O2 /W3 /std:c++17 /DNDEBUG /I"%DLSS_SDK%\include" /I"%FFX_SDK%\ffx-api\include" /I"%VULKAN_HEADERS%\include" ^
  "%SRC%\bridge.cpp" "%SRC%\super_resolution.cpp" "%SRC%\frame_gen.cpp" "%SRC%\fsr.cpp" ^
  /Fo"%OUT%\\" /Fe"%OUT%\dlss_bridge.dll" ^
  /link "%DLSS_SDK%\lib\Windows_x86_64\x64\nvsdk_ngx_s.lib" advapi32.lib user32.lib || exit /b 1

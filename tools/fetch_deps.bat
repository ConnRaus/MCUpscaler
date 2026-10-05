@echo off
rem Downloads the SDKs the native bridges are built against into _deps\ (gitignored; none of them are redistributed
rem with the source). Folders that already exist are left alone. Needs git.
setlocal
cd /d "%~dp0.."
if not exist _deps mkdir _deps
call :fetch dlss-sdk v310.9.1 https://github.com/NVIDIA/DLSS.git || exit /b 1
call :fetch ffx-sdk v1.1.4 https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK.git || exit /b 1
call :fetch vulkan-headers v1.4.365 https://github.com/KhronosGroup/Vulkan-Headers.git || exit /b 1
exit /b 0

:fetch
if exist "_deps\%1" (
  echo _deps\%1 already there
  exit /b 0
)
git -c advice.detachedHead=false clone --depth 1 --branch %2 %3 "_deps\%1"
exit /b %errorlevel%

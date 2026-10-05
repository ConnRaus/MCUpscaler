# Third-party notices

The MIT license in `LICENSE` covers this mod's own code only. The jar also bundles the libraries below, which keep
their own licenses; the full texts are in `licenses/` (in the repository and in the jar).

None of these SDKs are in the repository: `tools/fetch_deps.sh` / `tools/fetch_deps.bat` download them from their
publishers into `_deps/` for building.

## NVIDIA DLSS (Windows)

`natives/windows-x64/nvngx_dlss.dll` and `nvngx_dlssg.dll` in the jar are NVIDIA DLSS Super Resolution and Frame
Generation from the NVIDIA DLSS SDK 310.9.1, and `dlss_bridge.dll` links NVIDIA's NGX SDK statically. They are
distributed under the NVIDIA RTX SDKs license (`licenses/NVIDIA-RTX-SDKs-LICENSE.txt`,
https://github.com/NVIDIA/DLSS/blob/main/LICENSE.txt), not under MIT: no permission to modify, reverse engineer or
redistribute them on their own is granted. NVIDIA, RTX and DLSS are trademarks of NVIDIA Corporation. This mod is not
sponsored or endorsed by NVIDIA.

## AMD FidelityFX SDK (FSR 3.1)

On Windows, `natives/windows-x64/amd_fidelityfx_vk.dll` is AMD's signed FidelityFX library (FSR 3.1 upscaling and
frame generation) from the FidelityFX SDK 1.1.4.
`src/native/mac/fsr3_shaders.h` and `src/native/mac/fsr3_fg_shaders.h` are generated (by `tools/fsr3/`) from the
AMD FidelityFX SDK 1.1.4 (FSR 3.1 upscaler, optical flow and frame interpolation shaders), with small changes
noted in those scripts. The host code in `src/native/mac/metalfx_bridge.m` that drives those passes follows the SDK's.
These are under AMD's MIT license (`licenses/AMD-FidelityFX-SDK-LICENSE.txt`), Copyright (C) 2024 Advanced Micro
Devices, Inc. — https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK

## Apple MetalFX

Apple MetalFX is a system framework; the mod links against it at runtime and contains none of it.

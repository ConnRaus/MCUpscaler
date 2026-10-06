# Third-party notices

The MIT license in `LICENSE` covers this mod's own code only. The jar also bundles the libraries below, which keep
their own licenses; the full texts are in `licenses/` (in the repository and in the jar).

None of these SDKs are in the repository: `tools/fetch_deps.sh` / `tools/fetch_deps.bat` download them from their
publishers into `_deps/` for building.

## NVIDIA DLSS (Windows)

`natives/windows-x64/nvngx_dlss.dll` and `nvngx_dlssg.dll` in the jar are NVIDIA DLSS Super Resolution and Frame
Generation from the NVIDIA DLSS SDK 310.9.1 (NVIDIA's unmodified release libraries), and `dlss_bridge.dll` links
NVIDIA's NGX SDK statically. They are distributed, and may only be used, under the NVIDIA RTX SDKs license
(`licenses/NVIDIA-RTX-SDKs-LICENSE.txt`, https://github.com/NVIDIA/DLSS/blob/main/LICENSE.txt), not under MIT: no
permission to modify, reverse engineer or redistribute them on their own is granted.
Copyright (c) NVIDIA Corporation. All rights reserved.

The DLSS libraries contain third-party code (curl, an SGI bitmap font, d3dx12 / DirectX-Graphics-Samples, pugixml,
libnpy, stb and Vulkan-Headers). Their notices, which NVIDIA requires products shipping DLSS to include, are in
`licenses/NVIDIA-DLSS-third-party-notices.txt`, with the Apache 2.0 text in `licenses/Apache-2.0.txt`.

NVIDIA, RTX and DLSS are trademarks and/or registered trademarks of NVIDIA Corporation in the U.S. and other
countries. This mod is not sponsored or endorsed by NVIDIA.

## AMD FidelityFX SDK (FSR 3.1)

On Windows, `natives/windows-x64/amd_fidelityfx_vk.dll` is AMD's signed FidelityFX library (FSR 3.1 upscaling and
frame generation) from the FidelityFX SDK 1.1.4.
`src/native/mac/fsr3_shaders.h` and `src/native/mac/fsr3_fg_shaders.h` are generated (by `tools/fsr3/`) from the
AMD FidelityFX SDK 1.1.4 (FSR 3.1 upscaler, optical flow and frame interpolation shaders), with small changes
noted in those scripts. The host code in `src/native/mac/metalfx_bridge.m` that drives those passes follows the SDK's.
These are under AMD's MIT license (`licenses/AMD-FidelityFX-SDK-LICENSE.txt`), Copyright (C) 2024 Advanced Micro
Devices, Inc. — https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK

## Vulkan-Headers

`dlss_bridge.dll` is compiled against the Khronos Vulkan-Headers 1.4.365, Copyright 2015-2026 The Khronos Group Inc.,
under the Apache License 2.0 (`licenses/Apache-2.0.txt`) — https://github.com/KhronosGroup/Vulkan-Headers

## Apple MetalFX

Apple MetalFX is a system framework; the mod links against it at runtime and contains none of it.

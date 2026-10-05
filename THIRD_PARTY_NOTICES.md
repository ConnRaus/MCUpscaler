# Third-party notices

The MIT license in `LICENSE` covers this mod's own code only. The jar also bundles the libraries below, which keep
their own licenses.

## NVIDIA DLSS (Windows)

`natives/windows-x64/nvngx_dlss.dll` and `nvngx_dlssg.dll` in the jar are NVIDIA DLSS Super Resolution and Frame
Generation, and `dlss_bridge.dll` links NVIDIA's NGX SDK statically. They are distributed under the NVIDIA RTX SDKs
license (https://github.com/NVIDIA/DLSS/blob/main/LICENSE.txt), not under MIT: no permission to modify,
reverse engineer or redistribute them on their own is granted. NVIDIA, RTX and DLSS are trademarks of NVIDIA
Corporation. This mod is not sponsored or endorsed by NVIDIA.

## AMD FidelityFX SDK (FSR 3.1)

On Windows, `natives/windows-x64/amd_fidelityfx_vk.dll` is AMD's signed FidelityFX library (FSR 3.1 upscaling and
frame generation) from the FidelityFX SDK 1.1.4.
`src/native/mac/fsr3_shaders.h` and `src/native/mac/fsr3_fg_shaders.h` are generated (by `tools/fsr3/`) from the
AMD FidelityFX SDK 1.1.4 (FSR 3.1 upscaler, optical flow and frame interpolation shaders), with small changes
noted in those scripts. The host code in `src/native/mac/metalfx_bridge.m` that drives those passes follows the SDK's.

On macOS, AMD FidelityFX SDK — https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK

```
Copyright (C) 2024 Advanced Micro Devices, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

Apple MetalFX is a system framework; the mod links against it at runtime and contains none of it.

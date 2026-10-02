# Third-party notices

`src/native/fsr3_shaders.h` and `src/native/fsr3_fg_shaders.h` are generated (by `tools/fsr3/`) from the
AMD FidelityFX SDK 1.1.4 (FSR 3.1 upscaler, optical flow and frame interpolation shaders), with small changes
noted in those scripts. The host code in `src/native/metalfx_bridge.m` that drives those passes follows the SDK's.

AMD FidelityFX SDK — https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK

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

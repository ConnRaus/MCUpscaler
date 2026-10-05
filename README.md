# MCUpscaler for Minecraft (Fabric 26.3, Vulkan)

DLSS, FSR 3.1, and MetalFX upscaling and frame generation for Minecraft's Vulkan renderer on Windows and macOS. 

| | Windows | macOS |
|---|---|---|
| Upscalers | NVIDIA DLSS 3/4/4.5 (RTX cards), AMD FSR 3.1 (any GPU), Bilinear | Apple MetalFX Temporal and Spatial, AMD FSR 3.1, Bilinear |
| Frame generation | DLSS Frame Generation (RTX 40/50), FSR Frame Generation (any GPU) | MetalFX (macOS 26+), AMD FSR 3.1 (macOS 14+) |
| Latency | NVIDIA Reflex | – |

The release jar works for both Windows and MacOS. Download it from
[Releases](https://github.com/ConnRaus/MCUpscaler/releases) and put it in your `mods` folder.

![BSL shaders and Distant Horizons with MetalFX upscaling and frame generation](docs/screenshot-bsl-dh.jpg)

> [!WARNING]
> This mod was made quickly with significant AI help as a proof of concept. It is **not** a polished mod, and I won't be releasing it on Modrinth or CurseForge for that reason. It probably has bugs, and MetalFX/FSR on Mac seems to have a bit more overhead than I expected, although it still improves performance. 
>
> The original goal was to test upscalers on Mac with Minecraft's new Vulkan graphics backend, and MoltenVK's Vulkan → Metal bridging particularly, by implementing some native Metal features. Slight scope creep happened, and Windows support along with DLSS was added. I hope it can be a sort of example reference to help other mod creators create more fleshed-out MetalFX/FSR/DLSS upscaling mods. 

## Requirements

- Minecraft 26.3 with the **Vulkan** graphics backend (Video Settings → Graphics API → Prefer
  Vulkan), Fabric Loader 0.19.5+, Fabric API, Java 25.
- Windows 10/11 x64, or macOS 13+.

Optional:

- **Sodium**: recommended; adds the *Upscaling* page to Video Settings. Without it, use the key bindings or
  `config/mcupscaler.properties`.
- **Vitrail** shaders (tested with Bliss, BSL and Complementary) and **Distant Horizons** are supported.

## Settings

Video Settings → Upscaling (with Sodium):

| Setting | Purpose |
|---|---|
| Enable Upscaling | Render the world at a lower resolution and upscale it |
| Upscaler | Choose from various upscalers. Ones your GPU can't run are crossed out |
| Quality Mode | Auto (by screen size), Native AA (100%), Quality, Balanced, Performance, Ultra Performance or Custom |
| Sharpness | Sharpening for FSR and MetalFX |
| DLSS Preset | Windows, DLSS only |
| Frame Generation | Off, or the platform's frame generators |
| NVIDIA Reflex | Windows, NVIDIA cards |
| Texture LOD Correction, Still Shader Pack Foliage | Advanced; see their tooltips |

Controls → MCUpscaler has unbound keys to toggle upscaling and step through upscalers, quality modes and frame
generation. The MCUpscaler section of the F3 screen shows what is running.

## Building

The release jar is built on **Windows**; it contains the Windows natives it builds and the committed macOS dylib in
`prebuilt/natives/`.

```
./gradlew build                 # jar in build/libs
./gradlew deployToInstance -Pinstance_mods_dir=<mods folder>
./gradlew runClientGameTest     # automated test world + screenshots in build/run/clientGameTest/screenshots
```

**Windows** needs Visual Studio (C++ build tools) and, in `_deps/`, the NVIDIA DLSS SDK 310.9.1 (`_deps/dlss-sdk`),
the AMD FidelityFX SDK 1.1.4 (`_deps/ffx-sdk`) and Vulkan-Headers 1.4.365 (`_deps/vulkan-headers`), or `DLSS_SDK` /
`FFX_SDK` / `VULKAN_HEADERS` pointing at them. `tools\fetch_deps.bat` (or `tools/fetch_deps.sh`) downloads all three
from their publishers; none of them are in this repository.

**macOS** needs Xcode's command line tools (`xcode-select --install`); the build compiles
`src/native/mac/metalfx_bridge.m` into a universal `libmetalfx_bridge.dylib`. A jar built on a Mac has no Windows
natives, so DLSS and FSR are unavailable in it on Windows. After changing the bridge, copy
`build/native/natives/libmetalfx_bridge.dylib` to `prebuilt/natives/` so Windows builds pick it up.

The Metal FSR shaders in `src/native/mac/fsr3_*.h` are generated from the AMD FidelityFX SDK by the scripts in
`tools/fsr3/` (they need the FidelityFX SDK, see `tools/fetch_deps.sh`).

## How it works

- While the world renders, Minecraft's main render target is swapped for a smaller one, so vanilla, Sodium, Distant
  Horizons and shader packs all draw at the reduced resolution.
- The temporal upscalers get a sub-pixel camera jitter each frame, the scene depth (with the first-person hand and
  Distant Horizons merged in), and motion vectors computed from depth, the camera's movement and moving entities.
- Windows: `src/native/windows` records DLSS / FSR into Minecraft's own Vulkan command buffer; frame generation
  presents from its own thread, with Reflex markers around the frame.
- macOS: `src/native/mac/metalfx_bridge.m` runs MetalFX and the Metal port of FSR 3.1. Their work is ordered against
  Minecraft's Vulkan frame through a shared timeline semaphore (MoltenVK's `VK_EXT_metal_objects`), so the CPU doesn't
  wait on the GPU. With frame generation on, the mod presents the frames itself, pacing the generated ones between the
  real ones.

## License

MIT for this mod's own code, see `LICENSE`. The bundled NVIDIA DLSS and AMD FidelityFX libraries keep their own
licenses: see `THIRD_PARTY_NOTICES.md` and the full texts in `licenses/` (both are also in the jar).

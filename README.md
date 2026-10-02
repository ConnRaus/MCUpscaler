# MetalFX for Minecraft (Fabric 26.3, macOS Vulkan)

Upscaling and frame generation for Vulkan Minecraft on Macs. The world is rendered at a lower resolution and upscaled by Apple MetalFX or AMD FSR 3.1. 

### WARNING: 

This mod was created quickly using a lot of AI as a proof of concept, it is NOT a polished mod. I will not be releasing it on Modrinth or CurseForge for this reason, I'm sure it has bugs and MetalFX/FSR seem to have a bit more overhead than I was expecting. 

The intention of this mod was to test the new Vulkan graphics backend in Minecraft, and to test MoltenVK's Vulkan -> Metal bridging by implementing some native Metal features. I hope it can provide a jumping point to help other upscaler mod creators make use of MetalFX/FSR and better support Mac users. 

## Requirements

- macOS 13+ (frame generation: macOS 14+ for FSR 3, macOS 26+ for MetalFX)
- Minecraft 26.3 with the **Vulkan** graphics backend (Video Settings → Graphics API → Prefer Vulkan), Fabric Loader 0.19.5+, Fabric API, Java 25.

Optional:

- **Sodium**: Highly recommended, adds the *Upscaling* page to Video Settings. Without it, use the key bindings or
  `config/metalfx.properties`.
- **Vitrail** shader packs (tested with Bliss, BSL and Complementary) and **Distant Horizons** are supported.

## Settings

Video Settings → Upscaling (with Sodium):

| Setting | What it does |
|---|---|
| Enable Upscaling | Render the world at a lower resolution and upscale it |
| Upscaler | MetalFX Temporal (default), AMD FSR 3.1, MetalFX Spatial or Bilinear |
| Render Scale | Resolution the world renders at before upscaling (50% by default) |
| Sharpness | Sharpening after upscaling, 0–100% |
| Frame Generation / Frame Generator | Extra in-between frames, by MetalFX (default) or AMD FSR 3 |
| Texture LOD Correction, Still Shader Pack Foliage | Advanced; see their tooltips |

Controls → MetalFX has unbound keys to toggle upscaling or frame generation and to step through render scales and upscalers. Turn on the MetalFX section of the F3 screen to see what is running and the rendered/generated frame rates.

## Building

```
./gradlew build                 # jar in build/libs (needs Xcode command line tools for the native part)
./gradlew deployToInstance -Pinstance_mods_dir=<mods folder>
./gradlew runClientGameTest     # automated test world + screenshots in build/run/clientGameTest/screenshots
```

`-PtestMods=a.jar,b.jar` adds mods (e.g. Sodium, Vitrail, Distant Horizons) to the game test, and
`-PtestRunFiles=<dir>` copies files such as `shaderpacks/` into its run directory. The scenarios are selected with
`METALFX_*` environment variables, listed in `MetalFXScreenshotTest`.

The FSR shaders in `src/native/fsr3_*.h` are generated from the AMD FidelityFX SDK by the scripts in `tools/fsr3/`.

## Basic Overview

- While the world renders, Minecraft's main render target is swapped for a smaller one, so vanilla, Sodium, Distant Horizons and shader packs all draw at the reduced resolution.
- The temporal upscalers get a sub-pixel camera jitter each frame, the scene depth, and motion vectors computed from depth and the camera's movement.
- `src/native/metalfx_bridge.m` runs the upscalers and frame generation on Metal. Their work is ordered against Minecraft's Vulkan frame through a shared timeline semaphore (MoltenVK's `VK_EXT_metal_objects`), so the CPU doesn't wait on the GPU.
- With frame generation on, the mod presents the frames itself, pacing the generated ones between the real ones. 

## License

MIT, see `LICENSE`. AMD FSR 3.1 is also under MIT: see `THIRD_PARTY_NOTICES.md`.

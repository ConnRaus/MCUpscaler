// AMD FidelityFX API (FSR 3.1): upscaling and frame generation, on Minecraft's Vulkan device and command buffers like the
// DLSS paths. amd_fidelityfx_vk.dll (AMD's signed build from the FidelityFX SDK) is loaded at runtime, so the bridge
// works without it. FSR runs on any Vulkan GPU. See bridge.h.
//
// FidelityFX takes images with a state; every image here is in VK_IMAGE_LAYOUT_GENERAL, handed over as
// UNORDERED_ACCESS, and FidelityFX puts each one back into that layout at the end of its dispatch.

#include "bridge.h"

#include <cfloat>

bool gFfxReady;
static HMODULE gFfxModule;
static PfnFfxCreateContext pCreateContext;
static PfnFfxDestroyContext pDestroyContext;
static PfnFfxConfigure pConfigure;
static PfnFfxQuery pQuery;
static PfnFfxDispatch pDispatch;
static std::wstring gFfxLogPath;
static char gFfxVersion[32];

static ffxContext gUpscale;
static uint32_t gUpRenderW, gUpRenderH, gUpOutW, gUpOutH;
static bool gUpBroken; // context creation failed for these sizes: don't retry every frame

static ffxContext gFrameGen;
static uint32_t gFgDisplayW, gFgDisplayH, gFgRenderW, gFgRenderH;
static VkFormat gFgBackFormat, gFgHudlessFormat;
static bool gFgBroken;

// FidelityFX's warnings and errors, appended to <game dir>/dlssmc/fsr.log when logging is on.
static void ffxMessage(uint32_t type, const wchar_t *message) {
    if (gFfxLogPath.empty()) return;
    FILE *f = nullptr;
    if (_wfopen_s(&f, gFfxLogPath.c_str(), L"a, ccs=UTF-8") != 0 || !f) return;
    fwprintf(f, L"[%s] %s\n", type == FFX_API_MESSAGE_TYPE_ERROR ? L"error" : L"warning", message);
    fclose(f);
}

bool fsrLoad(const wchar_t *dir) {
    if (gFfxReady) return true;
    std::wstring path = std::wstring(dir) + L"\\amd_fidelityfx_vk.dll";
    gFfxModule = LoadLibraryExW(path.c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
    if (!gFfxModule) return false;
    pCreateContext = (PfnFfxCreateContext)GetProcAddress(gFfxModule, "ffxCreateContext");
    pDestroyContext = (PfnFfxDestroyContext)GetProcAddress(gFfxModule, "ffxDestroyContext");
    pConfigure = (PfnFfxConfigure)GetProcAddress(gFfxModule, "ffxConfigure");
    pQuery = (PfnFfxQuery)GetProcAddress(gFfxModule, "ffxQuery");
    pDispatch = (PfnFfxDispatch)GetProcAddress(gFfxModule, "ffxDispatch");
    gFfxReady = pCreateContext && pDestroyContext && pConfigure && pQuery && pDispatch;
    if (!gFfxReady) {
        FreeLibrary(gFfxModule);
        gFfxModule = nullptr;
        return false;
    }
    if (gLogging) gFfxLogPath = std::wstring(dir) + L"\\..\\fsr.log";
    return true;
}

const char *fsrVersion() {
    return gFfxVersion;
}

static FfxApiResource image(VkImage handle, VkFormat format, uint32_t w, uint32_t h, bool writable) {
    FfxApiResource r{};
    r.resource = (void *)handle;
    r.description.type = FFX_API_RESOURCE_TYPE_TEXTURE2D;
    r.description.format = ffxApiGetSurfaceFormatVK(format);
    r.description.width = w;
    r.description.height = h;
    r.description.depth = 1;
    r.description.mipCount = 1;
    r.description.flags = FFX_API_RESOURCE_FLAGS_NONE;
    r.description.usage = writable ? FFX_API_RESOURCE_USAGE_UAV : FFX_API_RESOURCE_USAGE_READ_ONLY;
    r.state = FFX_API_RESOURCE_STATE_UNORDERED_ACCESS;
    return r;
}

static FfxApiResource image(const OwnedImage &img, bool writable) {
    return image(img.image, img.format, img.width, img.height, writable);
}

// FFX asks for some functions by their extension names (vkGetBufferMemoryRequirements2KHR) and calls them unchecked.
// Minecraft's device uses them from core Vulkan without enabling those extensions, so the driver returns null for
// the alias: fall back to the core name.
static PFN_vkVoidFunction VKAPI_PTR ffxDeviceProcAddr(VkDevice device, const char *name) {
    PFN_vkVoidFunction fn = gGdpa(device, name);
    size_t len = strlen(name);
    if (!fn && len > 3 && strcmp(name + len - 3, "KHR") == 0) {
        std::string core(name, len - 3);
        fn = gGdpa(device, core.c_str());
    }
    return fn;
}

static ffxCreateBackendVKDesc backendDesc() {
    ffxCreateBackendVKDesc backend{};
    backend.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_VK;
    backend.vkDevice = gDevice;
    backend.vkPhysicalDevice = gPhysical;
    backend.vkDeviceProcAddr = ffxDeviceProcAddr;
    return backend;
}

static void destroyContext(ffxContext &ctx) {
    if (!ctx) return;
    deviceWaitIdle(); // the context's resources may still be in use by frames in flight
    pDestroyContext(&ctx, nullptr);
    ctx = nullptr;
}

// The upscaler's version ("3.1.4"); frame generation reports its own component version (1.1.x), which isn't shown.
static void readVersion(ffxContext &ctx) {
    ffxQueryGetProviderVersion q{};
    q.header.type = FFX_API_QUERY_DESC_TYPE_GET_PROVIDER_VERSION;
    if (pQuery(&ctx, &q.header) == FFX_API_RETURN_OK && q.versionName) {
        strncpy_s(gFfxVersion, q.versionName, _TRUNCATE);
    }
}

// Depth is near / view depth (the motion shader): reversed-Z with an infinite far plane. FSR wants, for that, the near
// plane as the far value and FLT_MAX as the near one (see the FSR sample).
static constexpr uint32_t kUpscaleFlags = FFX_UPSCALE_ENABLE_DEPTH_INVERTED | FFX_UPSCALE_ENABLE_DEPTH_INFINITE;
static constexpr uint32_t kFrameGenFlags = FFX_FRAMEGENERATION_ENABLE_DEPTH_INVERTED | FFX_FRAMEGENERATION_ENABLE_DEPTH_INFINITE;

// ------------------------------------------------------------------------------------------------ upscaling

static bool ensureUpscale(uint32_t renderW, uint32_t renderH, uint32_t outW, uint32_t outH) {
    if (gUpscale && gUpRenderW == renderW && gUpRenderH == renderH && gUpOutW == outW && gUpOutH == outH) return true;
    bool sameSizes = gUpRenderW == renderW && gUpRenderH == renderH && gUpOutW == outW && gUpOutH == outH;
    if (gUpBroken && sameSizes) return false;
    destroyContext(gUpscale);
    gUpRenderW = renderW;
    gUpRenderH = renderH;
    gUpOutW = outW;
    gUpOutH = outH;
    ffxCreateBackendVKDesc backend = backendDesc();
    ffxCreateContextDescUpscale create{};
    create.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE;
    create.header.pNext = &backend.header;
    create.flags = kUpscaleFlags | (gLogging ? FFX_UPSCALE_ENABLE_DEBUG_CHECKING : 0);
    create.maxRenderSize = {renderW, renderH};
    create.maxUpscaleSize = {outW, outH};
    create.fpMessage = ffxMessage;
    ffxReturnCode_t r = pCreateContext(&gUpscale, &create.header, nullptr);
    if (r != FFX_API_RETURN_OK) {
        setError("FSR upscaling context creation failed: %u (%ux%u -> %ux%u)", r, renderW, renderH, outW, outH);
        gUpscale = nullptr;
        gUpBroken = true;
        return false;
    }
    gUpBroken = false;
    readVersion(gUpscale);
    return true;
}

int fsrUpscale(VkCommandBuffer cb, const FsrUpscale &u) {
    if (!gFfxReady) {
        setError("FSR unavailable (amd_fidelityfx_vk.dll not loaded)");
        return -1;
    }
    const OwnedImage &out = *u.output;
    if (!ensureUpscale(u.renderW, u.renderH, out.width, out.height)) return -1;
    ffxDispatchDescUpscale d{};
    d.header.type = FFX_API_DISPATCH_DESC_TYPE_UPSCALE;
    d.commandList = cb;
    d.color = image(u.color, u.colorFormat, u.renderW, u.renderH, false);
    d.depth = image(*u.depth, false);
    d.motionVectors = image(*u.motion, false);
    if (u.reactive) d.reactive = image(*u.reactive, false);
    d.output = image(out, true);
    d.jitterOffset = {u.jitterX, u.jitterY};
    // The motion vectors are in render pixels already; FSR divides by the render size itself.
    d.motionVectorScale = {1.0f, 1.0f};
    d.renderSize = {u.renderW, u.renderH};
    d.upscaleSize = {out.width, out.height};
    d.enableSharpening = u.sharpness > 0.0f;
    d.sharpness = u.sharpness;
    d.frameTimeDelta = u.frameTimeMs;
    d.preExposure = 1.0f; // the colour is the pack's display-ready LDR image
    d.reset = u.reset;
    d.cameraNear = FLT_MAX;
    d.cameraFar = u.nearZ;
    d.cameraFovAngleVertical = u.fovY;
    d.viewSpaceToMetersFactor = 1.0f; // a block is a metre
    ffxReturnCode_t r = pDispatch(&gUpscale, &d.header);
    if (r != FFX_API_RETURN_OK) {
        setError("FSR upscaling dispatch failed: %u", r);
        return 0;
    }
    return 1;
}

// ------------------------------------------------------------------------------------------------ frame generation

static bool ensureFrameGen(uint32_t w, uint32_t h, VkFormat backFormat, VkFormat hudlessFormat) {
    uint32_t rw = gMotion.width, rh = gMotion.height;
    bool same = gFgDisplayW == w && gFgDisplayH == h && gFgRenderW == rw && gFgRenderH == rh && gFgBackFormat == backFormat
        && gFgHudlessFormat == hudlessFormat;
    if (gFrameGen && same) return true;
    if (gFgBroken && same) return false;
    destroyContext(gFrameGen);
    gFgDisplayW = w;
    gFgDisplayH = h;
    gFgRenderW = rw;
    gFgRenderH = rh;
    gFgBackFormat = backFormat;
    gFgHudlessFormat = hudlessFormat;
    ffxCreateBackendVKDesc backend = backendDesc();
    ffxCreateContextDescFrameGenerationHudless hudless{};
    hudless.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_FRAMEGENERATION_HUDLESS;
    hudless.header.pNext = &backend.header;
    hudless.hudlessBackBufferFormat = ffxApiGetSurfaceFormatVK(hudlessFormat);
    ffxCreateContextDescFrameGeneration create{};
    create.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_FRAMEGENERATION;
    create.header.pNext = &hudless.header;
    create.flags = kFrameGenFlags | (gLogging ? FFX_FRAMEGENERATION_ENABLE_DEBUG_CHECKING : 0);
    create.displaySize = {w, h};
    create.maxRenderSize = {rw, rh};
    create.backBufferFormat = ffxApiGetSurfaceFormatVK(backFormat);
    ffxReturnCode_t r = pCreateContext(&gFrameGen, &create.header, nullptr);
    if (r != FFX_API_RETURN_OK) {
        setError("FSR frame generation context creation failed: %u (%ux%u, render %ux%u, format %d)", r, w, h, rw, rh, backFormat);
        gFrameGen = nullptr;
        gFgBroken = true;
        return false;
    }
    gFgBroken = false;
    if (gLogging) {
        ffxConfigureDescGlobalDebug1 debug{};
        debug.header.type = FFX_API_CONFIGURE_DESC_TYPE_GLOBALDEBUG1;
        debug.fpMessage = ffxMessage;
        debug.debugLevel = FFX_API_CONFIGURE_GLOBALDEBUG_LEVEL_WARNINGS;
        pConfigure(&gFrameGen, &debug.header);
    }
    return true;
}

bool fsrFrameGen(VkCommandBuffer cb, uint64_t frameId, bool reset, float frameTimeMs, const OwnedImage &backbuffer,
                 const OwnedImage *hudless, const OwnedImage &output, const FgCamera &camera) {
    if (!gFfxReady) {
        setError("FSR unavailable (amd_fidelityfx_vk.dll not loaded)");
        return false;
    }
    VkFormat hudlessFormat = hudless ? hudless->format : backbuffer.format;
    if (!ensureFrameGen(backbuffer.width, backbuffer.height, backbuffer.format, hudlessFormat)) return false;

    // FSR's own swapchain is not used: the present thread shows the frames (NO_SWAPCHAIN_CONTEXT_NOTIFY), and the
    // generation is dispatched here rather than from a swapchain callback.
    ffxConfigureDescFrameGeneration config{};
    config.header.type = FFX_API_CONFIGURE_DESC_TYPE_FRAMEGENERATION;
    config.frameGenerationEnabled = true;
    config.allowAsyncWorkloads = false;
    config.flags = FFX_FRAMEGENERATION_FLAG_NO_SWAPCHAIN_CONTEXT_NOTIFY;
    if (hudless) config.HUDLessColor = image(*hudless, false);
    config.frameID = frameId;
    ffxReturnCode_t r = pConfigure(&gFrameGen, &config.header);
    if (r != FFX_API_RETURN_OK) {
        setError("FSR frame generation configure failed: %u", r);
        return false;
    }

    // Prepare: this frame's depth and motion vectors (dilated and kept for the generation).
    ffxDispatchDescFrameGenerationPrepareCameraInfo cameraInfo{};
    cameraInfo.header.type = FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION_PREPARE_CAMERAINFO;
    memcpy(cameraInfo.cameraPosition, camera.pos, 12);
    memcpy(cameraInfo.cameraUp, camera.up, 12);
    memcpy(cameraInfo.cameraRight, camera.right, 12);
    memcpy(cameraInfo.cameraForward, camera.fwd, 12);
    ffxDispatchDescFrameGenerationPrepare prepare{};
    prepare.header.type = FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION_PREPARE;
    prepare.header.pNext = &cameraInfo.header;
    prepare.frameID = frameId;
    prepare.commandList = cb;
    prepare.renderSize = {gMotion.width, gMotion.height};
    prepare.jitterOffset = {camera.jitterX, camera.jitterY};
    prepare.motionVectorScale = {1.0f, 1.0f};
    prepare.frameTimeDelta = frameTimeMs;
    prepare.cameraNear = FLT_MAX;
    prepare.cameraFar = camera.nearZ;
    prepare.cameraFovAngleVertical = camera.fov;
    prepare.viewSpaceToMetersFactor = 1.0f;
    prepare.depth = image(gDlssDepth, false);
    prepare.motionVectors = image(gMotion, false);
    r = pDispatch(&gFrameGen, &prepare.header);
    if (r != FFX_API_RETURN_OK) {
        setError("FSR frame generation prepare failed: %u", r);
        return false;
    }

    ffxDispatchDescFrameGeneration gen{};
    gen.header.type = FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION;
    gen.commandList = cb;
    gen.presentColor = image(backbuffer, false);
    gen.outputs[0] = image(output, true);
    gen.numGeneratedFrames = 1;
    gen.reset = reset;
    gen.backbufferTransferFunction = FFX_API_BACKBUFFER_TRANSFER_FUNCTION_SRGB;
    gen.minMaxLuminance[0] = 0.0f;
    gen.minMaxLuminance[1] = 1.0f;
    gen.frameID = frameId;
    r = pDispatch(&gFrameGen, &gen.header);
    if (r != FFX_API_RETURN_OK) {
        setError("FSR frame generation dispatch failed: %u", r);
        return false;
    }
    return true;
}

// ------------------------------------------------------------------------------------------------ shutdown

void fsrShutdown(bool unload) {
    if (!gFfxReady) return;
    destroyContext(gUpscale);
    destroyContext(gFrameGen);
    gUpBroken = gFgBroken = false;
    gUpRenderW = gUpRenderH = gUpOutW = gUpOutH = 0;
    gFgDisplayW = gFgDisplayH = gFgRenderW = gFgRenderH = 0;
    if (unload) {
        FreeLibrary(gFfxModule);
        gFfxModule = nullptr;
        gFfxReady = false;
    }
}

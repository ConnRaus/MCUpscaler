// DLSS Super Resolution: the motion vector and depth merge passes, the DLSS evaluate and the copy into Minecraft's
// target, all recorded into the command buffer Minecraft is recording. See bridge.h.

#include "bridge.h"

static OwnedBuffer gPackMergeBuffer;
static OwnedBuffer gDistantMergeBuffer;
static OwnedBuffer gBoxBuffer; // moving entities' boxes for the motion vector pass

// ------------------------------------------------------------------------------------------------ frame description

// Per-frame inputs. Must match DlssNative.java (FRAME_* offsets).
struct Frame {
    Tex color;              // 0   world image at the render resolution (jittered while upscaling)
    Tex depth;              // 32  scene depth without the first-person hand (reversed-Z)
    Tex hand;               // 64  depth the hand was drawn into (see DepthCapture)
    Tex output;             // 96  Minecraft's main target (output resolution; the colour image itself when not upscaling)
    float invViewProj[16];  // 128 inverse(unjittered projection * view rotation), column-major
    float prevViewProj[16]; // 192 previous frame's unjittered projection * view rotation
    float camDelta[4];      // 256 camera position - previous camera position
    float objMin[4];        // 272 third person: the player's box relative to the camera
    float objMax[4];        // 288
    float objDelta[4];      // 304 the player's movement since the previous frame
    float jitterX, jitterY; // 320 sub-pixel jitter in render pixels (y down)
    uint32_t reset;         // 328 discard history
    uint32_t quality;       // 332 NVSDK_NGX_PerfQuality_Value the feature is created for
    uint32_t preset;        // 336 NVSDK_NGX_DLSS_Hint_Render_Preset (0 = DLSS default for the mode)
    uint32_t zZeroToOne;    // 340 depth range of the projection
    float frameTimeMs;      // 344
    uint32_t upscale;       // 348 1: DLSS Super Resolution; 0: only motion vectors and depth (for Frame Generation)
    uint64_t boxes;         // 352 moving entities: boxCount x {min, max, delta} float4s relative to the camera (or 0)
    uint32_t boxCount;      // 360
    uint32_t vignette;      // 364 shader pack vignette drawn after DLSS (PackVignette.*, 0 = none)
    float vignetteA;        // 368 its settings
    float vignetteB;        // 372
};
static_assert(sizeof(Frame) == 376, "Frame layout");

// Push constants of the motion vector pass (std430). Must match the GLSL in Shaders.java.
struct MotionPush {
    float invViewProj[16];
    float prevViewProj[16];
    float camDelta[4];
    float objMin[4];
    float objMax[4];
    float objDelta[4];
    uint32_t zZeroToOne;
    uint32_t boxCount;
};
static_assert(sizeof(MotionPush) == 200, "MotionPush layout");

// ------------------------------------------------------------------------------------------------ GPU timing

// Timestamps around this file's per-frame work, read back a few frames later (never waits for the GPU).
// Per frame: 0 start, 1 after the motion vectors, 2 after DLSS, 3 after the copy into Minecraft's target.
static constexpr uint32_t kTimingFrames = 4, kStampsPerFrame = 4;
static VkQueryPool gQueryPool;
static double gTimestampPeriodNs;
static uint64_t gTimingFrame;           // frames recorded so far
static bool gTimingWritten[kTimingFrames];
static float gGpuTimes[3];              // smoothed ms: motion vectors, DLSS, copy

void initTiming() {
    VkPhysicalDeviceProperties props;
    p_vkGetPhysicalDeviceProperties(gPhysical, &props);
    if (props.limits.timestampPeriod <= 0.0f) return;
    gTimestampPeriodNs = props.limits.timestampPeriod;
    VkQueryPoolCreateInfo qpi{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
    qpi.queryType = VK_QUERY_TYPE_TIMESTAMP;
    qpi.queryCount = kTimingFrames * kStampsPerFrame;
    if (p_vkCreateQueryPool(gDevice, &qpi, nullptr, &gQueryPool) != VK_SUCCESS) gQueryPool = VK_NULL_HANDLE;
}

// Collects the oldest frame's results (if ready) and resets its queries for this frame.
static void beginTiming(VkCommandBuffer cb) {
    if (!gQueryPool) return;
    uint32_t slot = (uint32_t)(gTimingFrame % kTimingFrames);
    if (gTimingWritten[slot]) {
        uint64_t stamps[kStampsPerFrame * 2];
        if (p_vkGetQueryPoolResults(gDevice, gQueryPool, slot * kStampsPerFrame, kStampsPerFrame, sizeof(stamps), stamps,
                sizeof(uint64_t) * 2, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT) == VK_SUCCESS) {
            bool all = true;
            for (uint32_t i = 0; i < kStampsPerFrame; i++) all = all && stamps[i * 2 + 1] != 0;
            if (all) {
                for (int i = 0; i < 3; i++) {
                    float ms = (float)((double)(stamps[(i + 1) * 2] - stamps[i * 2]) * gTimestampPeriodNs / 1.0e6);
                    gGpuTimes[i] = gGpuTimes[i] == 0.0f ? ms : gGpuTimes[i] * 0.95f + ms * 0.05f;
                }
            }
        }
    }
    p_vkCmdResetQueryPool(cb, gQueryPool, slot * kStampsPerFrame, kStampsPerFrame);
    gTimingWritten[slot] = false;
}

static void stamp(VkCommandBuffer cb, uint32_t index) {
    if (!gQueryPool) return;
    uint32_t slot = (uint32_t)(gTimingFrame % kTimingFrames);
    p_vkCmdWriteTimestamp(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, gQueryPool, slot * kStampsPerFrame + index);
    if (index == kStampsPerFrame - 1) {
        gTimingWritten[slot] = true;
        gTimingFrame++;
    }
}

// Smoothed GPU milliseconds of the motion vector pass, the DLSS evaluate and the copy into Minecraft's target.
EXPORT int dlss_gpu_times(float *out) {
    for (int i = 0; i < 3; i++) out[i] = gGpuTimes[i];
    return gQueryPool ? 1 : 0;
}

// ------------------------------------------------------------------------------------------------ DLSS Super Resolution

OwnedImage gMotion;    // RG16F motion vectors at the render resolution
OwnedImage gDlssDepth; // R32F depth at the render resolution (scene depth with the hand on top)
static OwnedImage gBiasMask;  // R8 at the render resolution: 1 where DLSS should trust the current frame (animated entities)
static OwnedImage gOutput;    // DLSS output (storage image) at the output resolution
static OwnedImage gPost;      // gOutput with the shader pack's vignette
static OwnedImage gExposure;  // 1x1 R32F, 1.0: the colour is display-ready LDR (see ensureDlss)
static constexpr uint32_t kMaxBoxes = 64;
OwnedImage *gLastOutput; // gOutput or gPost
static NVSDK_NGX_Handle *gDlss;
static uint32_t gDlssInW, gDlssInH, gDlssOutW, gDlssOutH, gDlssQuality, gDlssPreset;
static VkFormat gDlssOutFormat;

static void releaseDlss() {
    if (gDlss) {
        deviceWaitIdle();
        NVSDK_NGX_VULKAN_ReleaseFeature(gDlss);
        gDlss = nullptr;
    }
}

static bool ensureDlss(VkCommandBuffer cb, const Frame &f, VkFormat outFormat) {
    uint32_t inW = f.color.width, inH = f.color.height, outW = f.output.width, outH = f.output.height;
    if (gDlss && gDlssInW == inW && gDlssInH == inH && gDlssOutW == outW && gDlssOutH == outH && gDlssQuality == f.quality
        && gDlssPreset == f.preset && gDlssOutFormat == outFormat) {
        return true;
    }
    releaseDlss();
    unsigned int preset = f.preset;
    NVSDK_NGX_Parameter_SetUI(gParams, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA, preset);
    NVSDK_NGX_Parameter_SetUI(gParams, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality, preset);
    NVSDK_NGX_Parameter_SetUI(gParams, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced, preset);
    NVSDK_NGX_Parameter_SetUI(gParams, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance, preset);
    NVSDK_NGX_Parameter_SetUI(gParams, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance, preset);
    NVSDK_NGX_Parameter_SetUI(gParams, NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraQuality, preset);

    NVSDK_NGX_DLSS_Create_Params create{};
    create.Feature.InWidth = inW;
    create.Feature.InHeight = inH;
    create.Feature.InTargetWidth = outW;
    create.Feature.InTargetHeight = outH;
    create.Feature.InPerfQualityValue = (NVSDK_NGX_PerfQuality_Value)f.quality;
    // LDR colour, motion vectors at the render resolution without jitter, reversed-Z depth.
    // No auto exposure: the colour is the pack's tonemapped LDR image, so exposure is a fixed 1.0 (gExposure). DLSS's
    // own exposure estimate drifts with the scene and made it drop its history in bright distant terrain (pulsing).
    create.InFeatureCreateFlags = NVSDK_NGX_DLSS_Feature_Flags_MVLowRes | NVSDK_NGX_DLSS_Feature_Flags_DepthInverted;
    create.InEnableOutputSubrects = false;
    NVSDK_NGX_Result r = NGX_VULKAN_CREATE_DLSS_EXT(cb, 1, 1, &gDlss, gParams, &create);
    if (NVSDK_NGX_FAILED(r)) {
        setError("DLSS feature creation failed: 0x%08x (%ux%u -> %ux%u, quality %u, preset %u)", r, inW, inH, outW, outH, f.quality, f.preset);
        gDlss = nullptr;
        return false;
    }
    gDlssInW = inW; gDlssInH = inH; gDlssOutW = outW; gDlssOutH = outH;
    gDlssQuality = f.quality; gDlssPreset = f.preset; gDlssOutFormat = outFormat;
    return true;
}

// Copies (same format) or blits (other format) a w x h colour image; both in GENERAL layout.
static void copyImage(VkCommandBuffer cb, VkImage src, VkFormat srcFormat, VkImage dst, VkFormat dstFormat, uint32_t w, uint32_t h) {
    if (srcFormat == dstFormat) {
        VkImageCopy copy{};
        copy.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        copy.extent = {w, h, 1};
        p_vkCmdCopyImage(cb, src, VK_IMAGE_LAYOUT_GENERAL, dst, VK_IMAGE_LAYOUT_GENERAL, 1, &copy);
    } else {
        VkImageBlit blit{};
        blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.srcOffsets[1] = {(int32_t)w, (int32_t)h, 1};
        blit.dstOffsets[1] = {(int32_t)w, (int32_t)h, 1};
        p_vkCmdBlitImage(cb, src, VK_IMAGE_LAYOUT_GENERAL, dst, VK_IMAGE_LAYOUT_GENERAL, 1, &blit, VK_FILTER_NEAREST);
    }
}

static void copyToTarget(VkCommandBuffer cb, const OwnedImage &src, const Tex &dst) {
    copyImage(cb, src.image, src.format, (VkImage)dst.image, (VkFormat)dst.format, dst.width, dst.height);
}

// Records the motion vector pass into commandBuffer and, with f->upscale, DLSS Super Resolution writing into f->output.
// Without upscaling (Frame Generation at the native resolution) only the motion vectors and depth are made, and the world
// image is kept as Frame Generation's hudless image.
// Returns 1 on success, 0 if this frame failed (see dlss_last_error), -1 if DLSS is unusable.
EXPORT int dlss_upscale(uint64_t commandBuffer, const Frame *f) {
    bool upscale = f->upscale != 0;
    if (!gNgxReady || !gPassesReady || (upscale && !gDlssAvailable)) {
        setError("DLSS not initialised");
        return -1;
    }
    VkCommandBuffer cb = (VkCommandBuffer)commandBuffer;
    uint32_t inW = f->color.width, inH = f->color.height, outW = f->output.width, outH = f->output.height;
    if (inW == 0 || inH == 0 || outW < 32 || outH < 32 || f->depth.width != inW || f->depth.height != inH
        || f->hand.width != inW || f->hand.height != inH || (!upscale && (outW != inW || outH != inH))) {
        setError("mismatched inputs: colour %ux%u depth %ux%u hand %ux%u output %ux%u", inW, inH, f->depth.width, f->depth.height,
            f->hand.width, f->hand.height, outW, outH);
        return 0;
    }
    // The DLSS output must be a storage image; Minecraft's targets aren't, so DLSS writes into our own and it's copied over.
    // RGBA8 (LDR) whatever Minecraft's target is: the copy below blits when the formats differ.
    VkFormat outFormat = VK_FORMAT_R8G8B8A8_UNORM;
    VkImageUsageFlags sampledStorage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
    VkImageUsageFlags transfer = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (!ensureImage(gMotion, VK_FORMAT_R16G16_SFLOAT, inW, inH, sampledStorage | transfer, "motion vectors")
        || !ensureImage(gDlssDepth, VK_FORMAT_R32_SFLOAT, inW, inH, sampledStorage | transfer, "DLSS depth")
        || !ensureImage(gBiasMask, VK_FORMAT_R8_UNORM, inW, inH, sampledStorage, "DLSS current-colour bias mask")
        || !ensureImage(gOutput, outFormat, outW, outH, sampledStorage | transfer, "DLSS output")
        || (upscale && f->vignette && !ensureImage(gPost, outFormat, outW, outH, sampledStorage | transfer, "vignetted output"))
        || (upscale && !ensureImage(gExposure, VK_FORMAT_R32_SFLOAT, 1, 1, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, "DLSS exposure"))
        || !ensureBuffer(gBoxBuffer, kMaxBoxes * 48)) {
        return 0;
    }
    uint32_t boxCount = f->boxes ? (f->boxCount < kMaxBoxes ? f->boxCount : kMaxBoxes) : 0;

    globalBarrier(cb);
    beginTiming(cb);
    stamp(cb, 0);
    transitionFresh(cb, gMotion);
    transitionFresh(cb, gDlssDepth);
    transitionFresh(cb, gBiasMask);
    transitionFresh(cb, gOutput);
    if (gPost.image) transitionFresh(cb, gPost);
    if (gExposure.fresh) {
        transitionFresh(cb, gExposure);
        VkClearColorValue one{};
        one.float32[0] = 1.0f;
        VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        p_vkCmdClearColorImage(cb, gExposure.image, VK_IMAGE_LAYOUT_GENERAL, &one, 1, &range);
    }
    if (boxCount) {
        p_vkCmdUpdateBuffer(cb, gBoxBuffer.buffer, 0, boxCount * 48, (const void *)f->boxes);
        globalBarrier(cb);
    }

    MotionPush push{};
    memcpy(push.invViewProj, f->invViewProj, sizeof(push.invViewProj));
    memcpy(push.prevViewProj, f->prevViewProj, sizeof(push.prevViewProj));
    memcpy(push.camDelta, f->camDelta, sizeof(push.camDelta));
    memcpy(push.objMin, f->objMin, sizeof(push.objMin));
    memcpy(push.objMax, f->objMax, sizeof(push.objMax));
    memcpy(push.objDelta, f->objDelta, sizeof(push.objDelta));
    push.zZeroToOne = f->zZeroToOne;
    push.boxCount = boxCount;
    Resource motionRes[6] = {{(VkImageView)f->depth.view}, {(VkImageView)f->hand.view}, {gMotion.view}, {gDlssDepth.view},
        {VK_NULL_HANDLE, gBoxBuffer.buffer}, {gBiasMask.view}};
    dispatch(cb, PASS_MOTION, motionRes, &push, sizeof(push), inW, inH);
    globalBarrier(cb);
    stamp(cb, 1);

    if (!upscale) {
        // Minecraft's target holds the world without the HUD right now: Frame Generation's hudless image.
        copyImage(cb, (VkImage)f->color.image, (VkFormat)f->color.format, gOutput.image, gOutput.format, outW, outH);
        globalBarrier(cb);
        gLastOutput = &gOutput;
        stamp(cb, 2);
        stamp(cb, 3);
        return 1;
    }

    if (!ensureDlss(cb, *f, outFormat)) {
        stamp(cb, 2);
        stamp(cb, 3);
        return -1;
    }
    NVSDK_NGX_Resource_VK color = resourceOf(f->color.image, f->color.view, (VkFormat)f->color.format, inW, inH, false);
    NVSDK_NGX_Resource_VK depth = resourceOf((uint64_t)gDlssDepth.image, (uint64_t)gDlssDepth.view, gDlssDepth.format, inW, inH, true);
    NVSDK_NGX_Resource_VK motion = resourceOf((uint64_t)gMotion.image, (uint64_t)gMotion.view, gMotion.format, inW, inH, true);
    NVSDK_NGX_Resource_VK output = resourceOf((uint64_t)gOutput.image, (uint64_t)gOutput.view, gOutput.format, outW, outH, true);
    NVSDK_NGX_Resource_VK exposure = resourceOf((uint64_t)gExposure.image, (uint64_t)gExposure.view, gExposure.format, 1, 1, false);
    NVSDK_NGX_Resource_VK biasMask = resourceOf((uint64_t)gBiasMask.image, (uint64_t)gBiasMask.view, gBiasMask.format, inW, inH, false);

    NVSDK_NGX_VK_DLSS_Eval_Params eval{};
    eval.Feature.pInColor = &color;
    eval.Feature.pInOutput = &output;
    eval.pInDepth = &depth;
    eval.pInMotionVectors = &motion;
    eval.InJitterOffsetX = f->jitterX;
    eval.InJitterOffsetY = f->jitterY;
    eval.InRenderSubrectDimensions = {inW, inH};
    eval.InReset = f->reset ? 1 : 0;
    eval.InMVScaleX = 1.0f;
    eval.InMVScaleY = 1.0f;
    eval.InFrameTimeDeltaInMsec = f->frameTimeMs;
    eval.pInExposureTexture = &exposure;
    eval.InPreExposure = 1.0f;
    eval.InExposureScale = 1.0f;
    // Animated entities (legs, heads, turning) have no motion vectors of their own: DLSS leans on the current frame there.
    eval.pInBiasCurrentColorMask = &biasMask;
    NVSDK_NGX_Result r = NGX_VULKAN_EVALUATE_DLSS_EXT(cb, gDlss, gParams, &eval);
    globalBarrier(cb);
    stamp(cb, 2);
    if (NVSDK_NGX_FAILED(r)) {
        setError("DLSS evaluate failed: 0x%08x", r);
        stamp(cb, 3);
        return 0;
    }
    if (f->vignette) {
        Resource postRes[2] = {{gOutput.view}, {gPost.view}};
        struct { uint32_t kind; float a, b; } post = {f->vignette, f->vignetteA, f->vignetteB};
        dispatch(cb, PASS_POST, postRes, &post, sizeof(post), outW, outH);
        globalBarrier(cb);
        copyToTarget(cb, gPost, f->output);
        gLastOutput = &gPost;
    } else {
        copyToTarget(cb, gOutput, f->output);
        gLastOutput = &gOutput;
    }
    globalBarrier(cb);
    stamp(cb, 3);
    return 1;
}

// ------------------------------------------------------------------------------------------------ depth merges

// Shader packs draw the hand into the world depth before water, the block outline and the rest: writes fin everywhere
// except the hand (pre there) into pre, so the scene depth keeps translucents and differs from fin exactly at the hand.
EXPORT int dlss_merge_pack_depth(uint64_t commandBuffer, const Tex *pre, const Tex *post, const Tex *fin, const Tex *preTl, const Tex *postTl) {
    if (!gPassesReady) {
        setError("shaders not loaded");
        return -1;
    }
    VkCommandBuffer cb = (VkCommandBuffer)commandBuffer;
    uint32_t w = pre->width, h = pre->height;
    if ((VkFormat)pre->format != VK_FORMAT_D32_SFLOAT || post->width != w || post->height != h || fin->width != w || fin->height != h
        || preTl->width != w || preTl->height != h || postTl->width != w || postTl->height != h) {
        setError("pack depth merge: mismatched depth textures");
        return 0;
    }
    OwnedBuffer &buffer = gPackMergeBuffer;
    if (!ensureBuffer(buffer, (VkDeviceSize)w * h * 4)) return 0;
    globalBarrier(cb);
    Resource res[6] = {{(VkImageView)pre->view}, {(VkImageView)post->view}, {(VkImageView)fin->view}, {(VkImageView)preTl->view},
        {(VkImageView)postTl->view}, {VK_NULL_HANDLE, buffer.buffer}};
    uint32_t size[2] = {w, h};
    dispatch(cb, PASS_PACK_MERGE, res, size, sizeof(size), w, h);
    globalBarrier(cb);
    VkBufferImageCopy copy{};
    copy.imageSubresource = {VK_IMAGE_ASPECT_DEPTH_BIT, 0, 0, 1};
    copy.imageExtent = {w, h, 1};
    p_vkCmdCopyBufferToImage(cb, buffer.buffer, (VkImage)pre->image, VK_IMAGE_LAYOUT_GENERAL, 1, &copy);
    globalBarrier(cb);
    return 1;
}

// Distant Horizons terrain drawn by a shader pack has its own depth buffer (its own projection) and leaves the world
// depth at 0 (sky): puts it into the scene depth, in the game's projection (depth = (dh - b) / a).
EXPORT int dlss_merge_distant_depth(uint64_t commandBuffer, const Tex *scene, const Tex *dh, float pairA, float pairB) {
    if (!gPassesReady) {
        setError("shaders not loaded");
        return -1;
    }
    VkCommandBuffer cb = (VkCommandBuffer)commandBuffer;
    uint32_t w = scene->width, h = scene->height;
    if ((VkFormat)scene->format != VK_FORMAT_D32_SFLOAT || dh->width != w || dh->height != h || pairA == 0.0f) {
        setError("distant depth merge: mismatched textures (%ux%u fmt %u, far %ux%u fmt %u)", w, h, scene->format, dh->width, dh->height, dh->format);
        return 0;
    }
    OwnedBuffer &buffer = gDistantMergeBuffer;
    if (!ensureBuffer(buffer, (VkDeviceSize)w * h * 4)) return 0;
    globalBarrier(cb);
    Resource res[3] = {{(VkImageView)scene->view}, {(VkImageView)dh->view}, {VK_NULL_HANDLE, buffer.buffer}};
    struct { uint32_t w, h; float a, b; } push = {w, h, pairA, pairB};
    dispatch(cb, PASS_DISTANT_MERGE, res, &push, sizeof(push), w, h);
    globalBarrier(cb);
    VkBufferImageCopy copy{};
    copy.imageSubresource = {VK_IMAGE_ASPECT_DEPTH_BIT, 0, 0, 1};
    copy.imageExtent = {w, h, 1};
    p_vkCmdCopyBufferToImage(cb, buffer.buffer, (VkImage)scene->image, VK_IMAGE_LAYOUT_GENERAL, 1, &copy);
    globalBarrier(cb);
    return 1;
}
// Frees everything Super Resolution created (the device is idle).
void srShutdown() {
    releaseDlss();
    for (OwnedImage *image : {&gMotion, &gDlssDepth, &gBiasMask, &gOutput, &gExposure, &gPost}) destroyImage(*image);
    for (OwnedBuffer *buffer : {&gBoxBuffer, &gPackMergeBuffer, &gDistantMergeBuffer}) destroyBuffer(*buffer);
    if (gQueryPool) p_vkDestroyQueryPool(gDevice, gQueryPool, nullptr);
    gQueryPool = VK_NULL_HANDLE;
}

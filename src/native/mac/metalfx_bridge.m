// Metal side of the mod: MetalFX and AMD FSR 3.1 upscaling, frame generation and its presenter.
//
// Java hands over Metal objects that MoltenVK exports through VK_EXT_metal_objects (device, textures, and the
// MTLSharedEvent behind a Vulkan timeline semaphore). Work runs on our own queues and is ordered against Minecraft's
// Vulkan frame through that event, so nothing waits on the CPU. Called from the render thread only, except the
// frame generation presenter, which has its own thread.

#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#import <QuartzCore/QuartzCore.h>
#import <ImageIO/ImageIO.h>
#include <errno.h>
#include <math.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "fsr3_shaders.h"

static id<MTLDevice> gDevice;
static id<MTLCommandQueue> gQueue;
static id<MTLFXSpatialScaler> gScaler;
static id<MTLTexture> gOutput;
static NSUInteger gInW, gInH, gOutW, gOutH;
static MTLPixelFormat gInFmt, gOutFmt;

static char gError[1024];
static atomic_int gGpuErrorCount;

static void setError(NSString *msg) {
    strlcpy(gError, msg.UTF8String ?: "unknown error", sizeof(gError));
}

// Copies the last error message into buf. Returns its length.
// GPU time spent in our command buffers, for profiling (microseconds, and number of buffers).
static atomic_llong gGpuMicros;
static atomic_llong gGpuBuffers;

// Per-stage profiling (MFX_PROFILE=1 in the environment): stages are committed as separate command buffers.
static BOOL gProfile;
static atomic_llong gStageMicros[4];
static atomic_llong gStageCount[4];

static void addStageTimer(id<MTLCommandBuffer> cb, int stage) {
    [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
        if (done.GPUEndTime > done.GPUStartTime) {
            atomic_fetch_add(&gStageMicros[stage], (long long)((done.GPUEndTime - done.GPUStartTime) * 1e6));
            atomic_fetch_add(&gStageCount[stage], 1);
        }
    }];
}

// Average GPU microseconds of a profiling stage since the last call (0 motion, 1 scaler, 2 output), or -1.
int mfx_take_stage_micros(int stage) {
    if (stage < 0 || stage > 3) return -1;
    long long micros = atomic_exchange(&gStageMicros[stage], 0);
    long long count = atomic_exchange(&gStageCount[stage], 0);
    return count > 0 ? (int)(micros / count) : -1;
}

// In profiling mode, ends the current stage's command buffer and starts a new one.
static id<MTLCommandBuffer> splitStage(id<MTLCommandBuffer> cb, int stage) {
    if (!gProfile) return cb;
    addStageTimer(cb, stage);
    [cb commit];
    return [gQueue commandBuffer];
}

static void addCompletionHandler(id<MTLCommandBuffer> cb) {
    [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
        if (done.error != nil) {
            atomic_fetch_add(&gGpuErrorCount, 1);
            setError([NSString stringWithFormat:@"GPU error: %@", done.error.localizedDescription]);
        } else if (done.GPUEndTime > done.GPUStartTime) {
            atomic_fetch_add(&gGpuMicros, (long long)((done.GPUEndTime - done.GPUStartTime) * 1e6));
            atomic_fetch_add(&gGpuBuffers, 1);
        }
    }];
}

// The wait for Vulkan's world pass goes in its own command buffer, so the GPU time measured for the
// work buffer is only our own work (same queue, so ordering is preserved).
static void encodeWait(id<MTLSharedEvent> event, uint64_t value) {
    id<MTLCommandBuffer> wait = [gQueue commandBufferWithUnretainedReferences];
    wait.label = @"MetalFX wait for world";
    [wait encodeWaitForEvent:event value:value];
    [wait commit];
}

// Returns the average GPU time per command buffer in microseconds since the last call, or -1 if none completed.
int mfx_take_gpu_micros(void) {
    long long micros = atomic_exchange(&gGpuMicros, 0);
    long long count = atomic_exchange(&gGpuBuffers, 0);
    return count > 0 ? (int)(micros / count) : -1;
}

int mfx_last_error(char *buf, int len) {
    if (len <= 0) return 0;
    strlcpy(buf, gError, (size_t)len);
    return (int)strlen(buf);
}

int mfx_gpu_error_count(void) {
    return atomic_load(&gGpuErrorCount);
}

// Returns 1 if MetalFX spatial scaling is usable on this device, 0 if not, -1 on error.
int mfx_init(uintptr_t mtlDevice) {
    @autoreleasepool {
        if (mtlDevice == 0) {
            setError(@"null MTLDevice");
            return -1;
        }
        gDevice = (__bridge id<MTLDevice>)(void *)mtlDevice;
        if (![MTLFXSpatialScalerDescriptor supportsDevice:gDevice]) {
            setError([NSString stringWithFormat:@"MetalFX spatial scaler not supported on %@", gDevice.name]);
            return 0;
        }
        gQueue = [gDevice newCommandQueue];
        gProfile = getenv("MFX_PROFILE") != NULL;
        gQueue.label = @"MetalFX Minecraft";
        if (gQueue == nil) {
            setError(@"failed to create MTLCommandQueue");
            return -1;
        }
        return 1;
    }
}

static BOOL ensureScaler(id<MTLTexture> in, id<MTLTexture> dst) {
    if (gScaler != nil && gInW == in.width && gInH == in.height && gOutW == dst.width && gOutH == dst.height
        && gInFmt == in.pixelFormat && gOutFmt == dst.pixelFormat) {
        return YES;
    }
    gScaler = nil;
    gOutput = nil;

    MTLFXSpatialScalerDescriptor *desc = [MTLFXSpatialScalerDescriptor new];
    desc.inputWidth = in.width;
    desc.inputHeight = in.height;
    desc.outputWidth = dst.width;
    desc.outputHeight = dst.height;
    desc.colorTextureFormat = in.pixelFormat;
    desc.outputTextureFormat = dst.pixelFormat;
    // Minecraft's main target holds display-referred (gamma encoded) 8-bit color.
    desc.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;

    id<MTLFXSpatialScaler> scaler = [desc newSpatialScalerWithDevice:gDevice];
    if (scaler == nil) {
        setError([NSString stringWithFormat:@"newSpatialScalerWithDevice failed (%lux%lu fmt %lu -> %lux%lu fmt %lu)",
                  (unsigned long)in.width, (unsigned long)in.height, (unsigned long)in.pixelFormat,
                  (unsigned long)dst.width, (unsigned long)dst.height, (unsigned long)dst.pixelFormat]);
        return NO;
    }

    MTLTextureUsage required = scaler.colorTextureUsage;
    if ((in.usage & required) != required) {
        setError([NSString stringWithFormat:@"input texture usage 0x%lx lacks MetalFX-required 0x%lx",
                  (unsigned long)in.usage, (unsigned long)required]);
        return NO;
    }

    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:dst.pixelFormat
                                                                                 width:dst.width
                                                                                height:dst.height
                                                                             mipmapped:NO];
    td.usage = scaler.outputTextureUsage | MTLTextureUsageShaderRead;
    td.storageMode = MTLStorageModePrivate;
    id<MTLTexture> output = [gDevice newTextureWithDescriptor:td];
    if (output == nil) {
        setError(@"failed to allocate MetalFX output texture");
        return NO;
    }
    output.label = @"MetalFX Output";

    gScaler = scaler;
    gOutput = output;
    gInW = in.width; gInH = in.height; gOutW = dst.width; gOutH = dst.height;
    gInFmt = in.pixelFormat; gOutFmt = dst.pixelFormat;
    return YES;
}

static void encodeOutput(id<MTLCommandBuffer> cb, id<MTLTexture> source, id<MTLTexture> dst, float sharpness);
static BOOL ensureMotionPipeline(void);

// Encodes: wait(event >= waitValue) -> MetalFX spatial upscale in -> sharpen into dst -> signal(event = signalValue).
//
// Returns 1 if a command buffer that waits on waitValue and signals signalValue was committed.
// The caller must then make Vulkan wait for signalValue. Returns 0 when the upscale itself could
// not run but the wait/signal pair was still committed (dst is left untouched), and -1 if nothing
// was committed at all (caller must not wait).
int mfx_upscale(uintptr_t inTex, uintptr_t dstTex, uintptr_t sharedEvent, uint64_t waitValue, uint64_t signalValue, float sharpness) {
    @autoreleasepool {
        if (gQueue == nil || sharedEvent == 0) {
            setError(@"bridge not initialised");
            return -1;
        }
        id<MTLSharedEvent> event = (__bridge id<MTLSharedEvent>)(void *)sharedEvent;
        id<MTLCommandBuffer> cb = [gQueue commandBuffer];
        if (cb == nil) {
            setError(@"failed to create MTLCommandBuffer");
            return -1;
        }
        cb.label = @"MetalFX Upscale";
        encodeWait(event, waitValue);

        int result = 0;
        id<MTLTexture> in = (__bridge id<MTLTexture>)(void *)inTex;
        id<MTLTexture> dst = (__bridge id<MTLTexture>)(void *)dstTex;
        if (in != nil && dst != nil && ensureScaler(in, dst) && ensureMotionPipeline()) {
            gScaler.colorTexture = in;
            gScaler.outputTexture = gOutput;
            gScaler.inputContentWidth = in.width;
            gScaler.inputContentHeight = in.height;
            [gScaler encodeToCommandBuffer:cb];
            encodeOutput(cb, gOutput, dst, sharpness);
            result = 1;
        }

        [cb encodeSignalEvent:event value:signalValue];
        if (gProfile) addStageTimer(cb, 2);
        addCompletionHandler(cb);
        [cb commit];
        return result;
    }
}

static id<MTLLibrary> gLibrary;
static id<MTLRenderPipelineState> gVignettePipeline;
static MTLPixelFormat gVignetteFormat;
static id<MTLTexture> gVignetteSource;

// Draws the shader pack's vignette over dstTex in place (see VIGNETTE_MSL). Returns 1, or -1 on failure.
int mfx_vignette(uintptr_t dstTex, uintptr_t sharedEvent, uint64_t waitValue, uint64_t signalValue, uint32_t kind, float a, float b) {
    @autoreleasepool {
        if (gQueue == nil || sharedEvent == 0) {
            setError(@"bridge not initialised");
            return -1;
        }
        id<MTLTexture> dst = (__bridge id<MTLTexture>)(void *)dstTex;
        if (dst == nil || !ensureMotionPipeline()) return -1;
        if (gVignettePipeline == nil || gVignetteFormat != dst.pixelFormat) {
            MTLRenderPipelineDescriptor *rp = [MTLRenderPipelineDescriptor new];
            rp.label = @"Shader pack vignette";
            rp.vertexFunction = [gLibrary newFunctionWithName:@"fullscreen_vs"];
            rp.fragmentFunction = [gLibrary newFunctionWithName:@"vignette_fs"];
            rp.colorAttachments[0].pixelFormat = dst.pixelFormat;
            NSError *error = nil;
            gVignettePipeline = [gDevice newRenderPipelineStateWithDescriptor:rp error:&error];
            if (gVignettePipeline == nil) {
                setError([NSString stringWithFormat:@"vignette pipeline failed: %@", error.localizedDescription]);
                return -1;
            }
            gVignetteFormat = dst.pixelFormat;
        }
        // The image can't be read and drawn at once: the vignette reads a copy.
        if (gVignetteSource == nil || gVignetteSource.width != dst.width || gVignetteSource.height != dst.height
            || gVignetteSource.pixelFormat != dst.pixelFormat) {
            MTLTextureDescriptor *desc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:dst.pixelFormat
                width:dst.width height:dst.height mipmapped:NO];
            desc.usage = MTLTextureUsageShaderRead;
            desc.storageMode = MTLStorageModePrivate;
            gVignetteSource = [gDevice newTextureWithDescriptor:desc];
            if (gVignetteSource == nil) {
                setError(@"failed to create the vignette texture");
                return -1;
            }
        }
        id<MTLSharedEvent> event = (__bridge id<MTLSharedEvent>)(void *)sharedEvent;
        id<MTLCommandBuffer> cb = [gQueue commandBuffer];
        if (cb == nil) {
            setError(@"failed to create MTLCommandBuffer");
            return -1;
        }
        cb.label = @"Shader pack vignette";
        encodeWait(event, waitValue);
        id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
        [blit copyFromTexture:dst toTexture:gVignetteSource];
        [blit endEncoding];
        MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
        pass.colorAttachments[0].texture = dst;
        pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
        pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:pass];
        [re setRenderPipelineState:gVignettePipeline];
        [re setFragmentTexture:gVignetteSource atIndex:0];
        struct { uint32_t kind; float a, b; } params = {kind, a, b};
        [re setFragmentBytes:&params length:sizeof params atIndex:0];
        [re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [re endEncoding];
        [cb encodeSignalEvent:event value:signalValue];
        addCompletionHandler(cb);
        [cb commit];
        return 1;
    }
}

// ---------------------------------------------------------------------------------------------
// Temporal upscaling
// ---------------------------------------------------------------------------------------------

// Must match TemporalParams in WorldUpscaler.java (all 4-byte fields, std layout).
typedef struct {
    float curInvViewProj[16];   // inverse(unjittered projection * view rotation), current frame (column-major)
    float prevViewProj[16];     // unjittered projection * view rotation, previous frame (column-major)
    float camDelta[4];          // current camera position - previous camera position (xyz)
    float jitterX, jitterY;     // jitter (in input pixels) handed to MetalFX
    uint32_t reset;             // discard history
    uint32_t zZeroToOne;        // depth range of the projection
    uint32_t flipY;             // texture row 0 = NDC y +1 (instead of -1)
    uint32_t debugView;         // 1 = write motion vectors to the output instead of upscaling
    float motionScaleX, motionScaleY; // multiplier for the motion vectors handed to MetalFX
    uint32_t skipScaler;        // debugging: skip the MetalFX encode
    float sharpness;            // RCAS sharpening strength after the temporal scaler (0 = plain copy)
    uint32_t kind;              // 0 = MetalFX temporal scaler, 4 = AMD FSR 3.1
    uint32_t pad;
    float camFrac[4];           // camera position minus its floor (xyz)
    int32_t camInt[4];          // floor of the camera position (xyz)
    uint32_t outSize[2];        // output size (filled in natively)
    uint32_t pad2[2];
    float projM22, projM32;     // FSR: unjittered projection terms (view depth = m32 / (device depth + m22))
    float tanHalfFovX, tanHalfFovY;
    float frameTimeMs;          // FSR: time since the previous frame
    uint32_t pad3[3];
    float objMin[4], objMax[4]; // third person: the player's box relative to the camera (min > max = none)
    float objDelta[4];          // the player's movement since the previous frame
    uint32_t handMotion;        // bits: the right (1) / left (2) arm's matrices below are valid (HandMotion.java)
    uint32_t pad4[3];
    float handClipToLocal[2][16];     // per arm: inverse(hand projection * model-view * arm pose), unjittered
    float prevHandLocalToClip[2][16]; // per arm: the previous frame's hand projection * model-view * arm pose
} MfxTemporalParams;
_Static_assert(sizeof(MfxTemporalParams) == 592, "TemporalParams layout");

// The shader pack's vignette, taken out of the pack (VitrailCompat) and drawn after upscaling and after frame generation
// captured the world image, so frame generation doesn't move it with the world. Same as the Windows post pass (POST in
// Shaders.java): kind = a PackVignette shape (0 = none), a and b its settings; display-encoded colour in and out.
#define VIGNETTE_MSL "\
struct VignetteParams { uint kind; float a; float b; };\n\
static float3 packVignette(float3 c, uint2 gid, float2 size, uint kind, float a, float b) {\n\
    if (kind == 0) return c;\n\
    float2 uv = (float2(gid) + 0.5) / size;\n\
    float2 d = uv - 0.5;\n\
    float v = 1.0;\n\
    bool lin = true;\n\
    if (kind == 1) { float s = length(d); s *= s * 0.3535 + 0.75; v = 1.0 - s * a; }\n\
    else if (kind == 2) { float2 q = d * 2.0; q.x *= mix(1.0, size.x / size.y, b); float rf = dot(q, q) * a * a + 1.0; v = 1.0 / (rf * rf); }\n\
    else if (kind == 3) { v = pow(max(16.0 * uv.x * uv.y * (1.0 - uv.x) * (1.0 - uv.y), 0.0), 0.08 * a); }\n\
    else if (kind == 4) { lin = false; v = 1.0 - dot(d, d) * (1.0 - dot(c, float3(0.2125, 0.7154, 0.0721))); }\n\
    v = saturate(v);\n\
    float3 l = pow(max(c, 0.0), 2.2);\n\
    if (kind == 1) { float3 x = l * rsqrt(max(1.0 - l * l, 1e-4)) * v; l = x * rsqrt(x * x + 1.0); } else l *= v;\n\
    return saturate(lin ? pow(l, 1.0 / 2.2) : c * v);\n\
}\n" HAND_MSL

// The first-person hands' motion (pixels, current -> previous; see HandMotion.java). The hands are drawn with their own
// projection and pose, so the camera's motion doesn't fit them: the hand point at ndc with device depth hd is taken back
// through this frame's matrices of the arm drawn on that half of the screen and projected with the previous frame's.
// arms = 0 (no matrices): no motion.
#define HAND_MSL "\
static float2 handMotion(uint arms, float4x4 toLocalR, float4x4 toLocalL, float4x4 prevR, float4x4 prevL, float2 ndc, float hd,\n\
                         bool zZeroToOne, bool flipY, float2 uv, float2 size) {\n\
    if (arms == 0) return float2(0.0);\n\
    bool right = ndc.x >= 0.0 ? (arms & 1u) != 0 : (arms & 2u) == 0;\n\
    float4 local = (right ? toLocalR : toLocalL) * float4(ndc, zZeroToOne ? hd : hd * 2.0 - 1.0, 1.0);\n\
    float4 prevClip = (right ? prevR : prevL) * local;\n\
    if (prevClip.w <= 0.0) return float2(0.0);\n\
    float2 prevNdc = prevClip.xy / prevClip.w;\n\
    float2 prevUv = float2(prevNdc.x * 0.5 + 0.5, flipY ? (1.0 - prevNdc.y) * 0.5 : prevNdc.y * 0.5 + 0.5);\n\
    return (prevUv - uv) * size;\n\
}\n"

static NSString *const kMotionShader = @"\
#include <metal_stdlib>\n\
using namespace metal;\n" VIGNETTE_MSL "\
struct Params {\n\
    float4x4 curInvViewProj;\n\
    float4x4 prevViewProj;\n\
    float4 camDelta;\n\
    float2 jitter;\n\
    uint reset; uint zZeroToOne; uint flipY; uint debugView; float2 motionScale; uint skipScaler; float sharpness; uint kind; uint pad2;\n\
    float4 camFrac; int4 camInt; uint2 outSize; uint2 pad3;\n\
    float4 projTerms; float4 timePad; float4 objMin; float4 objMax; float4 objDelta;\n\
    uint4 hand; float4x4 handToLocal[2]; float4x4 prevHand[2];\n\
};\n\
// Position relative to the previous camera; points in the player's box also moved with the player.\n\
static float3 prevRelative(float3 rel, float4 camDelta, float4 objMin, float4 objMax, float4 objDelta) {\n\
    float3 prev = rel + camDelta.xyz;\n\
    if (all(rel >= objMin.xyz) && all(rel <= objMax.xyz)) prev -= objDelta.xyz;\n\
    return prev;\n\
}\n\
kernel void camera_motion(depth2d<float, access::read> sceneDepth [[texture(0)]],\n\
                          depth2d<float, access::read> handDepth [[texture(1)]],\n\
                          texture2d<half, access::write> motion [[texture(2)]],\n\
                          constant Params &p [[buffer(0)]],\n\
                          uint2 gid [[thread_position_in_grid]]) {\n\
    uint w = motion.get_width(), h = motion.get_height();\n\
    if (gid.x >= w || gid.y >= h) return;\n\
    // Hand depth is either the hand alone (vanilla: 0 elsewhere) or the scene with the hand (shader packs draw it into the\n\
    // world's depth: equal to the scene depth elsewhere).\n\
    float d = sceneDepth.read(gid);\n\
    float hd = handDepth.read(gid);\n\
    float2 size = float2(w, h);\n\
    float2 uv = (float2(gid) + 0.5) / size;\n\
    float2 ndc = float2(uv.x * 2.0 - 1.0, p.flipY ? 1.0 - uv.y * 2.0 : uv.y * 2.0 - 1.0);\n\
    if (hd > 0.0 && hd != d) {\n\
        float2 mv = handMotion(p.hand.x, p.handToLocal[0], p.handToLocal[1], p.prevHand[0], p.prevHand[1], ndc, hd, p.zZeroToOne != 0, p.flipY != 0, uv, size);\n\
        motion.write(half4(half2(mv), 0.0h, 0.0h), gid);\n\
        return;\n\
    }\n\
    float z = p.zZeroToOne ? d : d * 2.0 - 1.0;\n\
    float4 rel = p.curInvViewProj * float4(ndc, z, 1.0);\n\
    rel /= rel.w;\n\
    float4 prevClip = p.prevViewProj * float4(prevRelative(rel.xyz, p.camDelta, p.objMin, p.objMax, p.objDelta), 1.0);\n\
    float2 prevNdc = prevClip.xy / prevClip.w;\n\
    float2 prevUv = float2(prevNdc.x * 0.5 + 0.5, p.flipY ? (1.0 - prevNdc.y) * 0.5 : prevNdc.y * 0.5 + 0.5);\n\
    float2 mv = (prevUv - uv) * size;\n\
    if (prevClip.w <= 0.0) mv = float2(0.0);\n\
    motion.write(half4(half2(mv), 0.0h, 0.0h), gid);\n\
}\n\
// Shader packs draw the hand into the world depth before water, the block outline and the rest. pre = depth before the\n\
// hand, post = right after it, fin = final: writes fin everywhere except the hand (pre there), so that the scene depth\n\
// keeps translucents and differs from fin exactly where the hand is.\n\
kernel void pack_depth_merge(depth2d<float, access::read> pre [[texture(0)]],\n\
                             depth2d<float, access::read> post [[texture(1)]],\n\
                             depth2d<float, access::read> fin [[texture(2)]],\n\
                             depth2d<float, access::read> preTl [[texture(3)]],\n\
                             depth2d<float, access::read> postTl [[texture(4)]],\n\
                             device float *out [[buffer(0)]],\n\
                             uint2 gid [[thread_position_in_grid]]) {\n\
    uint w = pre.get_width();\n\
    if (gid.x >= w || gid.y >= pre.get_height()) return;\n\
    float a = pre.read(gid);\n\
    // Hand: changed by the solid hand pass, or by the translucent one (preTl/postTl, the depth around it).\n\
    float t = preTl.read(gid);\n\
    bool hand = post.read(gid) != a || postTl.read(gid) != t;\n\
    out[gid.y * w + gid.x] = hand ? (post.read(gid) != a ? a : t) : fin.read(gid);\n\
}\n\
// Distant Horizons terrain has its own depth buffer (its own projection) and leaves the world depth at 0 (sky). Puts it\n\
// into the scene depth, in the game's projection (depth = (dh - b) / a; past the game's far plane that is below 0).\n\
kernel void distant_depth_merge(depth2d<float, access::read> scene [[texture(0)]],\n\
                                depth2d<float, access::read> dh [[texture(1)]],\n\
                                constant float2 &pair [[buffer(1)]],\n\
                                device float *out [[buffer(0)]],\n\
                                uint2 gid [[thread_position_in_grid]]) {\n\
    uint w = scene.get_width();\n\
    if (gid.x >= w || gid.y >= scene.get_height()) return;\n\
    float d = scene.read(gid);\n\
    float f = dh.read(gid);\n\
    out[gid.y * w + gid.x] = (d <= 0.0 && f > 0.0) ? (f - pair.y) / pair.x : d;\n\
}\n\
// FSR 3 resource clears.\n\
kernel void fsr_clear_f(texture2d<float, access::write> t [[texture(0)]], constant float4 &v [[buffer(0)]], uint2 gid [[thread_position_in_grid]]) {\n\
    if (gid.x < t.get_width() && gid.y < t.get_height()) t.write(v, gid);\n\
}\n\
kernel void fsr_clear_u(texture2d<uint, access::write> t [[texture(0)]], constant uint4 &v [[buffer(0)]], uint2 gid [[thread_position_in_grid]]) {\n\
    if (gid.x < t.get_width() && gid.y < t.get_height()) t.write(v, gid);\n\
}\n\
struct FsOut { float4 position [[position]]; };\n\
vertex FsOut fullscreen_vs(uint vid [[vertex_id]]) {\n\
    float2 p = float2((vid << 1) & 2, vid & 2);\n\
    FsOut o; o.position = float4(p * 2.0 - 1.0, 0.0, 1.0); return o;\n\
}\n\
// AMD FidelityFX RCAS (robust contrast-adaptive sharpening), simplified: no denoise, per-pixel lobe.\n\
fragment half4 rcas_fs(FsOut in [[stage_in]], texture2d<half, access::read> src [[texture(0)]], constant float &sharpness [[buffer(0)]]) {\n\
    int2 p = int2(in.position.xy);\n\
    int2 m = int2(src.get_width() - 1, src.get_height() - 1);\n\
    half3 b = src.read(uint2(clamp(p + int2(0, -1), int2(0), m))).rgb;\n\
    half3 d = src.read(uint2(clamp(p + int2(-1, 0), int2(0), m))).rgb;\n\
    half4 e4 = src.read(uint2(p));\n\
    half3 e = e4.rgb;\n\
    half3 f = src.read(uint2(clamp(p + int2(1, 0), int2(0), m))).rgb;\n\
    half3 h = src.read(uint2(clamp(p + int2(0, 1), int2(0), m))).rgb;\n\
    if (sharpness <= 0.0) return half4(e, 1.0h);\n\
    half3 mn = min(min(b, d), min(min(e, f), h));\n\
    half3 mx = max(max(b, d), max(max(e, f), h));\n\
    float3 hitMin = float3(mn) / (4.0 * float3(mx) + 1e-4);\n\
    float3 hitMax = (1.0 - float3(mx)) / (4.0 * float3(mn) - 4.0 - 1e-4);\n\
    float3 lobe3 = max(-hitMin, hitMax);\n\
    // Strength above 1 (sharpness slider above 100%) is clamped so that 4 * lobe + 1 stays positive.\n\
    float lobe = max(-0.24, max(-0.1875, min(max(lobe3.r, max(lobe3.g, lobe3.b)), 0.0)) * sharpness);\n\
    float3 c = (lobe * float3(b + d + f + h) + float3(e)) / (4.0 * lobe + 1.0);\n\
    return half4(half3(saturate(c)), 1.0h);\n\
}\n\
fragment float4 vignette_fs(FsOut in [[stage_in]], texture2d<float, access::read> src [[texture(0)]], constant VignetteParams &v [[buffer(0)]]) {\n\
    uint2 p = uint2(in.position.xy);\n\
    float4 c = src.read(p);\n\
    return float4(packVignette(c.rgb, p, float2(src.get_width(), src.get_height()), v.kind, v.a, v.b), c.a);\n\
}\n\
kernel void debug_motion(texture2d<half, access::read> motion [[texture(0)]],\n\
                         texture2d<half, access::write> output [[texture(1)]],\n\
                         uint2 gid [[thread_position_in_grid]]) {\n\
    if (gid.x >= output.get_width() || gid.y >= output.get_height()) return;\n\
    uint2 src = uint2(float2(gid) * float2(motion.get_width(), motion.get_height()) / float2(output.get_width(), output.get_height()));\n\
    half2 mv = motion.read(src).xy;\n\
    output.write(half4(clamp(0.5h + mv * 0.05h, 0.0h, 1.0h), 0.5h, 1.0h), gid);\n\
}\n\
";

static id<MTLComputePipelineState> gMotionPipeline;
static id<MTLComputePipelineState> gDebugPipeline;
static id<MTLComputePipelineState> gMergePipeline;
static id<MTLBuffer> gMergeBuffer;
static id<MTLComputePipelineState> gDistantPipeline;
static id<MTLRenderPipelineState> gRcasPipeline;
static MTLPixelFormat gRcasFormat;

static BOOL ensureRcasPipeline(MTLPixelFormat format) {
    if (gRcasPipeline != nil && gRcasFormat == format) return YES;
    MTLRenderPipelineDescriptor *rp = [MTLRenderPipelineDescriptor new];
    rp.label = @"MetalFX RCAS";
    rp.vertexFunction = [gLibrary newFunctionWithName:@"fullscreen_vs"];
    rp.fragmentFunction = [gLibrary newFunctionWithName:@"rcas_fs"];
    rp.colorAttachments[0].pixelFormat = format;
    NSError *error = nil;
    gRcasPipeline = [gDevice newRenderPipelineStateWithDescriptor:rp error:&error];
    if (gRcasPipeline == nil) {
        setError([NSString stringWithFormat:@"RCAS pipeline failed: %@", error.localizedDescription]);
        return NO;
    }
    gRcasFormat = format;
    return YES;
}
static id<MTLFXTemporalScaler> gTemporal;
static id<MTLTexture> gTemporalOutput;
static id<MTLTexture> gMotion;
static NSUInteger gTInW, gTInH, gTOutW, gTOutH;
static MTLPixelFormat gTInFmt, gTOutFmt, gTDepthFmt;

static BOOL ensureMotionPipeline(void) {
    if (gMotionPipeline != nil) return YES;
    NSError *error = nil;
    id<MTLLibrary> lib = [gDevice newLibraryWithSource:kMotionShader options:nil error:&error];
    gLibrary = lib;
    if (lib == nil) {
        setError([NSString stringWithFormat:@"motion shader compile failed: %@", error.localizedDescription]);
        return NO;
    }
    gMotionPipeline = [gDevice newComputePipelineStateWithFunction:[lib newFunctionWithName:@"camera_motion"] error:&error];
    gDebugPipeline = [gDevice newComputePipelineStateWithFunction:[lib newFunctionWithName:@"debug_motion"] error:&error];
    gMergePipeline = [gDevice newComputePipelineStateWithFunction:[lib newFunctionWithName:@"pack_depth_merge"] error:&error];
    gDistantPipeline = [gDevice newComputePipelineStateWithFunction:[lib newFunctionWithName:@"distant_depth_merge"] error:&error];
    if (gMotionPipeline == nil || gDebugPipeline == nil || gMergePipeline == nil || gDistantPipeline == nil) {
        setError([NSString stringWithFormat:@"motion pipeline failed: %@", error.localizedDescription]);
        return NO;
    }
    return YES;
}

// Returns 1 if the device supports the temporal scaler, 0 otherwise.
int mfx_temporal_supported(void) {
    return gDevice != nil && [MTLFXTemporalScalerDescriptor supportsDevice:gDevice] ? 1 : 0;
}

// Largest output/input ratio (per axis) the temporal scaler accepts on this device.
float mfx_temporal_max_scale(void) {
    if (gDevice == nil) return 2.0f;
    if (@available(macOS 14.4, *)) {
        return [MTLFXTemporalScalerDescriptor supportedInputContentMaxScaleForDevice:gDevice];
    }
    return 2.0f;
}

static BOOL ensureTemporal(id<MTLTexture> in, id<MTLTexture> depth, id<MTLTexture> dst) {
    if (gTemporal != nil && gTInW == in.width && gTInH == in.height && gTOutW == dst.width && gTOutH == dst.height
        && gTInFmt == in.pixelFormat && gTOutFmt == dst.pixelFormat && gTDepthFmt == depth.pixelFormat) {
        return YES;
    }
    gTemporal = nil;
    gTemporalOutput = nil;
    gMotion = nil;
    if (!ensureMotionPipeline()) return NO;

    MTLFXTemporalScalerDescriptor *desc = [MTLFXTemporalScalerDescriptor new];
    desc.inputWidth = in.width;
    desc.inputHeight = in.height;
    desc.outputWidth = dst.width;
    desc.outputHeight = dst.height;
    desc.colorTextureFormat = in.pixelFormat;
    desc.depthTextureFormat = depth.pixelFormat;
    desc.motionTextureFormat = MTLPixelFormatRG16Float;
    desc.outputTextureFormat = dst.pixelFormat;
    desc.autoExposureEnabled = NO;
    // Without this MetalFX may run a slower fallback while it compiles its optimised pipelines in the background.
    desc.requiresSynchronousInitialization = YES;

    id<MTLFXTemporalScaler> scaler = [desc newTemporalScalerWithDevice:gDevice];
    if (scaler == nil) {
        setError([NSString stringWithFormat:@"newTemporalScalerWithDevice failed (%lux%lu -> %lux%lu, depth fmt %lu)",
                  (unsigned long)in.width, (unsigned long)in.height, (unsigned long)dst.width, (unsigned long)dst.height,
                  (unsigned long)depth.pixelFormat]);
        return NO;
    }
    if ((in.usage & scaler.colorTextureUsage) != scaler.colorTextureUsage
        || (depth.usage & scaler.depthTextureUsage) != scaler.depthTextureUsage) {
        setError([NSString stringWithFormat:@"input usage mismatch: color 0x%lx needs 0x%lx, depth 0x%lx needs 0x%lx",
                  (unsigned long)in.usage, (unsigned long)scaler.colorTextureUsage,
                  (unsigned long)depth.usage, (unsigned long)scaler.depthTextureUsage]);
        return NO;
    }

    MTLTextureDescriptor *md = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRG16Float
                                                                                 width:in.width
                                                                                height:in.height
                                                                             mipmapped:NO];
    md.usage = scaler.motionTextureUsage | MTLTextureUsageShaderWrite | MTLTextureUsageShaderRead;
    md.storageMode = MTLStorageModePrivate;
    id<MTLTexture> motion = [gDevice newTextureWithDescriptor:md];

    MTLTextureDescriptor *od = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:dst.pixelFormat
                                                                                 width:dst.width
                                                                                height:dst.height
                                                                             mipmapped:NO];
    od.usage = scaler.outputTextureUsage | MTLTextureUsageShaderWrite | MTLTextureUsageShaderRead;
    od.storageMode = MTLStorageModePrivate;
    id<MTLTexture> output = [gDevice newTextureWithDescriptor:od];
    if (motion == nil || output == nil) {
        setError(@"failed to allocate temporal textures");
        return NO;
    }
    motion.label = @"MetalFX Motion";
    output.label = @"MetalFX Temporal Output";

    gTemporal = scaler;
    gMotion = motion;
    gTemporalOutput = output;
    gTInW = in.width; gTInH = in.height; gTOutW = dst.width; gTOutH = dst.height;
    gTInFmt = in.pixelFormat; gTOutFmt = dst.pixelFormat; gTDepthFmt = depth.pixelFormat;
    return YES;
}

static void dispatch2D(id<MTLComputeCommandEncoder> enc, id<MTLComputePipelineState> pso, NSUInteger w, NSUInteger h) {
    NSUInteger tw = pso.threadExecutionWidth;
    NSUInteger th = MAX(1, pso.maxTotalThreadsPerThreadgroup / tw);
    [enc dispatchThreads:MTLSizeMake(w, h, 1) threadsPerThreadgroup:MTLSizeMake(tw, th, 1)];
}

static id<MTLTexture> privateTexture(MTLPixelFormat format, NSUInteger w, NSUInteger h, MTLTextureUsage usage, NSString *label) {
    MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];
    d.usage = usage;
    d.storageMode = MTLStorageModePrivate;
    id<MTLTexture> t = [gDevice newTextureWithDescriptor:d];
    t.label = label;
    return t;
}

// Motion vectors + MetalFX temporal scaler into gTemporalOutput. ensureTemporal must have succeeded.
static id<MTLCommandBuffer> encodeTemporalWork(id<MTLCommandBuffer> cb, id<MTLTexture> in, id<MTLTexture> depth, id<MTLTexture> hand,
                                               const MfxTemporalParams *params, id<MTLTexture> __strong *outResult) {
    id<MTLTexture> motion = gMotion;
    id<MTLTexture> output = gTemporalOutput;
    *outResult = output;
    id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
    ce.label = @"Camera motion vectors";
    [ce setComputePipelineState:gMotionPipeline];
    [ce setTexture:depth atIndex:0];
    [ce setTexture:hand atIndex:1];
    [ce setTexture:motion atIndex:2];
    [ce setBytes:params length:sizeof(MfxTemporalParams) atIndex:0];
    dispatch2D(ce, gMotionPipeline, motion.width, motion.height);
    if (params->debugView) {
        [ce setComputePipelineState:gDebugPipeline];
        [ce setTexture:motion atIndex:0];
        [ce setTexture:output atIndex:1];
        dispatch2D(ce, gDebugPipeline, output.width, output.height);
    }
    [ce endEncoding];

    if (!params->debugView && !params->skipScaler) {
        gTemporal.colorTexture = in;
        gTemporal.depthTexture = depth;
        gTemporal.motionTexture = gMotion;
        gTemporal.outputTexture = gTemporalOutput;
        gTemporal.inputContentWidth = in.width;
        gTemporal.inputContentHeight = in.height;
        gTemporal.jitterOffsetX = params->jitterX;
        gTemporal.jitterOffsetY = params->jitterY;
        gTemporal.motionVectorScaleX = params->motionScaleX;
        gTemporal.motionVectorScaleY = params->motionScaleY;
        gTemporal.depthReversed = YES;
        gTemporal.reset = params->reset != 0;
        cb = splitStage(cb, 0);
        [gTemporal encodeToCommandBuffer:cb];
        cb = splitStage(cb, 1);
    }
    return cb;
}

// gTemporalOutput -> Minecraft's target, sharpened (replaces a plain copy, so it costs about the same).
static void encodeOutput(id<MTLCommandBuffer> cb, id<MTLTexture> source, id<MTLTexture> dst, float sharpness) {
    if ((dst.usage & MTLTextureUsageRenderTarget) && ensureRcasPipeline(dst.pixelFormat)) {
        MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
        pass.colorAttachments[0].texture = dst;
        pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
        pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:pass];
        re.label = @"MetalFX RCAS";
        [re setRenderPipelineState:gRcasPipeline];
        [re setFragmentTexture:source atIndex:0];
        // Slider 0..1 -> RCAS strength 0..1; above that, the same curve as FSR's RCAS (1.5 -> 2x).
        float strength = sharpness <= 1.0f ? sharpness : exp2f(2.0f * sharpness - 2.0f);
        [re setFragmentBytes:&strength length:sizeof(float) atIndex:0];
        [re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [re endEncoding];
    } else {
        id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
        [blit copyFromTexture:source
                  sourceSlice:0
                  sourceLevel:0
                 sourceOrigin:MTLOriginMake(0, 0, 0)
                   sourceSize:MTLSizeMake(dst.width, dst.height, 1)
                    toTexture:dst
             destinationSlice:0
             destinationLevel:0
            destinationOrigin:MTLOriginMake(0, 0, 0)];
        [blit endEncoding];
    }
}

// ---------------------------------------------------------------------------------------------
// AMD FSR 3.1 upscaler (FidelityFX SDK 1.1.4, MIT licence). The passes in fsr3_shaders.h are AMD's GLSL translated to
// MSL; this mirrors ffx_fsr3upscaler.cpp's resource setup and dispatch for one permutation (LDR colour, render-size
// motion vectors without jitter, reversed-Z depth, no reactive/transparency masks, no auto exposure input).
// ---------------------------------------------------------------------------------------------

// cbFSR3Upscaler (std140, 148 bytes used).
typedef struct {
    int32_t renderSize[2], previousFrameRenderSize[2], upscaleSize[2], previousFrameUpscaleSize[2];
    int32_t maxRenderSize[2], maxUpscaleSize[2];
    float deviceToViewDepth[4];
    float jitterOffset[2], previousFrameJitterOffset[2], motionVectorScale[2], downscaleFactor[2];
    float motionVectorJitterCancellation[2];
    float tanHalfFOV, jitterPhaseCount, deltaTime, deltaPreExposure, viewSpaceToMetersFactor, frameIndex;
    float velocityFactor, reactivenessScale, shadingChangeScale, accumulationAddedPerFrame, minDisocclusionAccumulation;
    float pad[3];
} FsrConstants;
typedef struct { uint32_t mips, numWorkGroups, workGroupOffset[2], renderSize[2], pad[2]; } FsrSpdConstants;
typedef struct { uint32_t rcasConfig[4]; } FsrRcasConstants;

enum {
    FSR_PREPARE_INPUTS, FSR_LUMA_PYRAMID, FSR_SHADING_CHANGE_PYRAMID, FSR_SHADING_CHANGE, FSR_PREPARE_REACTIVITY,
    FSR_LUMA_INSTABILITY, FSR_ACCUMULATE, FSR_ACCUMULATE_SHARPEN, FSR_RCAS, FSR_PASS_COUNT
};
static const char *const kFsrPassSources[FSR_PASS_COUNT] = {
    kFsrSource_prepare_inputs, kFsrSource_luma_pyramid, kFsrSource_shading_change_pyramid, kFsrSource_shading_change,
    kFsrSource_prepare_reactivity, kFsrSource_luma_instability, kFsrSource_accumulate, kFsrSource_accumulate_sharpen,
    kFsrSource_rcas,
};
static NSString *const kFsrPassNames[FSR_PASS_COUNT] = {
    @"fsr_prepare_inputs", @"fsr_luma_pyramid", @"fsr_shading_change_pyramid", @"fsr_shading_change",
    @"fsr_prepare_reactivity", @"fsr_luma_instability", @"fsr_accumulate", @"fsr_accumulate_sharpen", @"fsr_rcas",
};
static id<MTLComputePipelineState> gFsrPipelines[FSR_PASS_COUNT];
static id<MTLComputePipelineState> gFsrClearF, gFsrClearU;
static id<MTLSamplerState> gFsrPointClamp, gFsrLinearClamp;
static BOOL gFsrCompileFailed;

static id<MTLTexture> gFsrMotion, gFsrDilatedMotion, gFsrDilatedDepth, gFsrPrevNearestDepth;
static id<MTLTexture> gFsrAccumulation[2], gFsrLuma[2], gFsrLumaHistory[2], gFsrUpscaled[2];
static id<MTLTexture> gFsrIntermediate, gFsrShadingChange, gFsrNewLocks, gFsrFarthestDepthMip1, gFsrDilatedReactive;
static id<MTLTexture> gFsrSpdMips, gFsrSpdMipViews[6], gFsrSpdAtomic, gFsrFrameInfo, gFsrDefaultReactivity, gFsrDefaultExposure;
static id<MTLTexture> gFsrOutput; // only when Minecraft's target can't be written from a compute shader
static NSUInteger gFsrInW, gFsrInH, gFsrOutW, gFsrOutH;
static MTLPixelFormat gFsrOutFmt;
static BOOL gFsrFirst;
static uint32_t gFsrFrame;
static FsrConstants gFsrConst;

static BOOL ensureFsrPipelines(void) {
    if (gFsrPipelines[FSR_RCAS] != nil) return YES;
    if (gFsrCompileFailed || !ensureMotionPipeline()) return NO;
    MTLCompileOptions *options = [MTLCompileOptions new];
    if (@available(macOS 14.0, *)) options.languageVersion = MTLLanguageVersion3_1;
    options.fastMathEnabled = YES;
    // Compile the passes in parallel (~2.5 s serially, once per session).
    __block NSString *failure = nil;
    dispatch_apply(FSR_PASS_COUNT, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^(size_t i) {
        NSError *error = nil;
        id<MTLLibrary> lib = [gDevice newLibraryWithSource:@(kFsrPassSources[i]) options:options error:&error];
        id<MTLFunction> fn = [lib newFunctionWithName:kFsrPassNames[i]];
        id<MTLComputePipelineState> pso = fn != nil ? [gDevice newComputePipelineStateWithFunction:fn error:&error] : nil;
        @synchronized (gDevice) {
            if (pso == nil) failure = [NSString stringWithFormat:@"%@: %@", kFsrPassNames[i], error.localizedDescription];
            gFsrPipelines[i] = pso;
        }
    });
    NSError *error = nil;
    gFsrClearF = [gDevice newComputePipelineStateWithFunction:[gLibrary newFunctionWithName:@"fsr_clear_f"] error:&error];
    gFsrClearU = [gDevice newComputePipelineStateWithFunction:[gLibrary newFunctionWithName:@"fsr_clear_u"] error:&error];
    if (failure == nil && (gFsrClearF == nil || gFsrClearU == nil)) failure = @"FSR clear kernels";
    if (failure == nil && (gFsrPipelines[FSR_LUMA_PYRAMID].maxTotalThreadsPerThreadgroup < 256
                           || gFsrPipelines[FSR_SHADING_CHANGE_PYRAMID].maxTotalThreadsPerThreadgroup < 256)) {
        failure = @"SPD pass cannot run 256 threads per threadgroup";
    }
    if (failure != nil) {
        for (int i = 0; i < FSR_PASS_COUNT; i++) gFsrPipelines[i] = nil;
        gFsrCompileFailed = YES;
        setError([NSString stringWithFormat:@"FSR 3 pipeline failed: %@", failure]);
        return NO;
    }
    MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
    sd.sAddressMode = sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
    sd.minFilter = sd.magFilter = MTLSamplerMinMagFilterNearest;
    sd.mipFilter = MTLSamplerMipFilterNearest;
    gFsrPointClamp = [gDevice newSamplerStateWithDescriptor:sd];
    sd.minFilter = sd.magFilter = MTLSamplerMinMagFilterLinear;
    sd.mipFilter = MTLSamplerMipFilterLinear;
    gFsrLinearClamp = [gDevice newSamplerStateWithDescriptor:sd];
    return YES;
}

static id<MTLTexture> fsrTexture(MTLPixelFormat format, NSUInteger w, NSUInteger h, NSUInteger mips, BOOL atomic, NSString *label) {
    MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:MAX(w, 1) height:MAX(h, 1) mipmapped:NO];
    d.mipmapLevelCount = mips;
    d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
    if (@available(macOS 14.0, *)) {
        if (atomic) d.usage |= MTLTextureUsageShaderAtomic;
    }
    d.storageMode = MTLStorageModePrivate;
    id<MTLTexture> t = [gDevice newTextureWithDescriptor:d];
    t.label = label;
    return t;
}

static BOOL ensureFsr(id<MTLTexture> in, id<MTLTexture> dst) {
    if (!ensureFsrPipelines()) return NO;
    if (gFsrMotion != nil && gFsrInW == in.width && gFsrInH == in.height && gFsrOutW == dst.width && gFsrOutH == dst.height
        && gFsrOutFmt == dst.pixelFormat) {
        return YES;
    }
    NSUInteger iw = in.width, ih = in.height, ow = dst.width, oh = dst.height;
    gFsrMotion = fsrTexture(MTLPixelFormatRG16Float, iw, ih, 1, NO, @"FSR motion");
    gFsrDilatedMotion = fsrTexture(MTLPixelFormatRG16Float, iw, ih, 1, NO, @"FSR dilated motion");
    gFsrDilatedDepth = fsrTexture(MTLPixelFormatR32Float, iw, ih, 1, NO, @"FSR dilated depth");
    gFsrPrevNearestDepth = fsrTexture(MTLPixelFormatR32Uint, iw, ih, 1, YES, @"FSR reconstructed previous depth");
    for (int i = 0; i < 2; i++) {
        gFsrAccumulation[i] = fsrTexture(MTLPixelFormatR8Unorm, iw, ih, 1, NO, @"FSR accumulation");
        gFsrLuma[i] = fsrTexture(MTLPixelFormatR16Float, iw, ih, 1, NO, @"FSR luma");
        gFsrLumaHistory[i] = fsrTexture(MTLPixelFormatRGBA16Float, iw, ih, 1, NO, @"FSR luma history");
        gFsrUpscaled[i] = fsrTexture(MTLPixelFormatRGBA16Float, ow, oh, 1, NO, @"FSR upscaled colour");
    }
    gFsrIntermediate = fsrTexture(MTLPixelFormatR16Float, iw, ih, 1, NO, @"FSR farthest depth / luma instability");
    gFsrShadingChange = fsrTexture(MTLPixelFormatR8Unorm, iw / 2, ih / 2, 1, NO, @"FSR shading change");
    gFsrNewLocks = fsrTexture(MTLPixelFormatR8Unorm, ow, oh, 1, NO, @"FSR new locks");
    gFsrFarthestDepthMip1 = fsrTexture(MTLPixelFormatR16Float, iw / 2, ih / 2, 1, NO, @"FSR farthest depth mip 1");
    gFsrDilatedReactive = fsrTexture(MTLPixelFormatRGBA8Unorm, iw, ih, 1, NO, @"FSR dilated reactive masks");
    NSUInteger spdMips = (NSUInteger)floor(log2((double)MAX(MAX(iw / 2, ih / 2), 1))) + 1;
    gFsrSpdMips = fsrTexture(MTLPixelFormatRG16Float, iw / 2, ih / 2, MAX(spdMips, 6), NO, @"FSR SPD mips");
    for (int i = 0; i < 6; i++) {
        gFsrSpdMipViews[i] = [gFsrSpdMips newTextureViewWithPixelFormat:MTLPixelFormatRG16Float textureType:MTLTextureType2D
                                                                levels:NSMakeRange(MIN((NSUInteger)i, gFsrSpdMips.mipmapLevelCount - 1), 1)
                                                                slices:NSMakeRange(0, 1)];
    }
    gFsrSpdAtomic = fsrTexture(MTLPixelFormatR32Uint, 1, 1, 1, YES, @"FSR SPD counter");
    gFsrFrameInfo = fsrTexture(MTLPixelFormatRGBA32Float, 1, 1, 1, NO, @"FSR frame info");
    gFsrDefaultReactivity = fsrTexture(MTLPixelFormatR8Unorm, 1, 1, 1, NO, @"FSR default reactivity");
    gFsrDefaultExposure = fsrTexture(MTLPixelFormatRG32Float, 1, 1, 1, NO, @"FSR default exposure");
    gFsrOutput = (dst.usage & MTLTextureUsageShaderWrite) ? nil
        : fsrTexture(dst.pixelFormat, ow, oh, 1, NO, @"FSR output");
    for (int i = 0; i < 2; i++) {
        if (gFsrAccumulation[i] == nil || gFsrLuma[i] == nil || gFsrLumaHistory[i] == nil || gFsrUpscaled[i] == nil) gFsrMotion = nil;
    }
    if (gFsrMotion == nil || gFsrDilatedMotion == nil || gFsrDilatedDepth == nil || gFsrPrevNearestDepth == nil
        || gFsrIntermediate == nil || gFsrShadingChange == nil || gFsrNewLocks == nil || gFsrFarthestDepthMip1 == nil
        || gFsrDilatedReactive == nil || gFsrSpdMips == nil || gFsrSpdMipViews[5] == nil || gFsrSpdAtomic == nil
        || gFsrFrameInfo == nil || gFsrDefaultReactivity == nil || gFsrDefaultExposure == nil
        || (!(dst.usage & MTLTextureUsageShaderWrite) && gFsrOutput == nil)) {
        gFsrMotion = nil;
        setError(@"failed to allocate FSR 3 resources");
        return NO;
    }
    gFsrInW = iw; gFsrInH = ih; gFsrOutW = ow; gFsrOutH = oh; gFsrOutFmt = dst.pixelFormat;
    gFsrFirst = YES;
    gFsrFrame = 0;
    memset(&gFsrConst, 0, sizeof gFsrConst);
    gFsrConst.velocityFactor = 1.0f;
    gFsrConst.reactivenessScale = 1.0f;
    gFsrConst.shadingChangeScale = 1.0f;
    gFsrConst.accumulationAddedPerFrame = 1.0f / 3.0f;
    gFsrConst.minDisocclusionAccumulation = -1.0f / 3.0f;
    return YES;
}

static void fsrClear(id<MTLComputeCommandEncoder> ce, id<MTLTexture> t, float r, float g, float b, float a) {
    float v[4] = {r, g, b, a};
    [ce setComputePipelineState:gFsrClearF];
    [ce setTexture:t atIndex:0];
    [ce setBytes:v length:sizeof v atIndex:0];
    dispatch2D(ce, gFsrClearF, t.width, t.height);
}

static void fsrClearU(id<MTLComputeCommandEncoder> ce, id<MTLTexture> t, uint32_t value) {
    uint32_t v[4] = {value, value, value, value};
    [ce setComputePipelineState:gFsrClearU];
    [ce setTexture:t atIndex:0];
    [ce setBytes:v length:sizeof v atIndex:0];
    dispatch2D(ce, gFsrClearU, t.width, t.height);
}

static void fsrDispatch(id<MTLComputeCommandEncoder> ce, int pass, NSUInteger gx, NSUInteger gy, NSUInteger tx, NSUInteger ty) {
    [ce setComputePipelineState:gFsrPipelines[pass]];
    [ce dispatchThreadgroups:MTLSizeMake(gx, gy, 1) threadsPerThreadgroup:MTLSizeMake(tx, ty, 1)];
}

static uint16_t floatToHalf(float f) {
    __fp16 h = (__fp16)f;
    uint16_t bits;
    memcpy(&bits, &h, sizeof bits);
    return bits;
}

// Motion vectors + the FSR 3 upscaler, written into Minecraft's target.
static id<MTLCommandBuffer> encodeFsr(id<MTLCommandBuffer> cb, id<MTLTexture> in, id<MTLTexture> depth, id<MTLTexture> hand,
                                      id<MTLTexture> dst, const MfxTemporalParams *params) {
    const BOOL odd = (gFsrFrame & 1) != 0;
    const int accSrv = odd ? 1 : 0, accUav = odd ? 0 : 1;   // ACCUMULATION_1/2, INTERNAL_UPSCALED_1/2, LUMA_HISTORY_1/2
    const int lumaCur = odd ? 1 : 0, lumaPrev = odd ? 0 : 1;
    const BOOL reset = params->reset || gFsrFirst;
    const BOOL sharpen = params->sharpness > 0.0f;
    const int iw = (int)in.width, ih = (int)in.height, ow = (int)dst.width, oh = (int)dst.height;

    // Constants (ffx_fsr3upscaler.cpp fsr3upscalerDispatch).
    FsrConstants *c = &gFsrConst;
    c->previousFrameJitterOffset[0] = c->jitterOffset[0];
    c->previousFrameJitterOffset[1] = c->jitterOffset[1];
    c->jitterOffset[0] = params->jitterX;
    c->jitterOffset[1] = params->jitterY;
    c->previousFrameRenderSize[0] = c->renderSize[0];
    c->previousFrameRenderSize[1] = c->renderSize[1];
    c->renderSize[0] = c->maxRenderSize[0] = iw;
    c->renderSize[1] = c->maxRenderSize[1] = ih;
    c->previousFrameUpscaleSize[0] = c->upscaleSize[0];
    c->previousFrameUpscaleSize[1] = c->upscaleSize[1];
    c->upscaleSize[0] = c->maxUpscaleSize[0] = ow;
    c->upscaleSize[1] = c->maxUpscaleSize[1] = oh;
    c->tanHalfFOV = params->tanHalfFovX;
    c->viewSpaceToMetersFactor = 1.0f;
    // View depth = m32 / (device depth + m22) = deviceToViewDepth[1] / (device depth - deviceToViewDepth[0]).
    c->deviceToViewDepth[0] = -params->projM22;
    c->deviceToViewDepth[1] = params->projM32;
    c->deviceToViewDepth[2] = params->tanHalfFovX;
    c->deviceToViewDepth[3] = params->tanHalfFovY;
    c->downscaleFactor[0] = (float)iw / ow;
    c->downscaleFactor[1] = (float)ih / oh;
    c->deltaPreExposure = 1.0f;
    c->motionVectorScale[0] = params->motionScaleX / iw;
    c->motionVectorScale[1] = params->motionScaleY / ih;
    c->motionVectorJitterCancellation[0] = c->motionVectorJitterCancellation[1] = 0.0f;
    const int phaseCount = (int)(8.0f * powf((float)ow / iw, 2.0f));
    if (reset || c->jitterPhaseCount == 0.0f) c->jitterPhaseCount = (float)phaseCount;
    else if (phaseCount > c->jitterPhaseCount) c->jitterPhaseCount += 1.0f;
    else if (phaseCount < c->jitterPhaseCount) c->jitterPhaseCount -= 1.0f;
    c->deltaTime = fmaxf(0.0f, fminf(1.0f, params->frameTimeMs / 1000.0f));
    c->frameIndex = reset ? 0.0f : c->frameIndex + 1.0f;

    // SPD (ffxSpdSetup over the whole render area).
    FsrSpdConstants spd = {0};
    NSUInteger spdX = (NSUInteger)((iw - 1) / 64 + 1), spdY = (NSUInteger)((ih - 1) / 64 + 1);
    spd.numWorkGroups = (uint32_t)(spdX * spdY);
    spd.mips = (uint32_t)fmin(floor(log2((double)MAX(iw, ih))), 12.0);
    spd.renderSize[0] = (uint32_t)iw;
    spd.renderSize[1] = (uint32_t)ih;

    // RCAS: sharpness 0..1.5 -> 2..-1 stops (above 1 the lobe is clamped in the shader, see build_fsr3_shaders.sh).
    FsrRcasConstants rcas = {0};
    float stops = exp2f(-(-2.0f * params->sharpness + 2.0f));
    memcpy(&rcas.rcasConfig[0], &stops, 4);
    rcas.rcasConfig[1] = (uint32_t)floatToHalf(stops) | ((uint32_t)floatToHalf(stops) << 16);

    MfxTemporalParams local = *params;
    id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
    ce.label = @"FSR 3";
    // Camera motion vectors (no dilation: FSR dilates them itself).
    [ce setComputePipelineState:gMotionPipeline];
    [ce setTexture:depth atIndex:0];
    [ce setTexture:hand atIndex:1];
    [ce setTexture:gFsrMotion atIndex:2];
    [ce setBytes:&local length:sizeof local atIndex:0];
    dispatch2D(ce, gMotionPipeline, gFsrMotion.width, gFsrMotion.height);

    if (gFsrFirst) {
        for (int i = 0; i < 2; i++) { fsrClear(ce, gFsrAccumulation[i], 0, 0, 0, 0); fsrClear(ce, gFsrLuma[i], 0, 0, 0, 0); }
        fsrClear(ce, gFsrDefaultReactivity, 0, 0, 0, 0);
        fsrClear(ce, gFsrDefaultExposure, 0, 0, 0, 0);
        gFsrFirst = NO;
    }
    if (reset) {
        fsrClear(ce, gFsrAccumulation[accSrv], 0, 0, 0, 0);
        fsrClear(ce, gFsrFrameInfo, -1, 1, 0, 0);
    }
    fsrClearU(ce, gFsrPrevNearestDepth, 0); // inverted depth: farthest = 0
    fsrClearU(ce, gFsrSpdAtomic, 0);
    for (int i = 0; i < 6; i++) fsrClear(ce, gFsrSpdMipViews[i], 0, 0, 0, 0);

    [ce setSamplerState:gFsrPointClamp atIndex:14];
    [ce setSamplerState:gFsrLinearClamp atIndex:15];
    NSUInteger srcX = (NSUInteger)(iw + 7) / 8, srcY = (NSUInteger)(ih + 7) / 8;
    NSUInteger dstX = (NSUInteger)(ow + 7) / 8, dstY = (NSUInteger)(oh + 7) / 8;
    id<MTLTexture> output = gFsrOutput != nil ? gFsrOutput : dst;

    // Prepare inputs.
    [ce setTexture:gFsrMotion atIndex:0];
    [ce setTexture:depth atIndex:1];
    [ce setTexture:in atIndex:2];
    [ce setTexture:gFsrDilatedMotion atIndex:3];
    [ce setTexture:gFsrDilatedDepth atIndex:4];
    [ce setTexture:gFsrPrevNearestDepth atIndex:5];
    [ce setTexture:gFsrIntermediate atIndex:6];
    [ce setTexture:gFsrLuma[lumaCur] atIndex:7];
    [ce setBytes:c length:sizeof *c atIndex:8];
    fsrDispatch(ce, FSR_PREPARE_INPUTS, srcX, srcY, 8, 8);

    // Luma pyramid (SPD).
    [ce setTexture:gFsrLuma[lumaCur] atIndex:0];
    [ce setTexture:gFsrIntermediate atIndex:1];
    [ce setTexture:gFsrSpdAtomic atIndex:2];
    [ce setTexture:gFsrFrameInfo atIndex:3];
    for (int i = 0; i < 6; i++) [ce setTexture:gFsrSpdMipViews[i] atIndex:4 + i];
    [ce setTexture:gFsrFarthestDepthMip1 atIndex:10];
    [ce setBytes:c length:sizeof *c atIndex:11];
    [ce setBytes:&spd length:sizeof spd atIndex:12];
    fsrDispatch(ce, FSR_LUMA_PYRAMID, spdX, spdY, 256, 1);

    // Shading change pyramid (SPD).
    [ce setTexture:gFsrLuma[lumaCur] atIndex:0];
    [ce setTexture:gFsrLuma[lumaPrev] atIndex:1];
    [ce setTexture:gFsrDilatedMotion atIndex:2];
    [ce setTexture:gFsrDefaultExposure atIndex:3];
    [ce setTexture:gFsrSpdAtomic atIndex:4];
    for (int i = 0; i < 6; i++) [ce setTexture:gFsrSpdMipViews[i] atIndex:5 + i];
    [ce setBytes:c length:sizeof *c atIndex:11];
    [ce setBytes:&spd length:sizeof spd atIndex:12];
    fsrDispatch(ce, FSR_SHADING_CHANGE_PYRAMID, spdX, spdY, 256, 1);

    // Shading change.
    [ce setTexture:gFsrSpdMips atIndex:0];
    [ce setTexture:gFsrShadingChange atIndex:1];
    [ce setBytes:c length:sizeof *c atIndex:2];
    fsrDispatch(ce, FSR_SHADING_CHANGE, ((NSUInteger)(iw / 2) + 7) / 8, ((NSUInteger)(ih / 2) + 7) / 8, 8, 8);

    // Prepare reactivity.
    [ce setTexture:gFsrPrevNearestDepth atIndex:0];
    [ce setTexture:gFsrDilatedMotion atIndex:1];
    [ce setTexture:gFsrDilatedDepth atIndex:2];
    [ce setTexture:gFsrDefaultReactivity atIndex:3];
    [ce setTexture:gFsrDefaultReactivity atIndex:4];
    [ce setTexture:gFsrAccumulation[accSrv] atIndex:5];
    [ce setTexture:gFsrShadingChange atIndex:6];
    [ce setTexture:gFsrLuma[lumaCur] atIndex:7];
    [ce setTexture:gFsrDefaultExposure atIndex:8];
    [ce setTexture:gFsrDilatedReactive atIndex:9];
    [ce setTexture:gFsrNewLocks atIndex:10];
    [ce setTexture:gFsrAccumulation[accUav] atIndex:11];
    [ce setBytes:c length:sizeof *c atIndex:12];
    fsrDispatch(ce, FSR_PREPARE_REACTIVITY, srcX, srcY, 8, 8);

    // Luma instability.
    [ce setTexture:gFsrDefaultExposure atIndex:0];
    [ce setTexture:gFsrDilatedReactive atIndex:1];
    [ce setTexture:gFsrDilatedMotion atIndex:2];
    [ce setTexture:gFsrFrameInfo atIndex:3];
    [ce setTexture:gFsrLumaHistory[accSrv] atIndex:4];
    [ce setTexture:gFsrFarthestDepthMip1 atIndex:5];
    [ce setTexture:gFsrLuma[lumaCur] atIndex:6];
    [ce setTexture:gFsrLumaHistory[accUav] atIndex:7];
    [ce setTexture:gFsrIntermediate atIndex:8];
    [ce setBytes:c length:sizeof *c atIndex:9];
    fsrDispatch(ce, FSR_LUMA_INSTABILITY, srcX, srcY, 8, 8);

    // Accumulate (+ RCAS).
    [ce setTexture:gFsrDefaultExposure atIndex:0];
    [ce setTexture:gFsrDilatedReactive atIndex:1];
    [ce setTexture:gFsrDilatedMotion atIndex:2];
    [ce setTexture:gFsrUpscaled[accSrv] atIndex:3];
    [ce setTexture:gFsrFarthestDepthMip1 atIndex:5];
    [ce setTexture:gFsrLuma[lumaCur] atIndex:6];
    [ce setTexture:gFsrIntermediate atIndex:7];
    [ce setTexture:in atIndex:8];
    [ce setTexture:gFsrUpscaled[accUav] atIndex:9];
    [ce setTexture:output atIndex:10];
    [ce setTexture:gFsrNewLocks atIndex:11];
    [ce setBytes:c length:sizeof *c atIndex:12];
    fsrDispatch(ce, sharpen ? FSR_ACCUMULATE_SHARPEN : FSR_ACCUMULATE, dstX, dstY, 8, 8);
    if (sharpen) {
        [ce setTexture:gFsrDefaultExposure atIndex:0];
        [ce setTexture:gFsrUpscaled[accUav] atIndex:1];
        [ce setTexture:output atIndex:2];
        [ce setBytes:c length:sizeof *c atIndex:3];
        [ce setBytes:&rcas length:sizeof rcas atIndex:4];
        fsrDispatch(ce, FSR_RCAS, ((NSUInteger)ow + 15) / 16, ((NSUInteger)oh + 15) / 16, 64, 1);
    }
    [ce endEncoding];
    if (gFsrOutput != nil) {
        id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
        [blit copyFromTexture:gFsrOutput sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0)
                   sourceSize:MTLSizeMake(gFsrOutW, gFsrOutH, 1) toTexture:dst destinationSlice:0 destinationLevel:0
            destinationOrigin:MTLOriginMake(0, 0, 0)];
        [blit endEncoding];
    }
    gFsrFrame = (gFsrFrame + 1) % 16;
    return splitStage(cb, 1);
}

// Depth merges. They don't run on their own: each call checks its textures and keeps the merge, and the next temporal
// upscale (mfx_upscale_temporal) or mfx_merges_flush encodes it at the start of its command buffer. That saves a Vulkan ->
// Metal -> Vulkan round trip per merge (about 0.5 ms a frame on an M3 Pro).
static BOOL gPendingPack, gPendingDistant;
static id<MTLTexture> gPendingPre, gPendingPost, gPendingFin, gPendingPreTl, gPendingPostTl, gPendingScene, gPendingFar;
static float gPendingPair[2];

static void forgetMerges(void) {
    gPendingPack = gPendingDistant = NO;
    gPendingPre = gPendingPost = gPendingFin = gPendingPreTl = gPendingPostTl = gPendingScene = gPendingFar = nil;
}

// Runs the merge kernel into gMergeBuffer, then copies the result over dst (a depth texture can't be written directly).
static void encodeMerge(id<MTLCommandBuffer> cb, id<MTLComputePipelineState> pipeline, NSString *label, id<MTLTexture> dst,
                        NSArray<id<MTLTexture>> *textures, const float *pair) {
    NSUInteger w = dst.width, h = dst.height, bytes = w * h * 4;
    if (gMergeBuffer == nil || gMergeBuffer.length < bytes) {
        gMergeBuffer = [gDevice newBufferWithLength:bytes options:MTLResourceStorageModePrivate];
    }
    id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
    ce.label = label;
    [ce setComputePipelineState:pipeline];
    for (NSUInteger i = 0; i < textures.count; i++) [ce setTexture:textures[i] atIndex:i];
    [ce setBuffer:gMergeBuffer offset:0 atIndex:0];
    if (pair != NULL) [ce setBytes:pair length:2 * sizeof(float) atIndex:1];
    [ce dispatchThreads:MTLSizeMake(w, h, 1) threadsPerThreadgroup:MTLSizeMake(16, 16, 1)];
    [ce endEncoding];
    id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
    [blit copyFromBuffer:gMergeBuffer sourceOffset:0 sourceBytesPerRow:w * 4 sourceBytesPerImage:bytes
              sourceSize:MTLSizeMake(w, h, 1) toTexture:dst destinationSlice:0 destinationLevel:0
       destinationOrigin:MTLOriginMake(0, 0, 0)];
    [blit endEncoding];
}

// Encodes the kept merges into cb (in the order they were made) and forgets them.
static void encodePendingMerges(id<MTLCommandBuffer> cb) {
    if (gPendingPack) {
        encodeMerge(cb, gMergePipeline, @"Pack depth merge", gPendingPre,
                    @[gPendingPre, gPendingPost, gPendingFin, gPendingPreTl, gPendingPostTl], NULL);
    }
    if (gPendingDistant) encodeMerge(cb, gDistantPipeline, @"Distant depth merge", gPendingScene, @[gPendingScene, gPendingFar], gPendingPair);
    forgetMerges();
}

// Keeps a merge that rewrites preTex (Depth32Float, the depth before a shader pack drew the hand) into the scene depth
// without the hand but with everything drawn after it (see pack_depth_merge). Returns 1 if kept.
int mfx_merge_pack_depth(uintptr_t preTex, uintptr_t postTex, uintptr_t finTex, uintptr_t preTlTex, uintptr_t postTlTex) {
    @autoreleasepool {
        if (gQueue == nil) {
            setError(@"bridge not initialised");
            return -1;
        }
        id<MTLTexture> pre = (__bridge id<MTLTexture>)(void *)preTex;
        id<MTLTexture> post = (__bridge id<MTLTexture>)(void *)postTex;
        id<MTLTexture> fin = (__bridge id<MTLTexture>)(void *)finTex;
        // No translucent hand pass captured: compare a texture with itself (never a difference).
        id<MTLTexture> preTl = preTlTex != 0 ? (__bridge id<MTLTexture>)(void *)preTlTex : post;
        id<MTLTexture> postTl = postTlTex != 0 ? (__bridge id<MTLTexture>)(void *)postTlTex : preTl;
        if (!ensureMotionPipeline()) return 0;
        NSUInteger w = pre.width, h = pre.height;
        if (pre.pixelFormat != MTLPixelFormatDepth32Float || post.width != w || post.height != h || fin.width != w || fin.height != h
            || preTl.width != w || preTl.height != h || postTl.width != w || postTl.height != h) {
            setError(@"pack depth merge: mismatched depth textures");
            return 0;
        }
        gPendingPack = YES;
        gPendingPre = pre;
        gPendingPost = post;
        gPendingFin = fin;
        gPendingPreTl = preTl;
        gPendingPostTl = postTl;
        return 1;
    }
}

// Keeps a merge of Distant Horizons' depth (dhTex) into the scene depth (sceneTex, see distant_depth_merge). Returns 1 if
// kept.
int mfx_merge_distant_depth(uintptr_t sceneTex, uintptr_t dhTex, float pairA, float pairB) {
    @autoreleasepool {
        if (gQueue == nil) {
            setError(@"bridge not initialised");
            return -1;
        }
        id<MTLTexture> scene = (__bridge id<MTLTexture>)(void *)sceneTex;
        id<MTLTexture> dh = (__bridge id<MTLTexture>)(void *)dhTex;
        if (!ensureMotionPipeline()) return 0;
        if (scene.pixelFormat != MTLPixelFormatDepth32Float || dh.pixelFormat != MTLPixelFormatDepth32Float
            || dh.width != scene.width || dh.height != scene.height || pairA == 0.0f) {
            setError([NSString stringWithFormat:@"distant depth merge: mismatched textures (%lux%lu fmt %lu, far %lux%lu fmt %lu)",
                      (unsigned long)scene.width, (unsigned long)scene.height, (unsigned long)scene.pixelFormat,
                      (unsigned long)dh.width, (unsigned long)dh.height, (unsigned long)dh.pixelFormat]);
            return 0;
        }
        gPendingDistant = YES;
        gPendingScene = scene;
        gPendingFar = dh;
        gPendingPair[0] = pairA;
        gPendingPair[1] = pairB;
        return 1;
    }
}

// Runs the kept merges now (frame generation copies the depth on the Vulkan side when there was no temporal upscale).
int mfx_merges_flush(uintptr_t sharedEvent, uint64_t waitValue, uint64_t signalValue) {
    @autoreleasepool {
        if (gQueue == nil || sharedEvent == 0) {
            setError(@"bridge not initialised");
            return -1;
        }
        id<MTLSharedEvent> event = (__bridge id<MTLSharedEvent>)(void *)sharedEvent;
        id<MTLCommandBuffer> cb = [gQueue commandBuffer];
        if (cb == nil) {
            setError(@"failed to create MTLCommandBuffer");
            return -1;
        }
        cb.label = @"MetalFX Depth Merges";
        encodeWait(event, waitValue);
        encodePendingMerges(cb);
        [cb encodeSignalEvent:event value:signalValue];
        [cb commit];
        return 1;
    }
}

// Forgets the kept merges (a frame that had nothing to use them).
void mfx_merges_discard(void) {
    forgetMerges();
}

int mfx_upscale_temporal(uintptr_t inTex, uintptr_t sceneDepthTex, uintptr_t handDepthTex, uintptr_t dstTex,
                         uintptr_t sharedEvent, uint64_t waitValue, uint64_t signalValue, uintptr_t paramsPtr) {
    @autoreleasepool {
        if (gQueue == nil || sharedEvent == 0 || paramsPtr == 0) {
            setError(@"bridge not initialised");
            return -1;
        }
        const MfxTemporalParams *params = (const MfxTemporalParams *)(void *)paramsPtr;
        id<MTLSharedEvent> event = (__bridge id<MTLSharedEvent>)(void *)sharedEvent;
        id<MTLCommandBuffer> cb = [gQueue commandBuffer];
        if (cb == nil) {
            setError(@"failed to create MTLCommandBuffer");
            return -1;
        }
        cb.label = @"MetalFX Temporal Upscale";
        encodeWait(event, waitValue);
        encodePendingMerges(cb); // the kept depth merges, before anything reads the depth

        int result = 0;
        id<MTLTexture> in = (__bridge id<MTLTexture>)(void *)inTex;
        id<MTLTexture> depth = (__bridge id<MTLTexture>)(void *)sceneDepthTex;
        id<MTLTexture> hand = (__bridge id<MTLTexture>)(void *)handDepthTex;
        id<MTLTexture> dst = (__bridge id<MTLTexture>)(void *)dstTex;
        BOOL fsr = params->kind == 4;
        BOOL ready = in != nil && depth != nil && hand != nil && dst != nil
            && (fsr ? ensureFsr(in, dst) : ensureTemporal(in, depth, dst));
        if (ready && fsr) {
            cb = encodeFsr(cb, in, depth, hand, dst, params);
            result = 1;
        } else if (ready) {
            id<MTLTexture> output = nil;
            cb = encodeTemporalWork(cb, in, depth, hand, params, &output);
            encodeOutput(cb, output, dst, params->sharpness);
            result = 1;
        }

        [cb encodeSignalEvent:event value:signalValue];
        if (gProfile) addStageTimer(cb, 2);
        addCompletionHandler(cb);
        [cb commit];
        return result;
    }
}

// ---------------------------------------------------------------------------------------------
// Frame generation: AMD FSR 3.1 optical flow + frame interpolation (FidelityFX SDK 1.1.4, MIT licence) and our own
// presenter.
//
// The passes in fsr3_fg_shaders.h are AMD's GLSL translated to MSL; the host code below mirrors ffx_opticalflow.cpp and
// ffx_frameinterpolation.cpp for one permutation (LDR sRGB back buffer, render-size motion vectors without jitter,
// reversed-Z depth, a HUD-less image, no distortion field).
//
// Minecraft stops presenting while frame generation runs (Java skips its swapchain acquire). Each frame Java hands us
// the final image (with HUD), the world image before the HUD, the scene depth and the first-person hand depth:
//   copy queue  waits for Vulkan, copies the inputs into a staging set and the final image into a presentation slot,
//               then releases Vulkan (so the next frame renders while frame generation runs)
//   fg queue    camera motion + depth, optical flow, frame interpolation, then the current frame's hand pasted over
//               the generated one (the hand moves with the camera; interpolating it only smears it)
//   presenter   a CAMetalLayer overlaid on the game's view, driven by a CAMetalDisplayLink on its own thread: shows the
//               generated frame on the first refresh after it is ready and the real frame half a frame time later.
//               The render thread waits for a free slot (at most two frames queued), so the game runs at up to half
//               the refresh rate and every rendered frame reaches the screen twice as smoothly.
// ---------------------------------------------------------------------------------------------

#include "fsr3_fg_shaders.h"

// Must match fgParams in MetalBackend.java (544 bytes).
typedef struct {
    float curInvViewProj[16];   // inverse(unjittered projection * view rotation), current frame
    float prevViewProj[16];     // unjittered projection * view rotation, previous frame
    float camDelta[4];          // current camera position - previous camera position
    uint32_t zZeroToOne, flipY, reset, debugView; // debugView 1 = also copy the generated frame into the final image
    float nearPlane, farPlane, tanHalfFovX, tanHalfFovY;
    float motionScaleX, motionScaleY, frameTimeMs;
    uint32_t backend;           // 0 = FSR 3, 1 = MetalFX frame interpolation
    float crossHalfX, crossHalfY; // F3 axis crosshair: half size of a centred box (fraction of the image), 0 = none
    uint32_t menuOpen;          // a blurred menu covers the world: frame generation shows the real frame
    uint32_t pad4;
    float objMin[4], objMax[4]; // third person: the player's box relative to the camera (min > max = none)
    float objDelta[4];          // the player's movement since the previous frame
    uint32_t vignette;          // the shader pack's vignette drawn over the final image (PackVignette.*, 0 = none)
    float vignetteA, vignetteB; // its settings
    uint32_t pad5;
    uint32_t handMotion;        // the hands' matrices, as in MfxTemporalParams
    uint32_t pad6[3];
    float handClipToLocal[2][16];
    float prevHandLocalToClip[2][16];
} MfxFrameGenParams;
_Static_assert(sizeof(MfxFrameGenParams) == 544, "FrameGenParams layout");

// cbFI (FrameInterpolationConstants).
typedef struct {
    int32_t renderSize[2], displaySize[2];
    float displaySizeRcp[2];
    float cameraNear, cameraFar;
    int32_t upscalerTargetSize[2];
    int32_t mode, reset;
    float deviceToViewDepth[4];
    float deltaTime;
    int32_t hudLessAttachedFactor;
    int32_t distortionFieldSize[2];
    float opticalFlowScale[2];
    int32_t opticalFlowBlockSize;
    uint32_t dispatchFlags;
    int32_t maxRenderSize[2];
    int32_t opticalFlowHalfResMode, numInstances;
    int32_t interpolationRectBase[2], interpolationRectSize[2];
    float debugBarColor[3];
    uint32_t backBufferTransferFunction;
    float minMaxLuminance[2];
    float tanHalfFov;
    int32_t pad1;
    float jitter[2];
    float motionVectorScale[2];
} FgFiConstants;
_Static_assert(offsetof(FgFiConstants, deviceToViewDepth) == 48, "cbFI layout");
_Static_assert(offsetof(FgFiConstants, debugBarColor) == 128, "cbFI layout");
_Static_assert(offsetof(FgFiConstants, minMaxLuminance) == 144, "cbFI layout");
_Static_assert(sizeof(FgFiConstants) == 176, "cbFI layout");

// cbOF, cbOF_SPD, cbInpaintingPyramid.
typedef struct {
    int32_t inputLumaResolution[2];
    uint32_t level, levelCount, frameIndex, backbufferTransferFunction;
    float minMaxLuminance[2];
} FgOfConstants;
typedef struct { uint32_t mips, numWorkGroups, workGroupOffset[2], numWorkGroupsInputPyramid, pad[3]; } FgOfSpdConstants;
typedef struct { uint32_t mips, numWorkGroups, workGroupOffset[2]; } FgPyramidConstants;

enum {
    FG_OF_PREPARE_LUMA, FG_OF_LUMINANCE_PYRAMID, FG_OF_SCD_HISTOGRAM, FG_OF_SCD_DIVERGENCE, FG_OF_SEARCH, FG_OF_FILTER, FG_OF_SCALE,
    FG_FI_RECONSTRUCT_AND_DILATE, FG_FI_SETUP, FG_FI_RECONSTRUCT_PREVIOUS_DEPTH, FG_FI_GAME_MOTION_VECTOR_FIELD,
    FG_FI_GAME_VECTOR_FIELD_INPAINTING_PYRAMID, FG_FI_OPTICAL_FLOW_VECTOR_FIELD, FG_FI_DISOCCLUSION_MASK, FG_FI_INTERPOLATION,
    FG_FI_INPAINTING_PYRAMID, FG_FI_INPAINTING, FG_PASS_COUNT
};
static const char *const kFgPassSources[FG_PASS_COUNT] = {
    kFsrSource_of_prepare_luma, kFsrSource_of_luminance_pyramid, kFsrSource_of_scd_histogram, kFsrSource_of_scd_divergence,
    kFsrSource_of_search, kFsrSource_of_filter, kFsrSource_of_scale,
    kFsrSource_fi_reconstruct_and_dilate, kFsrSource_fi_setup, kFsrSource_fi_reconstruct_previous_depth,
    kFsrSource_fi_game_motion_vector_field, kFsrSource_fi_game_vector_field_inpainting_pyramid,
    kFsrSource_fi_optical_flow_vector_field, kFsrSource_fi_disocclusion_mask, kFsrSource_fi_interpolation,
    kFsrSource_fi_inpainting_pyramid, kFsrSource_fi_inpainting,
};
static NSString *const kFgPassNames[FG_PASS_COUNT] = {
    @"fsr_of_prepare_luma", @"fsr_of_luminance_pyramid", @"fsr_of_scd_histogram", @"fsr_of_scd_divergence",
    @"fsr_of_search", @"fsr_of_filter", @"fsr_of_scale",
    @"fsr_fi_reconstruct_and_dilate", @"fsr_fi_setup", @"fsr_fi_reconstruct_previous_depth",
    @"fsr_fi_game_motion_vector_field", @"fsr_fi_game_vector_field_inpainting_pyramid",
    @"fsr_fi_optical_flow_vector_field", @"fsr_fi_disocclusion_mask", @"fsr_fi_interpolation",
    @"fsr_fi_inpainting_pyramid", @"fsr_fi_inpainting",
};

static NSString *const kFgShader = @"\
#include <metal_stdlib>\n\
using namespace metal;\n" VIGNETTE_MSL "\
struct CompositeParams { uint menuOpen; VignetteParams vignette; };\n\
struct FgParams {\n\
    float4x4 curInvViewProj; float4x4 prevViewProj; float4 camDelta;\n\
    uint zZeroToOne; uint flipY; uint reset; uint debugView;\n\
    float nearPlane; float farPlane; float tanHalfFovX; float tanHalfFovY;\n\
    float motionScaleX; float motionScaleY; float frameTimeMs; uint backend;\n\
    float2 cross; uint menuOpen; uint pad4; float4 objMin; float4 objMax; float4 objDelta;\n\
    uint vignette; float vignetteA; float vignetteB; uint pad5;\n\
    uint4 hand; float4x4 handToLocal[2]; float4x4 prevHand[2];\n\
};\n\
static float3 prevRelative(float3 rel, float4 camDelta, float4 objMin, float4 objMax, float4 objDelta) {\n\
    float3 prev = rel + camDelta.xyz;\n\
    if (all(rel >= objMin.xyz) && all(rel <= objMax.xyz)) prev -= objDelta.xyz;\n\
    return prev;\n\
}\n\
// MetalFX backend: colour, depth (the hand's own where it is drawn) and camera motion at the interpolation size.\n\
kernel void fg_mfx_prepare(texture2d<float, access::sample> world [[texture(0)]],\n\
                           depth2d<float, access::read> sceneDepth [[texture(1)]],\n\
                           depth2d<float, access::read> handDepth [[texture(2)]],\n\
                           texture2d<float, access::write> color [[texture(3)]],\n\
                           texture2d<float, access::write> depthOut [[texture(4)]],\n\
                           texture2d<half, access::write> motion [[texture(5)]],\n\
                           constant FgParams &p [[buffer(0)]],\n\
                           uint2 gid [[thread_position_in_grid]]) {\n\
    uint w = color.get_width(), h = color.get_height();\n\
    if (gid.x >= w || gid.y >= h) return;\n\
    float2 size = float2(w, h);\n\
    float2 uv = (float2(gid) + 0.5) / size;\n\
    constexpr sampler lin(filter::linear, address::clamp_to_edge);\n\
    color.write(world.sample(lin, uv), gid);\n\
    uint2 ds = uint2(sceneDepth.get_width(), sceneDepth.get_height());\n\
    uint2 hs = uint2(handDepth.get_width(), handDepth.get_height());\n\
    float hand = handDepth.read(min(uint2(uv * float2(hs)), hs - 1));\n\
    float d = sceneDepth.read(min(uint2(uv * float2(ds)), ds - 1));\n\
    float2 ndc = float2(uv.x * 2.0 - 1.0, p.flipY ? 1.0 - uv.y * 2.0 : uv.y * 2.0 - 1.0);\n\
    if (hand > 0.0 && hand != d) {\n\
        depthOut.write(float4(hand), gid);\n\
        float2 mv = handMotion(p.hand.x, p.handToLocal[0], p.handToLocal[1], p.prevHand[0], p.prevHand[1], ndc, hand, p.zZeroToOne != 0, p.flipY != 0, uv, size);\n\
        motion.write(half4(half2(mv), 0.0h, 0.0h), gid);\n\
        return;\n\
    }\n\
    depthOut.write(float4(d), gid);\n\
    float z = p.zZeroToOne ? d : d * 2.0 - 1.0;\n\
    float4 rel = p.curInvViewProj * float4(ndc, z, 1.0);\n\
    rel /= rel.w;\n\
    float4 prevClip = p.prevViewProj * float4(prevRelative(rel.xyz, p.camDelta, p.objMin, p.objMax, p.objDelta), 1.0);\n\
    float2 prevNdc = prevClip.xy / prevClip.w;\n\
    float2 prevUv = float2(prevNdc.x * 0.5 + 0.5, p.flipY ? (1.0 - prevNdc.y) * 0.5 : prevNdc.y * 0.5 + 0.5);\n\
    float2 mv = (prevUv - uv) * size;\n\
    if (prevClip.w <= 0.0) mv = float2(0.0);\n\
    motion.write(half4(half2(mv), 0.0h, 0.0h), gid);\n\
}\n\
// MetalFX backend: generated world image + this frame's HUD (the difference between the final and the world image).\n\
// The final image has the pack's vignette drawn over the world image: the generated one gets the same.\n\
kernel void fg_mfx_composite(texture2d<float, access::read> generated [[texture(0)]],\n\
                             texture2d<float, access::read> world [[texture(1)]],\n\
                             texture2d<float, access::read> final [[texture(2)]],\n\
                             texture2d<float, access::write> out [[texture(3)]],\n\
                             constant CompositeParams &cp [[buffer(0)]],\n\
                             uint2 gid [[thread_position_in_grid]]) {\n\
    if (gid.x >= out.get_width() || gid.y >= out.get_height()) return;\n\
    float3 f = final.read(gid).rgb;\n\
    // A blurred menu: final - world is not the HUD but the whole (blurred) world, which would flicker against it.\n\
    if (cp.menuOpen != 0) { out.write(float4(f, 1.0), gid); return; }\n\
    float2 size = float2(out.get_width(), out.get_height());\n\
    VignetteParams v = cp.vignette;\n\
    float3 g = packVignette(generated.read(gid).rgb, gid, size, v.kind, v.a, v.b);\n\
    float3 delta = f - packVignette(world.read(gid).rgb, gid, size, v.kind, v.a, v.b);\n\
    float keep = smoothstep(0.08, 0.3, max(max(abs(delta.r), abs(delta.g)), abs(delta.b)));\n\
    out.write(float4(mix(saturate(g + delta), f, keep), 1.0), gid);\n\
}\n\
// FSR backend: the same, in place on the generated image.\n\
kernel void fg_fsr_composite(texture2d<float, access::read_write> gen [[texture(0)]],\n\
                             texture2d<float, access::read> world [[texture(1)]],\n\
                             texture2d<float, access::read> final [[texture(2)]],\n\
                             constant CompositeParams &cp [[buffer(0)]],\n\
                             uint2 gid [[thread_position_in_grid]]) {\n\
    if (gid.x >= gen.get_width() || gid.y >= gen.get_height()) return;\n\
    float3 f = final.read(gid).rgb;\n\
    if (cp.menuOpen != 0) { gen.write(float4(f, 1.0), gid); return; }\n\
    float2 size = float2(gen.get_width(), gen.get_height());\n\
    VignetteParams v = cp.vignette;\n\
    float3 g = packVignette(gen.read(gid).rgb, gid, size, v.kind, v.a, v.b);\n\
    float3 delta = f - packVignette(world.read(gid).rgb, gid, size, v.kind, v.a, v.b);\n\
    float keep = smoothstep(0.08, 0.3, max(max(abs(delta.r), abs(delta.g)), abs(delta.b)));\n\
    gen.write(float4(mix(saturate(g + delta), f, keep), 1.0), gid);\n\
}\n\
// Camera motion vectors (pixels, current -> previous, as for the FSR upscaler) and depth including the hand.\n\
kernel void fg_inputs(depth2d<float, access::read> sceneDepth [[texture(0)]],\n\
                      depth2d<float, access::read> handDepth [[texture(1)]],\n\
                      texture2d<float, access::write> depthOut [[texture(2)]],\n\
                      texture2d<half, access::write> motion [[texture(3)]],\n\
                      constant FgParams &p [[buffer(0)]],\n\
                      uint2 gid [[thread_position_in_grid]]) {\n\
    uint w = motion.get_width(), h = motion.get_height();\n\
    if (gid.x >= w || gid.y >= h) return;\n\
    uint2 ds = uint2(sceneDepth.get_width(), sceneDepth.get_height());\n\
    float d = sceneDepth.read(min(uint2((float2(gid) + 0.5) * float2(ds) / float2(w, h)), ds - 1));\n\
    uint2 hs = uint2(handDepth.get_width(), handDepth.get_height());\n\
    float hand = handDepth.read(min(uint2((float2(gid) + 0.5) * float2(hs) / float2(w, h)), hs - 1));\n\
    float2 size = float2(w, h);\n\
    float2 uv = (float2(gid) + 0.5) / size;\n\
    float2 ndc = float2(uv.x * 2.0 - 1.0, p.flipY ? 1.0 - uv.y * 2.0 : uv.y * 2.0 - 1.0);\n\
    if (hand > 0.0 && hand != d) {\n\
        depthOut.write(float4(max(d, hand)), gid);\n\
        float2 mv = handMotion(p.hand.x, p.handToLocal[0], p.handToLocal[1], p.prevHand[0], p.prevHand[1], ndc, hand, p.zZeroToOne != 0, p.flipY != 0, uv, size);\n\
        motion.write(half4(half2(mv), 0.0h, 0.0h), gid);\n\
        return;\n\
    }\n\
    depthOut.write(float4(d), gid);\n\
    float z = p.zZeroToOne ? d : d * 2.0 - 1.0;\n\
    float4 rel = p.curInvViewProj * float4(ndc, z, 1.0);\n\
    rel /= rel.w;\n\
    float4 prevClip = p.prevViewProj * float4(prevRelative(rel.xyz, p.camDelta, p.objMin, p.objMax, p.objDelta), 1.0);\n\
    float2 prevNdc = prevClip.xy / prevClip.w;\n\
    float2 prevUv = float2(prevNdc.x * 0.5 + 0.5, p.flipY ? (1.0 - prevNdc.y) * 0.5 : prevNdc.y * 0.5 + 0.5);\n\
    float2 mv = (prevUv - uv) * size;\n\
    if (prevClip.w <= 0.0) mv = float2(0.0);\n\
    motion.write(half4(half2(mv), 0.0h, 0.0h), gid);\n\
}\n\
// Hand mask at 1/4 of the hand depth's resolution: where the first-person hand is now or was in the previous frame (the\n\
// interpolators blend the previous world image, hand included, so its old place would show a ghost hand). Not needed\n\
// when the hand has its own motion vectors (handMoves): then only the F3 crosshair box.\n\
kernel void fg_hand_mask(depth2d<float, access::read> handDepth [[texture(0)]],\n\
                         depth2d<float, access::read> prevHandDepth [[texture(1)]],\n\
                         texture2d<float, access::write> mask [[texture(2)]],\n\
                         depth2d<float, access::read> sceneDepth [[texture(3)]],\n\
                         depth2d<float, access::read> prevSceneDepth [[texture(4)]],\n\
                         constant float4 &cross [[buffer(0)]],\n\
                         constant uint &handMoves [[buffer(1)]],\n\
                         uint2 gid [[thread_position_in_grid]]) {\n\
    if (gid.x >= mask.get_width() || gid.y >= mask.get_height()) return;\n\
    float2 fromCentre = abs((float2(gid) + 0.5) / float2(mask.get_width(), mask.get_height()) - 0.5);\n\
    if ((cross.x > 0.0 && all(fromCentre < cross.xy)) || (cross.z > 0.0 && all(fromCentre < cross.zw))) { mask.write(float4(1.0), gid); return; }\n\
    if (handMoves != 0) { mask.write(float4(0.0), gid); return; }\n\
    uint2 lim = uint2(handDepth.get_width() - 1, handDepth.get_height() - 1);\n\
    uint2 slim = uint2(sceneDepth.get_width() - 1, sceneDepth.get_height() - 1);\n\
    float2 toScene = float2(sceneDepth.get_width(), sceneDepth.get_height()) / float2(handDepth.get_width(), handDepth.get_height());\n\
    float m = 0.0;\n\
    for (uint y = 0; y < 4; y++) for (uint x = 0; x < 4; x++) {\n\
        uint2 q = min(gid * 4 + uint2(x, y), lim);\n\
        // Pixel centres: Metal's fast math makes 1512.0 / 1512.0 slightly below 1, so q * toScene would truncate to q - 1.\n\
        uint2 sq = min(uint2((float2(q) + 0.5) * toScene), slim);\n\
        float h = handDepth.read(q), ph = prevHandDepth.read(q);\n\
        if ((h > 0.0 && h != sceneDepth.read(sq)) || (ph > 0.0 && ph != prevSceneDepth.read(sq))) m = 1.0;\n\
    }\n\
    mask.write(float4(m), gid);\n\
}\n\
// The hand (and a border of one mask texel) comes from the current real frame.\n\
kernel void fg_hand(texture2d<float, access::read> mask [[texture(0)]],\n\
                    texture2d<float, access::read> real [[texture(1)]],\n\
                    texture2d<float, access::read_write> gen [[texture(2)]],\n\
                    uint2 gid [[thread_position_in_grid]]) {\n\
    uint w = gen.get_width(), h = gen.get_height();\n\
    if (gid.x >= w || gid.y >= h) return;\n\
    int2 ms = int2(mask.get_width(), mask.get_height());\n\
    int2 c = int2((float2(gid) + 0.5) * float2(ms) / float2(w, h));\n\
    float m = 0.0;\n\
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) m = max(m, mask.read(uint2(clamp(c + int2(x, y), int2(0), ms - 1))).r);\n\
    if (m > 0.0) gen.write(real.read(gid), gid);\n\
}\n\
// Optical flow input at a reduced resolution: box filter of the world image.\n\
kernel void fg_half(texture2d<float, access::read> src [[texture(0)]],\n\
                    texture2d<float, access::write> dst [[texture(1)]],\n\
                    constant uint &div [[buffer(0)]],\n\
                    uint2 gid [[thread_position_in_grid]]) {\n\
    if (gid.x >= dst.get_width() || gid.y >= dst.get_height()) return;\n\
    uint2 lim = uint2(src.get_width() - 1, src.get_height() - 1);\n\
    float4 sum = 0.0;\n\
    for (uint y = 0; y < div; y++) for (uint x = 0; x < div; x++) sum += src.read(min(gid * div + uint2(x, y), lim));\n\
    dst.write(sum / float(div * div), gid);\n\
}\n\
kernel void fg_clear_f(texture2d<float, access::write> t [[texture(0)]], constant float4 &v [[buffer(0)]], uint2 gid [[thread_position_in_grid]]) {\n\
    if (gid.x < t.get_width() && gid.y < t.get_height()) t.write(v, gid);\n\
}\n\
kernel void fg_clear_u(texture2d<uint, access::write> t [[texture(0)]], constant uint4 &v [[buffer(0)]], uint2 gid [[thread_position_in_grid]]) {\n\
    if (gid.x < t.get_width() && gid.y < t.get_height()) t.write(v, gid);\n\
}\n\
struct PresentOut { float4 position [[position]]; float2 uv; };\n\
// Minecraft's frames are stored bottom row first (its own swapchain blit flips them vertically): so does this.\n\
vertex PresentOut fg_present_vs(uint vid [[vertex_id]]) {\n\
    float2 q = float2((vid << 1) & 2, vid & 2);\n\
    PresentOut o; o.position = float4(q * 2.0 - 1.0, 0.0, 1.0); o.uv = q; return o;\n\
}\n\
fragment float4 fg_present_fs(PresentOut in [[stage_in]], texture2d<float> src [[texture(0)]]) {\n\
    constexpr sampler s(filter::linear, address::clamp_to_edge);\n\
    return float4(src.sample(s, in.uv).rgb, 1.0);\n\
}\n\
";

static id<MTLLibrary> gFgLibrary;
static id<MTLComputePipelineState> gFgPipelines[FG_PASS_COUNT];
static id<MTLComputePipelineState> gFgInputsPipeline, gFgHandPipeline, gFgHalfPipeline, gFgClearF, gFgClearU;
static id<MTLComputePipelineState> gMfxPreparePipeline, gMfxCompositePipeline, gFsrCompositePipeline, gFgHandMaskPipeline;
static id<MTLTexture> gFgHandMask;
static id<MTLSamplerState> gFgLinearClamp;
static BOOL gFgCompileFailed;
static id<MTLCommandQueue> gFgQueue, gFgCopyQueue, gPresentQueue;
static id<MTLEvent> gFgEvent; // value n = frame generation for world frame n finished
// value n = the copies of world frame n are done. (Not the shared event: the game's next frame signals a higher value
// on that as soon as its world is drawn, which can be before these copies ran, and frame generation saw half-copied inputs.)
static id<MTLEvent> gFgCopyEvent;

// FSR resources (fg queue only).
static id<MTLTexture> gFgOfInput[2][7], gFgOf[2][7], gFgOfVector, gFgScdHist, gFgScdPrevHist, gFgScdTemp, gFgScdOut, gFgOfConfidence;
static id<MTLTexture> gFgMotion, gFgDepth, gFgDilatedMotion, gFgDilatedDepth, gFgReconPrevDepth, gFgReconInterpDepth;
static id<MTLTexture> gFgGameMvX, gFgGameMvY, gFgOfMvX, gFgOfMvY, gFgDisocclusion, gFgPyramid, gFgPyramidMips[13], gFgDistortion;
static id<MTLBuffer> gFgCounters;
static NSUInteger gFgDispW, gFgDispH, gFgRenW, gFgRenH; // gFgRen* = frame interpolation's "render" size (see gFgFiDiv)
static NSUInteger gFgDepthW, gFgDepthH;
// Our motion vectors come from depth, so frame interpolation's render-resolution passes can run below the game's render
// resolution: at most display / gFgFiDiv.
static uint32_t gFgFiDiv = 2;
static uint32_t gFgLastBackend;
// Optical flow runs at display / gFgOfDiv (it costs most of the frame generation time at full resolution).
static uint32_t gFgOfDiv = 4;
static NSUInteger gFgOfW, gFgOfH;
static id<MTLTexture> gFgOfColor;
static MTLPixelFormat gFgColorFmt;
static BOOL gFgNeedsClear;
static uint32_t gFgOfFrameIndex, gFgOfResourceFrame;

// Staging copies of the inputs (written by the copy queue; set n % 3 holds world frame n).
static id<MTLTexture> gFgStageWorld[3], gFgStageDepth[3], gFgStageHand[3];
static NSUInteger gFgHandW, gFgHandH;
static MTLPixelFormat gFgDepthFmt, gFgHandFmt;
static uint64_t gFgFrame;       // world frames submitted
static BOOL gFgHistory;         // the previous submission was a world frame of the same size

// Presentation slots: a generated + a real frame each. Guarded by gSlotLock.
#define FG_SLOT_COUNT 4
enum { SLOT_FREE, SLOT_RESERVED, SLOT_WRITING, SLOT_READY, SLOT_HALF };
static id<MTLTexture> gSlotGen[FG_SLOT_COUNT], gSlotReal[FG_SLOT_COUNT];
static int gSlotState[FG_SLOT_COUNT], gSlotReading[FG_SLOT_COUNT];
static BOOL gSlotHasGen[FG_SLOT_COUNT];
static uint64_t gSlotSeq[FG_SLOT_COUNT], gSlotSeqCounter;
static double gSlotGenShownAt[FG_SLOT_COUNT];
static int gReservedSlot = -1;
static double gLastReadyTime, gRealInterval = 1.0 / 60.0;
static pthread_mutex_t gSlotLock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t gSlotCond = PTHREAD_COND_INITIALIZER;
static atomic_int gGeneratedShown;

// Presenter. gPresenterState: 0 off, 1 attaching, 2 live.
static atomic_int gPresenterState;
static NSView *gOverlay;              // main thread
static CAMetalLayer *gOverlayLayer;
static id gDisplayLink;               // CAMetalDisplayLink
static id gPresenterDelegate;
static double gRefreshPeriod = 1.0 / 120.0;
static id<MTLRenderPipelineState> gPresentPipeline;
static MTLPixelFormat gPresentFormat;
static int gAttachFailures;

// Returns 1 if this macOS has what frame generation needs (CAMetalDisplayLink, texture atomics).
int mfx_frame_interpolation_supported(void) {
    if (gDevice == nil) return 0;
    if (@available(macOS 14.0, *)) return 1;
    return 0;
}

static BOOL ensureFgPipelines(void) {
    if (gFgPipelines[FG_FI_INPAINTING] != nil) return YES;
    if (gFgCompileFailed) return NO;
    NSError *error = nil;
    gFgLibrary = [gDevice newLibraryWithSource:kFgShader options:nil error:&error];
    if (gFgLibrary == nil) {
        gFgCompileFailed = YES;
        setError([NSString stringWithFormat:@"frame generation shader compile failed: %@", error.localizedDescription]);
        return NO;
    }
    gFgInputsPipeline = [gDevice newComputePipelineStateWithFunction:[gFgLibrary newFunctionWithName:@"fg_inputs"] error:&error];
    gFgHandPipeline = [gDevice newComputePipelineStateWithFunction:[gFgLibrary newFunctionWithName:@"fg_hand"] error:&error];
    gFgHalfPipeline = [gDevice newComputePipelineStateWithFunction:[gFgLibrary newFunctionWithName:@"fg_half"] error:&error];
    gFgHandMaskPipeline = [gDevice newComputePipelineStateWithFunction:[gFgLibrary newFunctionWithName:@"fg_hand_mask"] error:&error];
    gMfxPreparePipeline = [gDevice newComputePipelineStateWithFunction:[gFgLibrary newFunctionWithName:@"fg_mfx_prepare"] error:&error];
    gMfxCompositePipeline = [gDevice newComputePipelineStateWithFunction:[gFgLibrary newFunctionWithName:@"fg_mfx_composite"] error:&error];
    gFsrCompositePipeline = [gDevice newComputePipelineStateWithFunction:[gFgLibrary newFunctionWithName:@"fg_fsr_composite"] error:&error];
    gFgClearF = [gDevice newComputePipelineStateWithFunction:[gFgLibrary newFunctionWithName:@"fg_clear_f"] error:&error];
    gFgClearU = [gDevice newComputePipelineStateWithFunction:[gFgLibrary newFunctionWithName:@"fg_clear_u"] error:&error];
    MTLCompileOptions *options = [MTLCompileOptions new];
    if (@available(macOS 14.0, *)) options.languageVersion = MTLLanguageVersion3_1;
    options.fastMathEnabled = YES;
    __block NSString *failure = nil;
    dispatch_apply(FG_PASS_COUNT, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^(size_t i) {
        NSError *passError = nil;
        id<MTLLibrary> lib = [gDevice newLibraryWithSource:@(kFgPassSources[i]) options:options error:&passError];
        id<MTLFunction> fn = [lib newFunctionWithName:kFgPassNames[i]];
        id<MTLComputePipelineState> pso = fn != nil ? [gDevice newComputePipelineStateWithFunction:fn error:&passError] : nil;
        @synchronized (gDevice) {
            if (pso == nil) failure = [NSString stringWithFormat:@"%@: %@", kFgPassNames[i], passError.localizedDescription];
            gFgPipelines[i] = pso;
        }
    });
    if (failure == nil && (gFgInputsPipeline == nil || gFgHandPipeline == nil || gFgHalfPipeline == nil || gMfxPreparePipeline == nil || gMfxCompositePipeline == nil || gFsrCompositePipeline == nil || gFgHandMaskPipeline == nil || gFgClearF == nil || gFgClearU == nil)) {
        failure = [NSString stringWithFormat:@"frame generation helpers: %@", error.localizedDescription];
    }
    const int spdPasses[] = {FG_OF_LUMINANCE_PYRAMID, FG_OF_SCD_DIVERGENCE, FG_FI_GAME_VECTOR_FIELD_INPAINTING_PYRAMID, FG_FI_INPAINTING_PYRAMID};
    for (int i = 0; failure == nil && i < 4; i++) {
        if (gFgPipelines[spdPasses[i]].maxTotalThreadsPerThreadgroup < 256) failure = [NSString stringWithFormat:@"%@ cannot run 256 threads", kFgPassNames[spdPasses[i]]];
    }
    if (failure != nil) {
        for (int i = 0; i < FG_PASS_COUNT; i++) gFgPipelines[i] = nil;
        gFgCompileFailed = YES;
        setError([NSString stringWithFormat:@"FSR 3 frame generation pipeline failed: %@", failure]);
        return NO;
    }
    MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
    sd.sAddressMode = sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
    sd.minFilter = sd.magFilter = MTLSamplerMinMagFilterLinear;
    sd.mipFilter = MTLSamplerMipFilterLinear;
    gFgLinearClamp = [gDevice newSamplerStateWithDescriptor:sd];
    return YES;
}

static void fgDispatch(id<MTLComputeCommandEncoder> ce, int pass, NSUInteger gx, NSUInteger gy, NSUInteger gz,
                       NSUInteger tx, NSUInteger ty, NSUInteger tz) {
    [ce setComputePipelineState:gFgPipelines[pass]];
    [ce dispatchThreadgroups:MTLSizeMake(MAX(gx, 1), MAX(gy, 1), MAX(gz, 1)) threadsPerThreadgroup:MTLSizeMake(tx, ty, tz)];
}

static void fgClearF(id<MTLComputeCommandEncoder> ce, id<MTLTexture> t, float value) {
    float v[4] = {value, value, value, value};
    [ce setComputePipelineState:gFgClearF];
    [ce setTexture:t atIndex:0];
    [ce setBytes:v length:sizeof v atIndex:0];
    dispatch2D(ce, gFgClearF, t.width, t.height);
}

static void fgClearU(id<MTLComputeCommandEncoder> ce, id<MTLTexture> t, uint32_t value) {
    uint32_t v[4] = {value, value, value, value};
    [ce setComputePipelineState:gFgClearU];
    [ce setTexture:t atIndex:0];
    [ce setBytes:v length:sizeof v atIndex:0];
    dispatch2D(ce, gFgClearU, t.width, t.height);
}

static id<MTLTexture> fgTexture(MTLPixelFormat format, NSUInteger w, NSUInteger h, NSUInteger mips, NSString *label) {
    MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:MAX(w, 1) height:MAX(h, 1) mipmapped:NO];
    d.mipmapLevelCount = mips;
    d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
    if (@available(macOS 14.0, *)) {
        if (format == MTLPixelFormatR32Uint) d.usage |= MTLTextureUsageShaderAtomic;
    }
    d.storageMode = MTLStorageModePrivate;
    id<MTLTexture> t = [gDevice newTextureWithDescriptor:d];
    t.label = label;
    return t;
}

// Blit destination shaped like `like`, read by shaders.
static id<MTLTexture> copyTarget(id<MTLTexture> like, NSString *label) {
    return privateTexture(like.pixelFormat, like.width, like.height, MTLTextureUsageShaderRead, label);
}

// (Re)creates the FSR resources and staging copies for these inputs. Sets gFgHistory = NO when anything changed.
static BOOL ensureFgResources(id<MTLTexture> world, id<MTLTexture> depth, id<MTLTexture> hand) {
    if (!ensureFgPipelines()) return NO;
    if (gFgMotion != nil && gFgDispW == world.width && gFgDispH == world.height && gFgColorFmt == world.pixelFormat
        && gFgDepthW == depth.width && gFgDepthH == depth.height && gFgDepthFmt == depth.pixelFormat
        && gFgHandW == hand.width && gFgHandH == hand.height && gFgHandFmt == hand.pixelFormat) {
        return YES;
    }
    gFgMotion = nil;
    gFgHistory = NO;
    const char *fiEnv = getenv("MFX_FG_FIDIV");
    if (fiEnv != NULL) gFgFiDiv = (uint32_t)MIN(MAX(atoi(fiEnv), 1), 4);
    NSUInteger dw = world.width, dh = world.height;
    NSUInteger rw = MIN(depth.width, MAX(dw / gFgFiDiv, 64)), rh = MIN(depth.height, MAX(dh / gFgFiDiv, 64));
    BOOL ok = YES;
    for (int s = 0; s < 3; s++) {
        gFgStageWorld[s] = copyTarget(world, @"Frame gen world");
        gFgStageDepth[s] = copyTarget(depth, @"Frame gen depth");
        gFgStageHand[s] = copyTarget(hand, @"Frame gen hand depth");
        ok = ok && gFgStageWorld[s] != nil && gFgStageDepth[s] != nil && gFgStageHand[s] != nil;
    }
    // Optical flow (display / gFgOfDiv).
    const char *divEnv = getenv("MFX_FG_OFDIV");
    if (divEnv != NULL) gFgOfDiv = (uint32_t)MIN(MAX(atoi(divEnv), 1), 4);
    NSUInteger ow = MAX(dw / gFgOfDiv, 64), oh = MAX(dh / gFgOfDiv, 64);
    gFgOfColor = gFgOfDiv > 1 ? fgTexture(world.pixelFormat, ow, oh, 1, @"OF colour") : nil;
    ok = ok && (gFgOfDiv == 1 || gFgOfColor != nil);
    NSUInteger ofW = (ow + 7) / 8, ofH = (oh + 7) / 8;
    for (int i = 0; i < 2; i++) {
        NSUInteger w = ofW, h = ofH;
        for (int level = 0; level < 7; level++) {
            gFgOfInput[i][level] = fgTexture(MTLPixelFormatR8Uint, ow >> level, oh >> level, 1, @"OF input");
            gFgOf[i][level] = fgTexture(MTLPixelFormatRG16Sint, w, h, 1, @"OF");
            ok = ok && gFgOfInput[i][level] != nil && gFgOf[i][level] != nil;
            w = (w + 1) / 2;
            h = (h + 1) / 2;
        }
    }
    gFgOfVector = fgTexture(MTLPixelFormatRG16Sint, ofW, ofH, 1, @"OF vector");
    gFgScdHist = fgTexture(MTLPixelFormatR32Uint, 256 * 9, 1, 1, @"OF SCD histogram");
    gFgScdPrevHist = fgTexture(MTLPixelFormatR32Float, 256 * 9, 1, 1, @"OF SCD previous histogram");
    gFgScdTemp = fgTexture(MTLPixelFormatR32Uint, 3, 1, 1, @"OF SCD temp");
    gFgScdOut = fgTexture(MTLPixelFormatR32Uint, 3, 1, 1, @"OF SCD output");
    gFgOfConfidence = fgTexture(MTLPixelFormatR32Uint, 1, 1, 1, @"OF confidence (unused)");
    // Frame interpolation.
    gFgMotion = fgTexture(MTLPixelFormatRG16Float, rw, rh, 1, @"FI motion");
    gFgDepth = fgTexture(MTLPixelFormatR32Float, rw, rh, 1, @"FI depth");
    gFgDilatedMotion = fgTexture(MTLPixelFormatRG16Float, rw, rh, 1, @"FI dilated motion");
    gFgDilatedDepth = fgTexture(MTLPixelFormatR32Float, rw, rh, 1, @"FI dilated depth");
    gFgReconPrevDepth = fgTexture(MTLPixelFormatR32Uint, rw, rh, 1, @"FI reconstructed previous depth");
    gFgReconInterpDepth = fgTexture(MTLPixelFormatR32Uint, rw, rh, 1, @"FI reconstructed interpolated depth");
    gFgGameMvX = fgTexture(MTLPixelFormatR32Uint, rw, rh, 1, @"FI game motion X");
    gFgGameMvY = fgTexture(MTLPixelFormatR32Uint, rw, rh, 1, @"FI game motion Y");
    gFgOfMvX = fgTexture(MTLPixelFormatR32Uint, rw, rh, 1, @"FI optical flow motion X");
    gFgOfMvY = fgTexture(MTLPixelFormatR32Uint, rw, rh, 1, @"FI optical flow motion Y");
    gFgDisocclusion = fgTexture(MTLPixelFormatRG8Unorm, rw, rh, 1, @"FI disocclusion");
    NSUInteger pw = MAX(dw / 2, 1), ph = MAX(dh / 2, 1);
    NSUInteger pyramidMips = (NSUInteger)floor(log2((double)MAX(pw, ph))) + 1;
    gFgPyramid = fgTexture(MTLPixelFormatRGBA16Float, pw, ph, pyramidMips, @"FI inpainting pyramid");
    for (int i = 0; i < 13; i++) {
        gFgPyramidMips[i] = gFgPyramid == nil ? nil
            : [gFgPyramid newTextureViewWithPixelFormat:MTLPixelFormatRGBA16Float textureType:MTLTextureType2D
                                                levels:NSMakeRange(MIN((NSUInteger)i, pyramidMips - 1), 1) slices:NSMakeRange(0, 1)];
        ok = ok && gFgPyramidMips[i] != nil;
    }
    gFgDistortion = fgTexture(MTLPixelFormatRG8Unorm, 1, 1, 1, @"FI distortion field (none)");
    gFgHandMask = fgTexture(MTLPixelFormatR8Unorm, (hand.width + 3) / 4, (hand.height + 3) / 4, 1, @"Frame gen hand mask");
    ok = ok && gFgHandMask != nil;
    gFgCounters = [gDevice newBufferWithLength:16 options:MTLResourceStorageModeShared];
    if (gFgCounters != nil) memset(gFgCounters.contents, 0, 16);
    ok = ok && gFgOfVector != nil && gFgScdHist != nil && gFgScdPrevHist != nil && gFgScdTemp != nil && gFgScdOut != nil
        && gFgOfConfidence != nil && gFgMotion != nil && gFgDepth != nil && gFgDilatedMotion != nil && gFgDilatedDepth != nil
        && gFgReconPrevDepth != nil && gFgReconInterpDepth != nil && gFgGameMvX != nil && gFgGameMvY != nil && gFgOfMvX != nil
        && gFgOfMvY != nil && gFgDisocclusion != nil && gFgPyramid != nil && gFgDistortion != nil && gFgCounters != nil;
    if (!ok) {
        gFgMotion = nil;
        setError(@"failed to allocate frame generation resources");
        return NO;
    }
    gFgDispW = dw; gFgDispH = dh; gFgColorFmt = world.pixelFormat;
    gFgOfW = ow; gFgOfH = oh;
    gFgRenW = rw; gFgRenH = rh; gFgDepthW = depth.width; gFgDepthH = depth.height; gFgDepthFmt = depth.pixelFormat;
    gFgHandW = hand.width; gFgHandH = hand.height; gFgHandFmt = hand.pixelFormat;
    gFgNeedsClear = YES;
    return YES;
}

// SPD dispatch setup (ffxSpdSetup) for a w x h source: thread groups, and the mip count (-1 = from the size).
static FgPyramidConstants spdSetup(NSUInteger w, NSUInteger h, int mips, NSUInteger *gx, NSUInteger *gy) {
    *gx = (w - 1) / 64 + 1;
    *gy = (h - 1) / 64 + 1;
    FgPyramidConstants c = {0};
    c.numWorkGroups = (uint32_t)(*gx * *gy);
    c.mips = mips >= 0 ? (uint32_t)mips : (uint32_t)fmin(floor(log2((double)MAX(w, h))), 12.0);
    return c;
}

// Optical flow + frame interpolation for world frame `stage`, written into slot `slot`'s generated frame.
// Profiling (MFX_PROFILE): GPU time per group of frame-generation passes, from timestamps at encoder boundaries.
enum { FG_PROF_GROUPS = 10 };
static const char *const kFgProfNames[FG_PROF_GROUPS] = {
    "inputs", "of-luma", "of-search", "fi-prepare", "fi-game-mv", "fi-of-mv+disocc", "fi-interp", "fi-inpaint-pyr", "fi-inpaint", "hand"};
static id<MTLCounterSampleBuffer> gFgTimer;
static BOOL gFgTimerTried;
static long long gFgProfNs[FG_PROF_GROUPS];
static int gFgProfFrames;

static void fgEnsureTimer(void) {
    if (gFgTimerTried) return;
    gFgTimerTried = YES;
    if (![gDevice supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) return;
    for (id<MTLCounterSet> set in gDevice.counterSets) {
        if (![set.name isEqualToString:MTLCommonCounterSetTimestamp]) continue;
        MTLCounterSampleBufferDescriptor *d = [MTLCounterSampleBufferDescriptor new];
        d.counterSet = set;
        d.storageMode = MTLStorageModeShared;
        d.sampleCount = 2 * FG_PROF_GROUPS;
        gFgTimer = [gDevice newCounterSampleBufferWithDescriptor:d error:nil];
    }
}

// Starts the encoder of a pass group: a new encoder (with timestamps) when profiling, else the current one.
static id<MTLComputeCommandEncoder> fgGroup(id<MTLCommandBuffer> cb, id<MTLComputeCommandEncoder> ce, int group) {
    if (gProfile) fgEnsureTimer();
    if (ce != nil && gFgTimer == nil) return ce;
    if (ce != nil) [ce endEncoding];
    if (gFgTimer == nil) {
        ce = [cb computeCommandEncoder];
    } else {
        MTLComputePassDescriptor *d = [MTLComputePassDescriptor computePassDescriptor];
        d.sampleBufferAttachments[0].sampleBuffer = gFgTimer;
        d.sampleBufferAttachments[0].startOfEncoderSampleIndex = 2 * group;
        d.sampleBufferAttachments[0].endOfEncoderSampleIndex = 2 * group + 1;
        ce = [cb computeCommandEncoderWithDescriptor:d];
    }
    ce.label = @"FSR 3 frame generation";
    [ce setSamplerState:gFgLinearClamp atIndex:15];
    return ce;
}

static void fgCollectProfile(id<MTLCommandBuffer> cb) {
    if (gFgTimer == nil) return;
    [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
        NSData *data = [gFgTimer resolveCounterRange:NSMakeRange(0, 2 * FG_PROF_GROUPS)];
        if (data == nil) return;
        // Timestamp units: calibrated against the command buffer's own GPU time (first group start to last group end).
        const MTLCounterResultTimestamp *t = data.bytes;
        MTLTimestamp first = t[0].timestamp, last = t[2 * FG_PROF_GROUPS - 1].timestamp;
        if (first == MTLCounterErrorValue || last == MTLCounterErrorValue || last <= first) return;
        double nsPerTick = (done.GPUEndTime - done.GPUStartTime) * 1e9 / (double)(last - first);
        for (int g = 0; g < FG_PROF_GROUPS; g++) {
            MTLTimestamp a = t[2 * g].timestamp, b = t[2 * g + 1].timestamp;
            if (a != MTLCounterErrorValue && b != MTLCounterErrorValue && b > a) gFgProfNs[g] += (long long)((b - a) * nsPerTick);
        }
        if (++gFgProfFrames == 120) {
            char line[512];
            int n = snprintf(line, sizeof line, "[fgprof] us/frame:");
            long long total = 0;
            for (int g = 0; g < FG_PROF_GROUPS; g++) {
                total += gFgProfNs[g];
                n += snprintf(line + n, sizeof line - n, " %s %lld", kFgProfNames[g], gFgProfNs[g] / 120000);
                gFgProfNs[g] = 0;
            }
            fprintf(stderr, "%s, total %lld (%lux%lu)\n", line, total / 120000, (unsigned long)gFgDispW, (unsigned long)gFgDispH);
            gFgProfFrames = 0;
        }
    }];
}

static void encodeHandFix(id<MTLComputeCommandEncoder> ce, int stage, id<MTLTexture> real, id<MTLTexture> out, const MfxFrameGenParams *p);

// CompositeParams of the composite kernels.
static void encodeCompositeParams(id<MTLComputeCommandEncoder> ce, const MfxFrameGenParams *p) {
    struct { uint32_t menuOpen, kind; float a, b; } cp = {p->menuOpen, p->vignette, p->vignetteA, p->vignetteB};
    [ce setBytes:&cp length:sizeof cp atIndex:0];
}

static void encodeFrameGen(id<MTLCommandBuffer> cb, int stage, int slot, const MfxFrameGenParams *p, BOOL reset) {
    id<MTLTexture> world = gFgStageWorld[stage], prevWorld = gFgStageWorld[(stage + 2) % 3];
    id<MTLTexture> depth = gFgStageDepth[stage], hand = gFgStageHand[stage];
    id<MTLTexture> real = gSlotReal[slot], out = gSlotGen[slot];
    const NSUInteger dw = gFgDispW, dh = gFgDispH, rw = gFgRenW, rh = gFgRenH;
    const NSUInteger renderX = (rw + 7) / 8, renderY = (rh + 7) / 8, displayX = (dw + 7) / 8, displayY = (dh + 7) / 8;

    id<MTLComputeCommandEncoder> ce = fgGroup(cb, nil, 0);
    if (gFgNeedsClear) {
        fgClearF(ce, gFgDistortion, 0.0f);
        fgClearU(ce, gFgOfConfidence, 0);
        gFgNeedsClear = NO;
    }

    // Inputs: camera motion vectors and depth (with the hand) at render resolution.
    [ce setComputePipelineState:gFgInputsPipeline];
    [ce setTexture:depth atIndex:0];
    [ce setTexture:hand atIndex:1];
    [ce setTexture:gFgDepth atIndex:2];
    [ce setTexture:gFgMotion atIndex:3];
    [ce setBytes:p length:sizeof *p atIndex:0];
    dispatch2D(ce, gFgInputsPipeline, rw, rh);

    // ---- Optical flow (ffx_opticalflow.cpp dispatch).
    ce = fgGroup(cb, ce, 1);
    if (reset) {
        gFgOfFrameIndex = 0;
        fgClearU(ce, gFgScdTemp, 0);
        fgClearU(ce, gFgScdOut, 0);
        fgClearU(ce, gFgScdHist, 0);
        fgClearF(ce, gFgScdPrevHist, 0.0f);
        for (int i = 0; i < 2; i++) for (int level = 0; level < 7; level++) fgClearU(ce, gFgOfInput[i][level], 0);
    } else {
        gFgOfFrameIndex++;
    }
    const NSUInteger ow = gFgOfW, oh = gFgOfH;
    id<MTLTexture> ofColor = world;
    if (gFgOfColor != nil) {
        [ce setComputePipelineState:gFgHalfPipeline];
        [ce setTexture:world atIndex:0];
        [ce setTexture:gFgOfColor atIndex:1];
        [ce setBytes:&gFgOfDiv length:sizeof gFgOfDiv atIndex:0];
        dispatch2D(ce, gFgHalfPipeline, ow, oh);
        ofColor = gFgOfColor;
    }
    const int odd = (int)(gFgOfResourceFrame & 1);
    const int in = odd ? 1 : 0, prevIn = 1 - in;
    FgOfConstants oc = {{(int32_t)ow, (int32_t)oh}, 0, 7, gFgOfFrameIndex, 0, {0.0f, 1.0f}};

    [ce setTexture:ofColor atIndex:0];
    [ce setTexture:gFgOfInput[in][0] atIndex:1];
    [ce setBytes:&oc length:sizeof oc atIndex:2];
    fgDispatch(ce, FG_OF_PREPARE_LUMA, ((ow + 1) / 2 + 15) / 16, ((oh + 1) / 2 + 15) / 16, 1, 16, 16, 1);

    NSUInteger gx, gy;
    FgPyramidConstants ofSpd = spdSetup(ow, oh, 4, &gx, &gy);
    FgOfSpdConstants sc = {ofSpd.mips, ofSpd.numWorkGroups, {0, 0}, ofSpd.numWorkGroups, {0, 0, 0}};
    for (int level = 0; level < 7; level++) [ce setTexture:gFgOfInput[in][level] atIndex:level];
    [ce setBytes:&sc length:sizeof sc atIndex:8];
    fgDispatch(ce, FG_OF_LUMINANCE_PYRAMID, gx, gy, 1, 256, 1, 1);

    [ce setTexture:gFgOfInput[in][0] atIndex:0];
    [ce setTexture:gFgScdHist atIndex:1];
    [ce setBytes:&oc length:sizeof oc atIndex:2];
    fgDispatch(ce, FG_OF_SCD_HISTOGRAM, ((ow / 4) / 3 + 31) / 32, 16, 9, 32, 8, 1);

    [ce setTexture:gFgScdHist atIndex:0];
    [ce setTexture:gFgScdPrevHist atIndex:1];
    [ce setTexture:gFgScdTemp atIndex:2];
    [ce setTexture:gFgScdOut atIndex:3];
    fgDispatch(ce, FG_OF_SCD_DIVERGENCE, 9, 3, 1, 256, 1, 1);

    ce = fgGroup(cb, ce, 2);
    NSUInteger ofW[7], ofH[7];
    ofW[0] = (ow + 7) / 8;
    ofH[0] = (oh + 7) / 8;
    for (int i = 1; i < 7; i++) { ofW[i] = (ofW[i - 1] + 1) / 2; ofH[i] = (ofH[i - 1] + 1) / 2; }
    for (int level = 6; level >= 0; level--) {
        const int a = (odd != (level & 1)) ? 1 : 0, b = 1 - a;
        oc.level = (uint32_t)level;
        const NSUInteger inW = MAX(ow >> level, 1), inH = MAX(oh >> level, 1);
        // Search.
        [ce setTexture:gFgOfInput[in][level] atIndex:0];
        [ce setTexture:gFgOfInput[prevIn][level] atIndex:1];
        [ce setTexture:gFgOf[a][level] atIndex:2];
        [ce setTexture:gFgScdOut atIndex:3];
        [ce setBytes:&oc length:sizeof oc atIndex:4];
        fgDispatch(ce, FG_OF_SEARCH, ((inW + 3) / 4 * 16 + 63) / 64, (inH + 15) / 16, 1, 64, 1, 1);
        // Filter.
        [ce setTexture:gFgOf[a][level] atIndex:0];
        [ce setTexture:(level == 0 ? gFgOfVector : gFgOf[b][level]) atIndex:1];
        fgDispatch(ce, FG_OF_FILTER, (ofW[level] + 15) / 16, (ofH[level] + 3) / 4, 1, 16, 4, 1);
        // Scale to the next level.
        if (level > 0) {
            [ce setTexture:gFgOfInput[in][level] atIndex:0];
            [ce setTexture:gFgOfInput[prevIn][level] atIndex:1];
            [ce setTexture:gFgOf[b][level] atIndex:2];
            [ce setTexture:gFgOf[b][level - 1] atIndex:3];
            [ce setTexture:gFgScdOut atIndex:4];
            [ce setBytes:&oc length:sizeof oc atIndex:5];
            fgDispatch(ce, FG_OF_SCALE, (ofW[level - 1] + 3) / 4, (ofH[level - 1] + 3) / 4, 1, 4, 4, 4);
        }
    }
    gFgOfResourceFrame = (gFgOfResourceFrame + 1) % 16;

    // ---- Frame interpolation constants (ffxFrameInterpolationPrepare / Dispatch).
    FgFiConstants fc;
    memset(&fc, 0, sizeof fc);
    fc.renderSize[0] = fc.maxRenderSize[0] = (int32_t)rw;
    fc.renderSize[1] = fc.maxRenderSize[1] = (int32_t)rh;
    fc.displaySize[0] = fc.upscalerTargetSize[0] = fc.interpolationRectSize[0] = (int32_t)dw;
    fc.displaySize[1] = fc.upscalerTargetSize[1] = fc.interpolationRectSize[1] = (int32_t)dh;
    fc.displaySizeRcp[0] = 1.0f / dw;
    fc.displaySizeRcp[1] = 1.0f / dh;
    fc.cameraNear = p->nearPlane;
    fc.cameraFar = p->farPlane;
    fc.reset = reset ? 1 : 0;
    // Inverted, finite depth: view depth = [1] / (device depth - [0]).
    float fMin = fmaxf(p->nearPlane, p->farPlane), fMax = fminf(p->nearPlane, p->farPlane);
    float fQ = fMax / (fMin - fMax);
    fc.deviceToViewDepth[0] = -fQ;
    fc.deviceToViewDepth[1] = fQ * fMin;
    fc.deviceToViewDepth[2] = p->tanHalfFovX;
    fc.deviceToViewDepth[3] = p->tanHalfFovY;
    fc.deltaTime = p->frameTimeMs;
    // No HUD-less handling in the shaders: they take any pixel where the final image differs from the world image for
    // HUD and paste the real frame there, and Minecraft's vignette (drawn with the HUD, strong in dark places) made that
    // an oval of real frame around the interpolated centre. The HUD is composited afterwards, as for MetalFX.
    fc.hudLessAttachedFactor = 0;
    fc.distortionFieldSize[0] = fc.distortionFieldSize[1] = 1;
    fc.opticalFlowScale[0] = 1.0f / ow;
    fc.opticalFlowScale[1] = 1.0f / oh;
    fc.opticalFlowBlockSize = 8;
    fc.numInstances = 1;
    fc.minMaxLuminance[1] = 1.0f;
    fc.tanHalfFov = p->tanHalfFovX;
    fc.motionVectorScale[0] = p->motionScaleX / rw;
    fc.motionVectorScale[1] = p->motionScaleY / rh;

    // Prepare: dilated motion vectors and depth, reconstructed previous depth.
    ce = fgGroup(cb, ce, 3);
    fgClearU(ce, gFgReconPrevDepth, 0);
    [ce setTexture:gFgMotion atIndex:0];
    [ce setTexture:gFgDepth atIndex:1];
    [ce setTexture:gFgReconPrevDepth atIndex:2];
    [ce setTexture:gFgDilatedMotion atIndex:3];
    [ce setTexture:gFgDilatedDepth atIndex:4];
    [ce setBytes:&fc length:sizeof fc atIndex:5];
    fgDispatch(ce, FG_FI_RECONSTRUCT_AND_DILATE, renderX, renderY, 1, 8, 8, 1);

    // Setup.
    [ce setBuffer:gFgCounters offset:0 atIndex:6];
    [ce setBytes:&fc length:sizeof fc atIndex:7];
    [ce setTexture:gFgScdOut atIndex:0];
    [ce setTexture:gFgGameMvX atIndex:1];
    [ce setTexture:gFgGameMvY atIndex:2];
    [ce setTexture:gFgOfMvX atIndex:3];
    [ce setTexture:gFgOfMvY atIndex:4];
    [ce setTexture:gFgDisocclusion atIndex:5];
    fgDispatch(ce, FG_FI_SETUP, renderX, renderY, 1, 8, 8, 1);

    if (reset) {
        // No previous frame to interpolate from: only the history above is built.
        [ce endEncoding];
        return;
    }

    ce = fgGroup(cb, ce, 4);
    fgClearU(ce, gFgReconInterpDepth, 0);
    [ce setTexture:gFgDilatedMotion atIndex:0];
    [ce setTexture:gFgDilatedDepth atIndex:1];
    [ce setTexture:gFgDistortion atIndex:3];
    [ce setTexture:gFgReconInterpDepth atIndex:4];
    [ce setBytes:&fc length:sizeof fc atIndex:5];
    fgDispatch(ce, FG_FI_RECONSTRUCT_PREVIOUS_DEPTH, renderX, renderY, 1, 8, 8, 1);

    [ce setTexture:gFgDilatedMotion atIndex:0];
    [ce setTexture:gFgDilatedDepth atIndex:1];
    [ce setTexture:prevWorld atIndex:2];
    [ce setTexture:world atIndex:3];
    [ce setTexture:gFgDistortion atIndex:4];
    [ce setTexture:gFgGameMvX atIndex:5];
    [ce setTexture:gFgGameMvY atIndex:6];
    [ce setBytes:&fc length:sizeof fc atIndex:7];
    fgDispatch(ce, FG_FI_GAME_MOTION_VECTOR_FIELD, renderX, renderY, 1, 8, 8, 1);

    FgPyramidConstants pc = spdSetup(rw, rh, -1, &gx, &gy);
    [ce setBuffer:gFgCounters offset:0 atIndex:2];
    [ce setBytes:&fc length:sizeof fc atIndex:16];
    [ce setBytes:&pc length:sizeof pc atIndex:17];
    [ce setTexture:gFgGameMvX atIndex:0];
    [ce setTexture:gFgGameMvY atIndex:1];
    for (int i = 0; i < 13; i++) [ce setTexture:gFgPyramidMips[i] atIndex:3 + i];
    fgDispatch(ce, FG_FI_GAME_VECTOR_FIELD_INPAINTING_PYRAMID, gx, gy, 1, 256, 1, 1);

    ce = fgGroup(cb, ce, 5);
    [ce setTexture:gFgOfVector atIndex:0];
    [ce setTexture:gFgOfConfidence atIndex:1];
    [ce setTexture:prevWorld atIndex:3];
    [ce setTexture:world atIndex:4];
    [ce setTexture:gFgOfMvX atIndex:6];
    [ce setTexture:gFgOfMvY atIndex:7];
    [ce setBytes:&fc length:sizeof fc atIndex:8];
    NSUInteger ofX = ((NSUInteger)(ow / 8.0f) + 7) / 8, ofY = ((NSUInteger)(oh / 8.0f) + 7) / 8;
    fgDispatch(ce, FG_FI_OPTICAL_FLOW_VECTOR_FIELD, ofX, ofY, 1, 8, 8, 1);

    [ce setTexture:gFgGameMvX atIndex:0];
    [ce setTexture:gFgGameMvY atIndex:1];
    [ce setTexture:gFgReconPrevDepth atIndex:2];
    [ce setTexture:gFgDilatedDepth atIndex:3];
    [ce setTexture:gFgReconInterpDepth atIndex:4];
    [ce setTexture:gFgPyramid atIndex:5];
    [ce setTexture:gFgDistortion atIndex:6];
    [ce setTexture:gFgDisocclusion atIndex:7];
    [ce setBytes:&fc length:sizeof fc atIndex:8];
    fgDispatch(ce, FG_FI_DISOCCLUSION_MASK, renderX, renderY, 1, 8, 8, 1);

    // Interpolation.
    ce = fgGroup(cb, ce, 6);
    [ce setBuffer:gFgCounters offset:0 atIndex:8];
    [ce setBytes:&fc length:sizeof fc atIndex:10];
    [ce setTexture:gFgGameMvX atIndex:0];
    [ce setTexture:gFgGameMvY atIndex:1];
    [ce setTexture:gFgOfMvX atIndex:2];
    [ce setTexture:gFgOfMvY atIndex:3];
    [ce setTexture:prevWorld atIndex:4];
    [ce setTexture:world atIndex:5];
    [ce setTexture:gFgDisocclusion atIndex:6];
    [ce setTexture:gFgPyramid atIndex:7];
    [ce setTexture:out atIndex:9];
    fgDispatch(ce, FG_FI_INTERPOLATION, displayX, displayY, 1, 8, 8, 1);

    // Inpainting pyramid + inpainting.
    ce = fgGroup(cb, ce, 7);
    pc = spdSetup(dw, dh, -1, &gx, &gy);
    [ce setBuffer:gFgCounters offset:0 atIndex:1];
    [ce setBytes:&fc length:sizeof fc atIndex:15];
    [ce setBytes:&pc length:sizeof pc atIndex:16];
    [ce setTexture:out atIndex:0];
    for (int i = 0; i < 13; i++) [ce setTexture:gFgPyramidMips[i] atIndex:2 + i];
    fgDispatch(ce, FG_FI_INPAINTING_PYRAMID, gx, gy, 1, 256, 1, 1);

    ce = fgGroup(cb, ce, 8);
    [ce setTexture:gFgScdOut atIndex:0];
    [ce setTexture:gFgPyramid atIndex:1];
    [ce setTexture:real atIndex:2];
    [ce setTexture:world atIndex:3];
    [ce setTexture:out atIndex:7];
    [ce setBytes:&fc length:sizeof fc atIndex:8];
    fgDispatch(ce, FG_FI_INPAINTING, displayX, displayY, 1, 8, 8, 1);

    ce = fgGroup(cb, ce, 9);
    [ce setComputePipelineState:gFsrCompositePipeline];
    [ce setTexture:out atIndex:0];
    [ce setTexture:world atIndex:1];
    [ce setTexture:real atIndex:2];
    encodeCompositeParams(ce, p);
    dispatch2D(ce, gFsrCompositePipeline, out.width, out.height);
    encodeHandFix(ce, stage, real, out, p);
    [ce endEncoding];
    if (gProfile) fgCollectProfile(cb);
}

// ---- presentation slots

static BOOL presenterLive(void) {
    return atomic_load(&gPresenterState) == 2;
}

static void markReady(int slot, BOOL hasGen) {
    pthread_mutex_lock(&gSlotLock);
    if (gSlotState[slot] == SLOT_WRITING) {
        gSlotState[slot] = SLOT_READY;
        gSlotHasGen[slot] = hasGen;
        double now = CACurrentMediaTime();
        if (gLastReadyTime > 0.0) {
            double interval = fmin(fmax(now - gLastReadyTime, 0.004), 0.1);
            gRealInterval += 0.2 * (interval - gRealInterval);
        }
        gLastReadyTime = now;
    }
    pthread_cond_broadcast(&gSlotCond);
    pthread_mutex_unlock(&gSlotLock);
}

static void resetSlots(void) {
    pthread_mutex_lock(&gSlotLock);
    for (int i = 0; i < FG_SLOT_COUNT; i++) {
        if (gSlotState[i] != SLOT_WRITING) gSlotState[i] = SLOT_FREE;
    }
    gReservedSlot = -1;
    gLastReadyTime = 0.0;
    pthread_cond_broadcast(&gSlotCond);
    pthread_mutex_unlock(&gSlotLock);
}

// Reserves the slot the next frame is written to. Waits (up to timeout) while two frames are already queued for the
// presenter: that wait is what paces the game to the display. Returns the slot, or -1.
static int reserveSlot(double timeout) {
    pthread_mutex_lock(&gSlotLock);
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    long long ns = deadline.tv_nsec + (long long)(timeout * 1e9);
    deadline.tv_sec += (time_t)(ns / 1000000000LL);
    deadline.tv_nsec = (long)(ns % 1000000000LL);
    BOOL expired = NO;
    const BOOL keepsUp = gRefreshPeriod <= 0.0 || gRealInterval < 2.2 * gRefreshPeriod;
    while (gReservedSlot < 0) {
        int queued = 0, freeSlot = -1;
        for (int i = 0; i < FG_SLOT_COUNT; i++) {
            // While the game keeps up with half the refresh rate, the frame on screen (HALF: its generated frame is up, its
            // real one is next) counts too: otherwise the game runs ahead and the presenter drops generated frames to catch
            // up (uneven pacing). A slower (GPU-bound) game gets one more frame in flight to keep the GPU busy.
            if (gSlotState[i] == SLOT_WRITING || gSlotState[i] == SLOT_READY || (gSlotState[i] == SLOT_HALF && keepsUp)) queued++;
            if (gSlotState[i] == SLOT_FREE && gSlotReading[i] == 0 && freeSlot < 0) freeSlot = i;
        }
        if (freeSlot >= 0 && (queued <= 1 || expired || !presenterLive())) {
            gSlotState[freeSlot] = SLOT_RESERVED;
            gReservedSlot = freeSlot;
            break;
        }
        if (expired) {
            // The presenter isn't taking frames (window hidden?): drop the oldest one that isn't being read.
            int oldest = -1;
            for (int i = 0; i < FG_SLOT_COUNT; i++) {
                if ((gSlotState[i] == SLOT_READY || gSlotState[i] == SLOT_HALF) && gSlotReading[i] == 0
                    && (oldest < 0 || gSlotSeq[i] < gSlotSeq[oldest])) {
                    oldest = i;
                }
            }
            if (oldest < 0) break;
            gSlotState[oldest] = SLOT_FREE;
            continue;
        }
        if (pthread_cond_timedwait(&gSlotCond, &gSlotLock, &deadline) == ETIMEDOUT) expired = YES;
    }
    int slot = gReservedSlot;
    pthread_mutex_unlock(&gSlotLock);
    return slot;
}

// Blocks until the next frame has a presentation slot (call after the frame's work was submitted). Returns 1 if a
// slot is reserved, 0 if not (the next submission then shows nothing new).
int mfx_fg_reserve(void) {
    if (!presenterLive()) return 0;
    return reserveSlot(0.1) >= 0 ? 1 : 0;
}

static BOOL ensureSlot(int slot, id<MTLTexture> final) {
    id<MTLTexture> real = gSlotReal[slot];
    if (real != nil && real.width == final.width && real.height == final.height && real.pixelFormat == final.pixelFormat) return YES;
    MTLTextureUsage rw = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
    gSlotReal[slot] = privateTexture(final.pixelFormat, final.width, final.height, rw, @"Frame gen real frame");
    gSlotGen[slot] = privateTexture(final.pixelFormat, final.width, final.height, rw, @"Frame gen generated frame");
    return gSlotReal[slot] != nil && gSlotGen[slot] != nil;
}

static void blitCopy(id<MTLBlitCommandEncoder> blit, id<MTLTexture> src, id<MTLTexture> dst) {
    [blit copyFromTexture:src sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0)
               sourceSize:MTLSizeMake(src.width, src.height, 1)
                toTexture:dst destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0, 0, 0)];
}

// Pastes the first-person hand (now and in the previous frame) from the current real frame into the generated one, unless
// it has its own motion vectors.
static void encodeHandFix(id<MTLComputeCommandEncoder> ce, int stage, id<MTLTexture> real, id<MTLTexture> out, const MfxFrameGenParams *p) {
    // The F3 axis crosshair is drawn into the world image without depth and turns with the camera: like the hand, it
    // comes from the real frame (this frame's box or the previous one's).
    static float prevCross[2];
    float cross[4] = {p->crossHalfX, p->crossHalfY, prevCross[0], prevCross[1]};
    prevCross[0] = p->crossHalfX;
    prevCross[1] = p->crossHalfY;
    [ce setComputePipelineState:gFgHandMaskPipeline];
    uint32_t handMoves = p->handMotion != 0;
    [ce setBytes:cross length:sizeof cross atIndex:0];
    [ce setBytes:&handMoves length:sizeof handMoves atIndex:1];
    [ce setTexture:gFgStageHand[stage] atIndex:0];
    [ce setTexture:gFgStageHand[(stage + 2) % 3] atIndex:1];
    [ce setTexture:gFgHandMask atIndex:2];
    [ce setTexture:gFgStageDepth[stage] atIndex:3];
    [ce setTexture:gFgStageDepth[(stage + 2) % 3] atIndex:4];
    dispatch2D(ce, gFgHandMaskPipeline, gFgHandMask.width, gFgHandMask.height);
    [ce setComputePipelineState:gFgHandPipeline];
    [ce setTexture:gFgHandMask atIndex:0];
    [ce setTexture:real atIndex:1];
    [ce setTexture:out atIndex:2];
    dispatch2D(ce, gFgHandPipeline, out.width, out.height);
}

// ---- MetalFX frame interpolation backend (macOS 26+). Interpolates at the same capped size as FSR's render-resolution
// passes (display / gFgFiDiv), MetalFX spatial scales the result back up, and the HUD and hand come from the real frame.
static id gMfxInterp; // id<MTLFXFrameInterpolator>
static id<MTLFXSpatialScaler> gMfxSpatial;
static id<MTLTexture> gMfxColor[2], gMfxDepth, gMfxMotion, gMfxSmall, gMfxBig;
static NSUInteger gMfxW, gMfxH, gMfxOutW, gMfxOutH;
static MTLPixelFormat gMfxFormat;
static int gMfxCur;
static BOOL gMfxFailed;

// Returns 1 if the MetalFX frame-generation backend is available (macOS 26+ and a supported GPU).
int mfx_metalfx_frame_interpolation_supported(void) {
    if (gDevice == nil) return 0;
    if (@available(macOS 26.0, *)) return [MTLFXFrameInterpolatorDescriptor supportsDevice:gDevice] ? 1 : 0;
    return 0;
}

static BOOL ensureMfxGen(id<MTLTexture> world) API_AVAILABLE(macos(26.0)) {
    NSUInteger w = MAX(world.width / gFgFiDiv, 16), h = MAX(world.height / gFgFiDiv, 16);
    if (gMfxInterp != nil && gMfxW == w && gMfxH == h && gMfxOutW == world.width && gMfxOutH == world.height && gMfxFormat == world.pixelFormat) {
        return YES;
    }
    if (gMfxFailed && gMfxW == w && gMfxH == h && gMfxOutW == world.width && gMfxOutH == world.height && gMfxFormat == world.pixelFormat) {
        return NO;
    }
    gMfxInterp = nil;
    gMfxSpatial = nil;
    gMfxColor[0] = gMfxColor[1] = gMfxDepth = gMfxMotion = gMfxSmall = gMfxBig = nil;
    gMfxW = w; gMfxH = h; gMfxOutW = world.width; gMfxOutH = world.height; gMfxFormat = world.pixelFormat;
    gMfxFailed = YES;
    MTLFXFrameInterpolatorDescriptor *desc = [MTLFXFrameInterpolatorDescriptor new];
    desc.colorTextureFormat = world.pixelFormat;
    desc.outputTextureFormat = world.pixelFormat;
    desc.depthTextureFormat = MTLPixelFormatR32Float;
    desc.motionTextureFormat = MTLPixelFormatRG16Float;
    desc.inputWidth = w;
    desc.inputHeight = h;
    desc.outputWidth = w;
    desc.outputHeight = h;
    id<MTLFXFrameInterpolator> fi = [desc newFrameInterpolatorWithDevice:gDevice];
    if (fi == nil) {
        setError([NSString stringWithFormat:@"newFrameInterpolatorWithDevice failed (%lux%lu)", (unsigned long)w, (unsigned long)h]);
        return NO;
    }
    MTLTextureUsage rw = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
    for (int i = 0; i < 2; i++) gMfxColor[i] = privateTexture(world.pixelFormat, w, h, rw | fi.colorTextureUsage, @"MetalFX frame gen colour");
    gMfxDepth = privateTexture(MTLPixelFormatR32Float, w, h, rw | fi.depthTextureUsage, @"MetalFX frame gen depth");
    gMfxMotion = privateTexture(MTLPixelFormatRG16Float, w, h, rw | fi.motionTextureUsage, @"MetalFX frame gen motion");
    gMfxSmall = privateTexture(world.pixelFormat, w, h, MTLTextureUsageShaderRead | fi.outputTextureUsage, @"MetalFX frame gen output");
    if (gMfxColor[0] == nil || gMfxColor[1] == nil || gMfxDepth == nil || gMfxMotion == nil || gMfxSmall == nil) {
        setError(@"failed to allocate MetalFX frame generation textures");
        return NO;
    }
    if (w != world.width || h != world.height) {
        MTLFXSpatialScalerDescriptor *sd = [MTLFXSpatialScalerDescriptor new];
        sd.inputWidth = w;
        sd.inputHeight = h;
        sd.outputWidth = world.width;
        sd.outputHeight = world.height;
        sd.colorTextureFormat = world.pixelFormat;
        sd.outputTextureFormat = world.pixelFormat;
        sd.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;
        gMfxSpatial = [sd newSpatialScalerWithDevice:gDevice];
        gMfxBig = gMfxSpatial == nil ? nil
            : privateTexture(world.pixelFormat, world.width, world.height, MTLTextureUsageShaderRead | gMfxSpatial.outputTextureUsage,
                             @"MetalFX frame gen upscaled output");
        if (gMfxSpatial == nil || gMfxBig == nil) {
            setError(@"failed to create the MetalFX frame generation spatial scaler");
            return NO;
        }
    }
    gMfxInterp = fi;
    gMfxFailed = NO;
    return YES;
}

// MetalFX counterpart of encodeFrameGen. Returns 1 if slot `slot` got a generated frame, 0 if only the history was
// built (reset), -1 on error.
static int encodeMetalFxGen(id<MTLCommandBuffer> cb, int stage, int slot, const MfxFrameGenParams *p, BOOL reset) API_AVAILABLE(macos(26.0)) {
    id<MTLTexture> world = gFgStageWorld[stage], depth = gFgStageDepth[stage], hand = gFgStageHand[stage];
    id<MTLTexture> real = gSlotReal[slot], out = gSlotGen[slot];
    if (!ensureMfxGen(world)) return -1;
    gMfxCur ^= 1;
    id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
    ce.label = @"MetalFX frame gen prepare";
    [ce setComputePipelineState:gMfxPreparePipeline];
    [ce setTexture:world atIndex:0];
    [ce setTexture:depth atIndex:1];
    [ce setTexture:hand atIndex:2];
    [ce setTexture:gMfxColor[gMfxCur] atIndex:3];
    [ce setTexture:gMfxDepth atIndex:4];
    [ce setTexture:gMfxMotion atIndex:5];
    [ce setBytes:p length:sizeof *p atIndex:0];
    dispatch2D(ce, gMfxPreparePipeline, gMfxW, gMfxH);
    [ce endEncoding];
    if (reset) return 0;

    id<MTLFXFrameInterpolator> fi = gMfxInterp;
    fi.colorTexture = gMfxColor[gMfxCur];
    fi.prevColorTexture = gMfxColor[gMfxCur ^ 1];
    fi.depthTexture = gMfxDepth;
    fi.motionTexture = gMfxMotion;
    fi.outputTexture = gMfxSmall;
    // Same sign as MetalFX temporal: verified with presented-frame dumps (generated frame halfway between the real ones).
    fi.motionVectorScaleX = p->motionScaleX;
    fi.motionVectorScaleY = p->motionScaleY;
    fi.deltaTime = p->frameTimeMs * 0.001f; // seconds
    fi.nearPlane = p->nearPlane;
    fi.farPlane = p->farPlane;
    fi.fieldOfView = 2.0f * atanf(p->tanHalfFovY) * 180.0f / (float)M_PI;
    fi.aspectRatio = p->tanHalfFovY > 0.0f ? p->tanHalfFovX / p->tanHalfFovY : 1.0f;
    fi.jitterOffsetX = 0.0f;
    fi.jitterOffsetY = 0.0f;
    fi.depthReversed = YES;
    fi.shouldResetHistory = NO;
    [fi encodeToCommandBuffer:cb];
    id<MTLTexture> generated = gMfxSmall;
    if (gMfxSpatial != nil) {
        gMfxSpatial.colorTexture = gMfxSmall;
        gMfxSpatial.outputTexture = gMfxBig;
        gMfxSpatial.inputContentWidth = gMfxW;
        gMfxSpatial.inputContentHeight = gMfxH;
        [gMfxSpatial encodeToCommandBuffer:cb];
        generated = gMfxBig;
    }
    ce = [cb computeCommandEncoder];
    ce.label = @"MetalFX frame gen composite";
    [ce setComputePipelineState:gMfxCompositePipeline];
    [ce setTexture:generated atIndex:0];
    [ce setTexture:world atIndex:1];
    [ce setTexture:real atIndex:2];
    [ce setTexture:out atIndex:3];
    encodeCompositeParams(ce, p);
    dispatch2D(ce, gMfxCompositePipeline, out.width, out.height);
    encodeHandFix(ce, stage, real, out, p);
    [ce endEncoding];
    return 1;
}

// Queues this frame for the presenter. finalTex = Minecraft's finished frame; worldTex = the world image before the HUD
// (0 = no frame generation this frame: only the final frame is shown), depthTex / handTex = scene and hand depth.
// Returns 1 if a generated frame was queued too, 0 if only the real frame, -1 on error (signalValue is still signalled
// once waitValue is reached), -2 if nothing was committed (the caller must not wait).
static atomic_int gDumpRemaining;
static void dumpDepth(id<MTLCommandBuffer> cb, id<MTLTexture> t, NSString *name);
static int gDumpDepthCount;

int mfx_fg_submit(uintptr_t worldTex, uintptr_t finalTex, uintptr_t depthTex, uintptr_t handTex,
                  uintptr_t sharedEvent, uint64_t waitValue, uint64_t signalValue, uintptr_t paramsPtr) {
    @autoreleasepool {
        if (gQueue == nil || sharedEvent == 0 || finalTex == 0) {
            setError(@"bridge not initialised");
            return -2;
        }
        if (gFgQueue == nil) {
            gFgQueue = [gDevice newCommandQueue];
            gFgQueue.label = @"FSR 3 frame generation";
            gFgCopyQueue = [gDevice newCommandQueue];
            gFgCopyQueue.label = @"Frame generation copies";
            gFgEvent = [gDevice newEvent];
            gFgCopyEvent = [gDevice newEvent];
            if (gFgQueue == nil || gFgCopyQueue == nil || gFgEvent == nil || gFgCopyEvent == nil) {
                setError(@"failed to create frame generation queues");
                gFgQueue = nil;
                return -2;
            }
        }
        id<MTLSharedEvent> event = (__bridge id<MTLSharedEvent>)(void *)sharedEvent;
        id<MTLTexture> final = (__bridge id<MTLTexture>)(void *)finalTex;
        id<MTLTexture> world = worldTex != 0 ? (__bridge id<MTLTexture>)(void *)worldTex : nil;
        id<MTLTexture> depth = depthTex != 0 ? (__bridge id<MTLTexture>)(void *)depthTex : nil;
        id<MTLTexture> hand = handTex != 0 ? (__bridge id<MTLTexture>)(void *)handTex : nil;
        const MfxFrameGenParams *params = paramsPtr != 0 ? (const MfxFrameGenParams *)(void *)paramsPtr : NULL;

        // No slot (presenter not live, or not taking frames): nothing new is shown this frame.
        int slot = presenterLive() ? reserveSlot(0.1) : -1;
        int result = 0;
        if (slot >= 0 && !ensureSlot(slot, final)) {
            setError(@"failed to allocate frame generation slot textures");
            slot = -1;
            result = -1;
        }
        BOOL generate = slot >= 0 && world != nil && depth != nil && hand != nil && params != NULL
            && world.width == final.width && world.height == final.height;
        if (generate && !ensureFgResources(world, depth, hand)) {
            generate = NO;
            result = -1;
        }
        BOOL debugCopy = generate && params->debugView != 0;

        id<MTLCommandBuffer> copy = [(debugCopy ? gFgQueue : gFgCopyQueue) commandBuffer];
        copy.label = @"Frame generation copies";
        [copy encodeWaitForEvent:event value:waitValue];
        if (atomic_load(&gDumpRemaining) > 0 && gDumpDepthCount < 3 && depth != nil && hand != nil) {
            dumpDepth(copy, depth, [NSString stringWithFormat:@"scene%d", gDumpDepthCount]);
            dumpDepth(copy, hand, [NSString stringWithFormat:@"hand%d", gDumpDepthCount]);
            gDumpDepthCount++;
        }
        uint64_t frame = 0;
        int stage = 0;
        if (generate) {
            frame = ++gFgFrame;
            stage = (int)(frame % 3);
            // Staging set `stage` was last read by frame - 3 (as current) and frame - 2 (as previous).
            if (frame > 2) [copy encodeWaitForEvent:gFgEvent value:frame - 2];
        }
        if (slot >= 0) {
            id<MTLBlitCommandEncoder> blit = [copy blitCommandEncoder];
            blitCopy(blit, final, gSlotReal[slot]);
            if (generate) {
                blitCopy(blit, world, gFgStageWorld[stage]);
                blitCopy(blit, depth, gFgStageDepth[stage]);
                blitCopy(blit, hand, gFgStageHand[stage]);
            }
            [blit endEncoding];
            pthread_mutex_lock(&gSlotLock);
            gSlotState[slot] = SLOT_WRITING;
            gSlotSeq[slot] = ++gSlotSeqCounter;
            gReservedSlot = -1;
            pthread_mutex_unlock(&gSlotLock);
        }

        const uint32_t backend = generate && params->backend == 1 && mfx_metalfx_frame_interpolation_supported() ? 1 : 0;
        BOOL reset = generate && (params->reset || !gFgHistory || backend != gFgLastBackend);
        if (generate) {
            id<MTLCommandBuffer> cb = copy;
            if (!debugCopy) {
                [copy encodeSignalEvent:gFgCopyEvent value:frame];
                [copy encodeSignalEvent:event value:signalValue];
                addCompletionHandler(copy);
                [copy commit];
                cb = [gFgQueue commandBuffer];
                cb.label = @"FSR 3 frame generation";
                [cb encodeWaitForEvent:gFgCopyEvent value:frame];
            }
            int generated = reset ? 0 : 1;
            static BOOL preDumped;
            if (atomic_load(&gDumpRemaining) > 0 && !preDumped) {
                preDumped = YES;
                dumpDepth(cb, gFgStageDepth[stage], @"preScene");
                dumpDepth(cb, gFgStageHand[stage], @"preHand");
            }
            if (backend == 1) {
                if (@available(macOS 26.0, *)) generated = encodeMetalFxGen(cb, stage, slot, params, reset);
            } else {
                encodeFrameGen(cb, stage, slot, params, reset);
            }
            gFgLastBackend = backend;
            static BOOL maskDumped;
            if (atomic_load(&gDumpRemaining) > 0 && !maskDumped && gFgHandMask != nil) {
                maskDumped = YES;
                NSLog(@"[MetalFX] frame dump: stage %d formats scene %lu hand %lu", stage, (unsigned long)gFgStageDepth[stage].pixelFormat, (unsigned long)gFgStageHand[stage].pixelFormat);
                dumpDepth(cb, gFgStageDepth[stage], @"stageScene");
                dumpDepth(cb, gFgStageHand[stage], @"stageHand");
                dumpDepth(cb, gFgStageDepth[(stage + 2) % 3], @"stagePrevScene");
                dumpDepth(cb, gFgStageHand[(stage + 2) % 3], @"stagePrevHand");
                NSUInteger mw = gFgHandMask.width, mh = gFgHandMask.height;
                id<MTLBuffer> mb = [gDevice newBufferWithLength:mw * mh options:MTLResourceStorageModeShared];
                id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
                [blit copyFromTexture:gFgHandMask sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(mw, mh, 1)
                             toBuffer:mb destinationOffset:0 destinationBytesPerRow:mw destinationBytesPerImage:mw * mh];
                [blit endEncoding];
                NSString *mp = [NSString stringWithFormat:@"%s/handmask_%lux%lu.u8", getenv("MFX_FG_DUMP_DIR"), (unsigned long)mw, (unsigned long)mh];
                [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
                    [[NSData dataWithBytes:mb.contents length:mw * mh] writeToFile:mp atomically:NO];
                }];
            }
            if (debugCopy) {
                if (generated == 1) {
                    id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
                    blitCopy(blit, gSlotGen[slot], final);
                    [blit endEncoding];
                }
                [cb encodeSignalEvent:event value:signalValue];
            }
            [cb encodeSignalEvent:gFgEvent value:frame];
            const int readySlot = slot;
            const BOOL hasGen = generated == 1;
            [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
                markReady(readySlot, hasGen && done.error == nil);
            }];
            if (gProfile) addStageTimer(cb, 3);
            addCompletionHandler(cb);
            [cb commit];
            gFgHistory = generated >= 0;
            result = generated;
        } else {
            gFgHistory = NO;
            [copy encodeSignalEvent:event value:signalValue];
            if (slot >= 0) {
                const int readySlot = slot;
                [copy addCompletedHandler:^(id<MTLCommandBuffer> done) { markReady(readySlot, NO); }];
            }
            addCompletionHandler(copy);
            [copy commit];
        }
        return result;
    }
}

// Generated frames the presenter showed since the last call.
int mfx_fg_take_generated(void) {
    return atomic_exchange(&gGeneratedShown, 0);
}

// ---- debugging: saves the next frames the presenter shows as PNGs in $MFX_FG_DUMP_DIR (exactly what reaches the screen,
// in order: present_<index>_<gen|real>_<ms since the first>.png).
static int gDumpIndex;
static double gDumpStart;

void mfx_fg_dump(int frames) {
    if (getenv("MFX_FG_DUMP_DIR") == NULL) return;
    NSLog(@"[MetalFX] frame dump: %d frames to %s", frames, getenv("MFX_FG_DUMP_DIR"));
    gDumpIndex = 0;
    atomic_store(&gDumpRemaining, frames);
}

static void writeDumpPng(id<MTLBuffer> buffer, NSUInteger w, NSUInteger h, BOOL bgra, NSString *path) {
    // Minecraft's frames are stored bottom row first.
    NSMutableData *rows = [NSMutableData dataWithLength:w * h * 4];
    const uint8_t *src = buffer.contents;
    uint8_t *dst = rows.mutableBytes;
    for (NSUInteger y = 0; y < h; y++) memcpy(dst + y * w * 4, src + (h - 1 - y) * w * 4, w * 4);
    CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
    CGBitmapInfo info = bgra ? (CGBitmapInfo)kCGImageAlphaNoneSkipFirst | kCGBitmapByteOrder32Little
                             : (CGBitmapInfo)kCGImageAlphaNoneSkipLast | kCGBitmapByteOrder32Big;
    CGDataProviderRef provider = CGDataProviderCreateWithCFData((__bridge CFDataRef)rows);
    CGImageRef image = CGImageCreate(w, h, 8, 32, w * 4, cs, info, provider, NULL, false, kCGRenderingIntentDefault);
    CGImageDestinationRef out = CGImageDestinationCreateWithURL((__bridge CFURLRef)[NSURL fileURLWithPath:path], CFSTR("public.png"), 1, NULL);
    if (out != NULL && image != NULL) {
        CGImageDestinationAddImage(out, image, NULL);
        if (!CGImageDestinationFinalize(out)) NSLog(@"[MetalFX] frame dump: writing %@ failed", path);
    } else {
        NSLog(@"[MetalFX] frame dump: can't create %@", path);
    }
    if (out != NULL) CFRelease(out);
    if (image != NULL) CGImageRelease(image);
    CGDataProviderRelease(provider);
    CGColorSpaceRelease(cs);
}

// Saves a depth texture as a grey PNG (min..max stretched) and logs its value range.
static void dumpDepth(id<MTLCommandBuffer> cb, id<MTLTexture> t, NSString *name) {
    MTLPixelFormat f = t.pixelFormat;
    if (f != MTLPixelFormatDepth32Float && f != MTLPixelFormatDepth32Float_Stencil8) {
        NSLog(@"[MetalFX] frame dump: %@ has format %lu", name, (unsigned long)f);
        return;
    }
    NSUInteger w = t.width, h = t.height;
    id<MTLBuffer> buffer = [gDevice newBufferWithLength:w * h * 4 options:MTLResourceStorageModeShared];
    id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
    [blit copyFromTexture:t sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(w, h, 1)
                 toBuffer:buffer destinationOffset:0 destinationBytesPerRow:w * 4 destinationBytesPerImage:w * h * 4
                  options:f == MTLPixelFormatDepth32Float_Stencil8 ? MTLBlitOptionDepthFromDepthStencil : MTLBlitOptionNone];
    [blit endEncoding];
    NSString *path = [NSString stringWithFormat:@"%s/depth_%@.png", getenv("MFX_FG_DUMP_DIR"), name];
    [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
        const float *v = buffer.contents;
        [[NSData dataWithBytesNoCopy:(void *)v length:w * h * 4 freeWhenDone:NO]
            writeToFile:[path stringByReplacingOccurrencesOfString:@".png" withString:@".f32"] atomically:NO];
        float lo = INFINITY, hi = -INFINITY;
        for (NSUInteger i = 0; i < w * h; i++) { lo = fminf(lo, v[i]); hi = fmaxf(hi, v[i]); }
        NSLog(@"[MetalFX] frame dump: %@ %lux%lu depth range %f .. %f (centre %f, bottom-right quarter %f)", name,
              (unsigned long)w, (unsigned long)h, lo, hi, v[(h / 2) * w + w / 2], v[(h / 8) * w + w * 3 / 4]);
        id<MTLBuffer> rgba = [gDevice newBufferWithLength:w * h * 4 options:MTLResourceStorageModeShared];
        uint8_t *o = rgba.contents;
        for (NSUInteger i = 0; i < w * h; i++) {
            uint8_t g = (uint8_t)(255.0f * (hi > lo ? (v[i] - lo) / (hi - lo) : 0.0f));
            o[i * 4] = o[i * 4 + 1] = o[i * 4 + 2] = g; o[i * 4 + 3] = 255;
        }
        writeDumpPng(rgba, w, h, NO, path);
    }];
}

static void dumpPresented(id<MTLCommandBuffer> cb, id<MTLTexture> texture, BOOL generated, double targetTime) {
    if (atomic_load(&gDumpRemaining) <= 0) return;
    MTLPixelFormat f = texture.pixelFormat;
    BOOL bgra = f == MTLPixelFormatBGRA8Unorm || f == MTLPixelFormatBGRA8Unorm_sRGB;
    if (!bgra && f != MTLPixelFormatRGBA8Unorm && f != MTLPixelFormatRGBA8Unorm_sRGB) {
        NSLog(@"[MetalFX] frame dump: unsupported format %lu", (unsigned long)f);
        atomic_store(&gDumpRemaining, 0);
        return;
    }
    atomic_fetch_sub(&gDumpRemaining, 1);
    NSLog(@"[MetalFX] frame dump: frame %d %s", gDumpIndex, generated ? "gen" : "real");
    int index = gDumpIndex++;
    if (index == 0) gDumpStart = targetTime;
    NSUInteger w = texture.width, h = texture.height;
    id<MTLBuffer> buffer = [gDevice newBufferWithLength:w * h * 4 options:MTLResourceStorageModeShared];
    if (buffer == nil) return;
    id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
    [blit copyFromTexture:texture sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(w, h, 1)
                 toBuffer:buffer destinationOffset:0 destinationBytesPerRow:w * 4 destinationBytesPerImage:w * h * 4];
    [blit endEncoding];
    NSString *path = [NSString stringWithFormat:@"%s/present_%02d_%s_%.1fms.png", getenv("MFX_FG_DUMP_DIR"), index,
                                                generated ? "gen" : "real", (targetTime - gDumpStart) * 1000.0];
    [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{ writeDumpPng(buffer, w, h, bgra, path); });
    }];
}

// ---- presenter

static BOOL ensurePresentPipeline(MTLPixelFormat format) {
    if (gPresentPipeline != nil && gPresentFormat == format) return YES;
    if (gFgLibrary == nil && !ensureFgPipelines()) return NO;
    MTLRenderPipelineDescriptor *rp = [MTLRenderPipelineDescriptor new];
    rp.label = @"Frame gen present";
    rp.vertexFunction = [gFgLibrary newFunctionWithName:@"fg_present_vs"];
    rp.fragmentFunction = [gFgLibrary newFunctionWithName:@"fg_present_fs"];
    rp.colorAttachments[0].pixelFormat = format;
    NSError *error = nil;
    gPresentPipeline = [gDevice newRenderPipelineStateWithDescriptor:rp error:&error];
    if (gPresentPipeline == nil) {
        setError([NSString stringWithFormat:@"present pipeline failed: %@", error.localizedDescription]);
        return NO;
    }
    gPresentFormat = format;
    return YES;
}

// One display refresh: decides what (if anything) to show and presents it.
static void presentTick(id<CAMetalDrawable> drawable, double targetTime) {
    if (!presenterLive() || drawable == nil) return;
    pthread_mutex_lock(&gSlotLock);
    int half = -1, newest = -1, ready = 0;
    for (int i = 0; i < FG_SLOT_COUNT; i++) {
        if (gSlotState[i] == SLOT_HALF) half = i;
        if (gSlotState[i] == SLOT_READY) {
            ready++;
            if (newest < 0 || gSlotSeq[i] > gSlotSeq[newest]) newest = i;
        }
    }
    int show = -1;
    BOOL showGen = NO;
    if (half >= 0) {
        // The real frame goes up half a frame time after the generated one, or right away if the next frame is waiting.
        double due = gSlotGenShownAt[half] + 0.5 * gRealInterval - 0.5 * gRefreshPeriod;
        if (ready > 0 || targetTime >= due) {
            show = half;
            gSlotState[half] = SLOT_FREE;
        }
    } else if (newest >= 0) {
        for (int i = 0; i < FG_SLOT_COUNT; i++) {
            if (gSlotState[i] == SLOT_READY && i != newest) gSlotState[i] = SLOT_FREE; // behind: skip to the newest
        }
        show = newest;
        if (gSlotHasGen[newest]) {
            showGen = YES;
            gSlotState[newest] = SLOT_HALF;
            gSlotGenShownAt[newest] = targetTime;
        } else {
            gSlotState[newest] = SLOT_FREE;
        }
    }
    id<MTLTexture> texture = nil;
    if (show >= 0) {
        gSlotReading[show]++;
        texture = showGen ? gSlotGen[show] : gSlotReal[show];
    }
    pthread_cond_broadcast(&gSlotCond);
    pthread_mutex_unlock(&gSlotLock);
    if (texture == nil) return;

    CAMetalLayer *layer = gOverlayLayer;
    BOOL drawn = NO;
    id<MTLCommandBuffer> cb = [gPresentQueue commandBuffer];
    cb.label = @"Frame generation present";
    if (cb != nil && ensurePresentPipeline(drawable.texture.pixelFormat)) {
        MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
        pass.colorAttachments[0].texture = drawable.texture;
        pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
        pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:pass];
        [re setRenderPipelineState:gPresentPipeline];
        [re setFragmentTexture:texture atIndex:0];
        [re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [re endEncoding];
        dumpPresented(cb, texture, showGen, targetTime);
        [cb presentDrawable:drawable];
        drawn = YES;
    }
    const int readSlot = show;
    [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
        pthread_mutex_lock(&gSlotLock);
        gSlotReading[readSlot]--;
        pthread_cond_broadcast(&gSlotCond);
        pthread_mutex_unlock(&gSlotLock);
    }];
    [cb commit];
    if (drawn && showGen) atomic_fetch_add(&gGeneratedShown, 1);
    if (layer != nil && (layer.drawableSize.width != texture.width || layer.drawableSize.height != texture.height)) {
        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        layer.drawableSize = CGSizeMake(texture.width, texture.height);
        [CATransaction commit];
    }
}

API_AVAILABLE(macos(14.0))
@interface MfxPresenter : NSObject <CAMetalDisplayLinkDelegate>
@end

@implementation MfxPresenter
- (void)metalDisplayLink:(CAMetalDisplayLink *)link needsUpdate:(CAMetalDisplayLinkUpdate *)update {
    @autoreleasepool {
        presentTick(update.drawable, update.targetPresentationTimestamp);
    }
}
@end

// Covers the game's view; never takes mouse events.
@interface MfxOverlayView : NSView
@end

@implementation MfxOverlayView
- (NSView *)hitTest:(NSPoint)point { return nil; }
- (BOOL)acceptsFirstResponder { return NO; }
- (BOOL)isOpaque { return YES; }
@end

static CAMetalLayer *findMetalLayer(NSView *view) {
    if (view == gOverlay) return nil;
    if ([view.layer isKindOfClass:[CAMetalLayer class]]) return (CAMetalLayer *)view.layer;
    for (NSView *sub in view.subviews) {
        CAMetalLayer *layer = findMetalLayer(sub);
        if (layer != nil) return layer;
    }
    return nil;
}

static void attachOnMain(void) API_AVAILABLE(macos(14.0)) {
    if (atomic_load(&gPresenterState) != 1) return;
    [gOverlay removeFromSuperview];
    gOverlay = nil;
    NSWindow *window = nil;
    CAMetalLayer *gameLayer = nil;
    NSMutableArray<NSWindow *> *candidates = [NSMutableArray array];
    if (NSApp.mainWindow != nil) [candidates addObject:NSApp.mainWindow];
    if (NSApp.keyWindow != nil) [candidates addObject:NSApp.keyWindow];
    [candidates addObjectsFromArray:NSApp.orderedWindows];
    for (NSWindow *candidate in candidates) {
        CAMetalLayer *layer = candidate.contentView != nil ? findMetalLayer(candidate.contentView) : nil;
        if (layer != nil) {
            window = candidate;
            gameLayer = layer;
            break;
        }
    }
    if (window == nil) {
        gAttachFailures++;
        setError(@"frame generation: no window with a Metal layer found");
        atomic_store(&gPresenterState, 0);
        return;
    }
    NSView *content = window.contentView;
    MfxOverlayView *view = [[MfxOverlayView alloc] initWithFrame:content.bounds];
    CAMetalLayer *layer = [CAMetalLayer layer];
    layer.device = gDevice;
    layer.pixelFormat = gameLayer.pixelFormat;
    layer.colorspace = gameLayer.colorspace;
    layer.wantsExtendedDynamicRangeContent = gameLayer.wantsExtendedDynamicRangeContent;
    layer.framebufferOnly = YES;
    layer.opaque = YES;
    layer.contentsScale = gameLayer.contentsScale;
    layer.drawableSize = gameLayer.drawableSize;
    layer.maximumDrawableCount = 3;
    layer.displaySyncEnabled = YES;
    view.layer = layer;
    view.wantsLayer = YES;
    view.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    [content addSubview:view positioned:NSWindowAbove relativeTo:nil];
    gOverlay = view;
    gOverlayLayer = layer;
    NSInteger fps = MAX(window.screen.maximumFramesPerSecond, 30);
    gRefreshPeriod = 1.0 / (double)fps;
    if (gPresentQueue == nil) {
        gPresentQueue = [gDevice newCommandQueue];
        gPresentQueue.label = @"Frame generation presenter";
    }
    CAMetalDisplayLink *link = [[CAMetalDisplayLink alloc] initWithMetalLayer:layer];
    MfxPresenter *delegate = [MfxPresenter new];
    link.delegate = delegate;
    link.preferredFrameRateRange = CAFrameRateRangeMake((float)fps, (float)fps, (float)fps);
    gDisplayLink = link;
    gPresenterDelegate = delegate;
    NSThread *thread = [[NSThread alloc] initWithBlock:^{
        [link addToRunLoop:NSRunLoop.currentRunLoop forMode:NSDefaultRunLoopMode];
        while (atomic_load(&gPresenterState) != 0) {
            @autoreleasepool {
                [NSRunLoop.currentRunLoop runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.1]];
            }
        }
        [link invalidate];
    }];
    thread.name = @"MetalFX frame generation presenter";
    thread.qualityOfService = NSQualityOfServiceUserInteractive;
    atomic_store(&gPresenterState, 2);
    [thread start];
}

static void detachOnMain(void) {
    if (atomic_load(&gPresenterState) != 0) return;
    [gOverlay removeFromSuperview];
    gOverlay = nil;
    gOverlayLayer = nil;
    gDisplayLink = nil;
    gPresenterDelegate = nil;
}

static void runOnMain(dispatch_block_t block) {
    if (NSThread.isMainThread) block();
    else dispatch_async(dispatch_get_main_queue(), block);
}

// Shows the presenter's layer over the game. Returns 1 once it is live (Minecraft must then stop presenting), 0 while
// it is being set up, -1 if it can't be (see mfx_last_error).
int mfx_fg_attach(void) {
    if (gDevice == nil) return -1;
    if (@available(macOS 14.0, *)) {
        int state = atomic_load(&gPresenterState);
        if (state == 2) return 1;
        if (state == 1) return 0;
        if (gAttachFailures >= 3 || !ensureFgPipelines()) return -1;
        atomic_store(&gPresenterState, 1);
        resetSlots();
        runOnMain(^{ attachOnMain(); });
        return atomic_load(&gPresenterState) == 2 ? 1 : 0;
    }
    return -1;
}

// Removes the presenter (Minecraft presents again).
void mfx_fg_detach(void) {
    if (atomic_exchange(&gPresenterState, 0) == 0) return;
    resetSlots();
    gFgHistory = NO;
    runOnMain(^{ detachOnMain(); });
}

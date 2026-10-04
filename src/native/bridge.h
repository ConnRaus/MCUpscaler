// Native side of the DLSS mod: NVIDIA NGX (DLSS Super Resolution, DLSS Frame Generation) and AMD FidelityFX (FSR 3.1
// upscaling and frame generation) on Minecraft's own Vulkan device. Everything is recorded into the command buffer
// Minecraft is currently recording, so the work is ordered with the frame without any extra synchronisation. Minecraft
// keeps all images in VK_IMAGE_LAYOUT_GENERAL and separates its commands with global memory barriers; the bridge does
// the same.
//
// Java (DlssNative.java) calls the exported functions through the FFM API; handles travel as 64-bit integers.
//
// bridge.cpp            device, Vulkan functions, queue lock, image/buffer helpers, compute passes, init and shutdown
// super_resolution.cpp  motion vectors, depth merges, DLSS Super Resolution / FSR upscaling, GPU timing
// frame_gen.cpp         frame generation (DLSS-G or FSR) and its present thread
// fsr.cpp               AMD FidelityFX API: FSR upscaling and FSR frame generation

#pragma once

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <windows.h>

#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "nvsdk_ngx_vk.h"
#include "nvsdk_ngx_helpers.h"
#include "nvsdk_ngx_helpers_vk.h"
#include "nvsdk_ngx_defs_dlssg.h"
#include "nvsdk_ngx_params_dlssg.h"
#include "nvsdk_ngx_helpers_dlssg_vk.h"

#include "ffx_api/ffx_api.h"
#include "ffx_api/ffx_upscale.h"
#include "ffx_api/ffx_framegeneration.h"
#include "ffx_api/vk/ffx_api_vk.h"

#define EXPORT extern "C" __declspec(dllexport)

// ------------------------------------------------------------------------------------------------ bridge.cpp

// Sets the message dlss_last_error returns.
void setError(const char *fmt, ...);

extern VkPhysicalDevice gPhysical;
extern VkDevice gDevice;
extern PFN_vkGetDeviceProcAddr gGdpa;
extern bool gLogging; // troubleshooting logs requested (NGX logs, FidelityFX messages)

#define VK_FUNCS(X) \
    X(vkCreateShaderModule) X(vkDestroyShaderModule) X(vkCreateComputePipelines) X(vkDestroyPipeline) \
    X(vkCreatePipelineLayout) X(vkDestroyPipelineLayout) X(vkCreateDescriptorSetLayout) X(vkDestroyDescriptorSetLayout) \
    X(vkCreateSampler) X(vkDestroySampler) X(vkCreateImage) X(vkDestroyImage) X(vkCreateImageView) X(vkDestroyImageView) \
    X(vkCreateBuffer) X(vkDestroyBuffer) X(vkGetImageMemoryRequirements) X(vkGetBufferMemoryRequirements) \
    X(vkAllocateMemory) X(vkFreeMemory) X(vkBindImageMemory) X(vkBindBufferMemory) X(vkDeviceWaitIdle) \
    X(vkCmdPipelineBarrier) X(vkCmdBindPipeline) X(vkCmdPushConstants) X(vkCmdDispatch) X(vkCmdCopyImage) \
    X(vkCmdBlitImage) X(vkCmdCopyBufferToImage) X(vkCmdClearColorImage) \
    X(vkCreateQueryPool) X(vkDestroyQueryPool) X(vkCmdResetQueryPool) X(vkCmdWriteTimestamp) X(vkGetQueryPoolResults) X(vkCmdUpdateBuffer) \
    X(vkAcquireNextImageKHR) X(vkQueuePresentKHR) X(vkQueueSubmit) X(vkQueueWaitIdle) X(vkCreateFence) X(vkDestroyFence) \
    X(vkWaitForFences) X(vkResetFences) X(vkCreateSemaphore) X(vkDestroySemaphore) X(vkWaitSemaphores) X(vkSignalSemaphore) X(vkGetSemaphoreCounterValue) \
    X(vkCreateCommandPool) X(vkDestroyCommandPool) X(vkAllocateCommandBuffers) X(vkBeginCommandBuffer) X(vkEndCommandBuffer) \
    X(vkResetCommandBuffer) X(vkCmdCopyImageToBuffer) X(vkMapMemory) X(vkUnmapMemory)

#define DECLARE(name) extern PFN_##name p_##name;
VK_FUNCS(DECLARE)
#undef DECLARE
extern PFN_vkGetPhysicalDeviceProperties p_vkGetPhysicalDeviceProperties;

// Vulkan queues need external synchronisation. With Frame Generation, the present thread uses Minecraft's queue
// alongside the render thread: every queue operation (Minecraft's own submits through mixins too) holds this lock.
extern std::recursive_mutex gQueueMutex;
void deviceWaitIdle();

void globalBarrier(VkCommandBuffer cb);
int32_t findMemoryType(uint32_t bits, VkMemoryPropertyFlags flags);
VkImageAspectFlags aspectOf(VkFormat format);

// An image owned by the bridge, always kept in VK_IMAGE_LAYOUT_GENERAL.
struct OwnedImage {
    VkImage image = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    uint32_t width = 0, height = 0;
    bool fresh = false; // needs its UNDEFINED -> GENERAL transition
};

void destroyImage(OwnedImage &img);
// (Re)creates img unless it already has this format and size. families: when given (two distinct queue families), the
// image is shared concurrently between them.
bool ensureImage(OwnedImage &img, VkFormat format, uint32_t width, uint32_t height, VkImageUsageFlags usage, const char *what,
                 const uint32_t *families = nullptr);
void transitionFresh(VkCommandBuffer cb, OwnedImage &img);

struct OwnedBuffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize size = 0;
};

void destroyBuffer(OwnedBuffer &buf);
bool ensureBuffer(OwnedBuffer &buf, VkDeviceSize size);

NVSDK_NGX_Resource_VK resourceOf(uint64_t image, uint64_t view, VkFormat format, uint32_t w, uint32_t h, bool readWrite);

// The compute passes (GLSL in Shaders.java), in the order Java hands them over.
enum PassId { PASS_MOTION = 0,PASS_PACK_MERGE, PASS_DISTANT_MERGE, PASS_POST, PASS_COUNT };

// One binding of a pass: an image view or a buffer, as the pass's layout says.
struct Resource {
    VkImageView view;
    VkBuffer buffer;
};

// Records pass id over a w x h grid (16 x 16 groups) with the given bindings and push constants.
void dispatch(VkCommandBuffer cb, PassId id, const Resource *resources, const void *push, uint32_t pushBytes, uint32_t w, uint32_t h);

extern bool gNgxReady;
extern bool gDlssAvailable;
extern bool gFrameGenAvailable;
extern bool gPassesReady;
extern NVSDK_NGX_Parameter *gParams;

// A Minecraft texture: its VkImage, a view of it (Minecraft's own), VkFormat and size. Must match DlssNative.java.
struct Tex {
    uint64_t image;
    uint64_t view;
    uint32_t format;
    uint32_t width;
    uint32_t height;
    uint32_t pad;
};
static_assert(sizeof(Tex) == 32, "Tex layout");

// ------------------------------------------------------------------------------------------------ super_resolution.cpp

// This frame's motion vectors, depth and world image, which Frame Generation reuses. Written by dlss_upscale, with or
// without DLSS Super Resolution.
extern OwnedImage gMotion;       // RG16F motion vectors at the render resolution
extern OwnedImage gDlssDepth;    // R32F depth at the render resolution
extern OwnedImage *gLastOutput;  // the world at the output resolution, without the HUD (Frame Generation's hudless image)

void initTiming();
void srShutdown();

// ------------------------------------------------------------------------------------------------ frame_gen.cpp

// Camera of the frame, written by Java after the motion vectors were made. Must match DlssNative.java (FG_CAMERA_*).
struct FgCamera {
    float viewToClip[16];     // 0   unjittered projection
    float clipToView[16];     // 64
    float clipToPrevClip[16]; // 128 current clip -> previous frame's clip (camera movement and rotation)
    float prevClipToClip[16]; // 192
    float pos[4], up[4], right[4], fwd[4]; // 256 world space
    float nearZ, farZ, fov, aspect;        // 320 fov vertical, radians
    float jitterX, jitterY;                // 336 render pixels
    uint32_t reset;                        // 344
    uint32_t pad;                          // 348
};
static_assert(sizeof(FgCamera) == 352, "FgCamera layout");

void fgShutdown();

// ------------------------------------------------------------------------------------------------ fsr.cpp

// amd_fidelityfx_vk.dll is loaded (any Vulkan GPU).
extern bool gFfxReady;
// Loads amd_fidelityfx_vk.dll from dir. Returns gFfxReady.
bool fsrLoad(const wchar_t *dir);

// One FSR upscale. Inputs at the render resolution: color (Minecraft's world image), depth (near / view depth), motion
// vectors (render pixels), reactive mask (R8, may be null); output: a storage image at the output resolution.
struct FsrUpscale {
    VkImage color;
    VkFormat colorFormat;
    uint32_t renderW, renderH;
    const OwnedImage *depth, *motion, *reactive, *output;
    float jitterX, jitterY; // render pixels, as for DLSS
    float frameTimeMs;
    float sharpness;        // 0 = no sharpening
    float fovY, nearZ;
    bool reset;
};
// Records FSR upscaling into cb. Returns 1, 0 (failed this frame) or -1 (FSR unusable); see setError.
int fsrUpscale(VkCommandBuffer cb, const FsrUpscale &u);

// Records FSR frame generation into cb: the frame between the previous backbuffer and this one into output. backbuffer:
// the final frame (with the HUD); hudless: the same without the HUD (may be null); depth and motion vectors are this
// frame's (gDlssDepth, gMotion). frameId must grow by one per frame (any other step resets FSR's history).
// Returns true if output holds a generated frame.
bool fsrFrameGen(VkCommandBuffer cb, uint64_t frameId, bool reset, float frameTimeMs, const OwnedImage &backbuffer,
                 const OwnedImage *hudless, const OwnedImage &output, const FgCamera &camera);

// FSR's version for the F3 screen ("3.1.4"), or "" before the first context exists.
const char *fsrVersion();
// Releases the FidelityFX contexts (the device must be idle) and, with unload, the library.
void fsrShutdown(bool unload);

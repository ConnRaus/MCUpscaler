// Core of the native bridge: errors, Minecraft's Vulkan device and its functions, the queue lock, image and buffer
// helpers, the compute passes, NGX initialisation and shutdown. See bridge.h.

#include "bridge.h"

// Any GUID-like string identifies a "custom engine" project to NGX.
static const char *const kProjectId = "6a4f1d0e-3c8b-4e9a-b1f2-7d5c9e8a0b41";

static char gError[1024];

void setError(const char *fmt, ...) {
    va_list args;
    va_start(args, fmt);
    vsnprintf(gError, sizeof(gError), fmt, args);
    va_end(args);
}

EXPORT int dlss_last_error(char *buf, int len) {
    if (len <= 0) return 0;
    strncpy_s(buf, (size_t)len, gError, _TRUNCATE);
    return (int)strlen(buf);
}

// ------------------------------------------------------------------------------------------------ Vulkan functions

static VkInstance gInstance;
VkPhysicalDevice gPhysical;
VkDevice gDevice;
static PFN_vkGetInstanceProcAddr gGipa;
PFN_vkGetDeviceProcAddr gGdpa;
bool gLogging;

#define DEFINE(name) PFN_##name p_##name;
VK_FUNCS(DEFINE)
#undef DEFINE
static PFN_vkCmdPushDescriptorSetKHR p_vkCmdPushDescriptorSetKHR;
static PFN_vkGetPhysicalDeviceMemoryProperties p_vkGetPhysicalDeviceMemoryProperties;
static PFN_vkGetPhysicalDeviceFormatProperties p_vkGetPhysicalDeviceFormatProperties;
PFN_vkGetPhysicalDeviceProperties p_vkGetPhysicalDeviceProperties;

static bool loadVulkan() {
#define LOAD(name) p_##name = (PFN_##name)gGdpa(gDevice, #name); if (!p_##name) { setError("missing Vulkan function " #name); return false; }
    VK_FUNCS(LOAD)
#undef LOAD
    p_vkCmdPushDescriptorSetKHR = (PFN_vkCmdPushDescriptorSetKHR)gGdpa(gDevice, "vkCmdPushDescriptorSetKHR");
    p_vkGetPhysicalDeviceMemoryProperties = (PFN_vkGetPhysicalDeviceMemoryProperties)gGipa(gInstance, "vkGetPhysicalDeviceMemoryProperties");
    p_vkGetPhysicalDeviceFormatProperties = (PFN_vkGetPhysicalDeviceFormatProperties)gGipa(gInstance, "vkGetPhysicalDeviceFormatProperties");
    p_vkGetPhysicalDeviceProperties = (PFN_vkGetPhysicalDeviceProperties)gGipa(gInstance, "vkGetPhysicalDeviceProperties");
    if (!p_vkCmdPushDescriptorSetKHR || !p_vkGetPhysicalDeviceMemoryProperties || !p_vkGetPhysicalDeviceFormatProperties) {
        setError("missing VK_KHR_push_descriptor or physical device functions");
        return false;
    }
    return true;
}

// ------------------------------------------------------------------------------------------------ queue lock

// Vulkan queues need external synchronisation. With Frame Generation, this file's present thread uses Minecraft's queue
// alongside the render thread: every queue operation (Minecraft's own submits through mixins too) holds this lock.
std::recursive_mutex gQueueMutex;

EXPORT void dlss_queue_lock(void) {
    gQueueMutex.lock();
}

EXPORT void dlss_queue_unlock(void) {
    gQueueMutex.unlock();
}

void deviceWaitIdle() {
    std::lock_guard<std::recursive_mutex> lock(gQueueMutex);
    p_vkDeviceWaitIdle(gDevice);
}

// ------------------------------------------------------------------------------------------------ small helpers

void globalBarrier(VkCommandBuffer cb) {
    VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    barrier.srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    p_vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 1, &barrier, 0, nullptr, 0, nullptr);
}

int32_t findMemoryType(uint32_t bits, VkMemoryPropertyFlags flags) {
    VkPhysicalDeviceMemoryProperties props;
    p_vkGetPhysicalDeviceMemoryProperties(gPhysical, &props);
    for (uint32_t i = 0; i < props.memoryTypeCount; i++) {
        if ((bits & (1u << i)) && (props.memoryTypes[i].propertyFlags & flags) == flags) return (int32_t)i;
    }
    return -1;
}

VkImageAspectFlags aspectOf(VkFormat format) {
    switch (format) {
        case VK_FORMAT_D16_UNORM:
        case VK_FORMAT_D32_SFLOAT:
        case VK_FORMAT_X8_D24_UNORM_PACK32:
            return VK_IMAGE_ASPECT_DEPTH_BIT;
        case VK_FORMAT_D24_UNORM_S8_UINT:
        case VK_FORMAT_D32_SFLOAT_S8_UINT:
        case VK_FORMAT_D16_UNORM_S8_UINT:
            return VK_IMAGE_ASPECT_DEPTH_BIT; // views for sampling use the depth aspect only
        default:
            return VK_IMAGE_ASPECT_COLOR_BIT;
    }
}

void destroyImage(OwnedImage &img) {
    if (img.view) p_vkDestroyImageView(gDevice, img.view, nullptr);
    if (img.image) p_vkDestroyImage(gDevice, img.image, nullptr);
    if (img.memory) p_vkFreeMemory(gDevice, img.memory, nullptr);
    img = OwnedImage{};
}

// families: when given (two distinct queue families), the image is shared concurrently between them.
bool ensureImage(OwnedImage &img, VkFormat format, uint32_t width, uint32_t height, VkImageUsageFlags usage, const char *what,
                        const uint32_t *families) {
    if (img.image && img.format == format && img.width == width && img.height == height) return true;
    if (img.image) {
        deviceWaitIdle(); // rare (resize): the old image may still be used by frames in flight
        destroyImage(img);
    }
    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = format;
    ici.extent = {width, height, 1};
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = usage;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (families) {
        ici.sharingMode = VK_SHARING_MODE_CONCURRENT;
        ici.queueFamilyIndexCount = 2;
        ici.pQueueFamilyIndices = families;
    }
    if (p_vkCreateImage(gDevice, &ici, nullptr, &img.image) != VK_SUCCESS) {
        setError("vkCreateImage failed for %s (%ux%u format %d)", what, width, height, format);
        return false;
    }
    VkMemoryRequirements req;
    p_vkGetImageMemoryRequirements(gDevice, img.image, &req);
    int32_t type = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = (uint32_t)type;
    if (type < 0 || p_vkAllocateMemory(gDevice, &mai, nullptr, &img.memory) != VK_SUCCESS
        || p_vkBindImageMemory(gDevice, img.image, img.memory, 0) != VK_SUCCESS) {
        setError("could not allocate memory for %s", what);
        destroyImage(img);
        return false;
    }
    VkImageViewCreateInfo vci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vci.image = img.image;
    vci.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vci.format = format;
    vci.subresourceRange = {aspectOf(format), 0, 1, 0, 1};
    if (p_vkCreateImageView(gDevice, &vci, nullptr, &img.view) != VK_SUCCESS) {
        setError("vkCreateImageView failed for %s", what);
        destroyImage(img);
        return false;
    }
    img.format = format;
    img.width = width;
    img.height = height;
    img.fresh = true;
    return true;
}

void transitionFresh(VkCommandBuffer cb, OwnedImage &img) {
    if (!img.fresh) return;
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = 0;
    b.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
    b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img.image;
    b.subresourceRange = {aspectOf(img.format), 0, 1, 0, 1};
    p_vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    img.fresh = false;
}

void destroyBuffer(OwnedBuffer &buf) {
    if (buf.buffer) p_vkDestroyBuffer(gDevice, buf.buffer, nullptr);
    if (buf.memory) p_vkFreeMemory(gDevice, buf.memory, nullptr);
    buf = OwnedBuffer{};
}


bool ensureBuffer(OwnedBuffer &buf, VkDeviceSize size) {
    if (buf.buffer && buf.size >= size) return true;
    if (buf.buffer) {
        deviceWaitIdle();
        p_vkDestroyBuffer(gDevice, buf.buffer, nullptr);
        p_vkFreeMemory(gDevice, buf.memory, nullptr);
        buf = OwnedBuffer{};
    }
    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = size;
    bci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    if (p_vkCreateBuffer(gDevice, &bci, nullptr, &buf.buffer) != VK_SUCCESS) {
        setError("vkCreateBuffer failed");
        return false;
    }
    VkMemoryRequirements req;
    p_vkGetBufferMemoryRequirements(gDevice, buf.buffer, &req);
    int32_t type = findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = (uint32_t)type;
    if (type < 0 || p_vkAllocateMemory(gDevice, &mai, nullptr, &buf.memory) != VK_SUCCESS
        || p_vkBindBufferMemory(gDevice, buf.buffer, buf.memory, 0) != VK_SUCCESS) {
        setError("could not allocate buffer memory");
        return false;
    }
    buf.size = size;
    return true;
}

// ------------------------------------------------------------------------------------------------ compute passes

// Binding kinds for the push-descriptor layouts.
enum Binding : uint32_t { SAMPLED = 0, STORAGE_IMAGE = 1, STORAGE_BUFFER = 2 };

struct ComputePass {
    VkDescriptorSetLayout setLayout = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    std::vector<Binding> bindings;
};

static ComputePass gPasses[PASS_COUNT];
static VkSampler gPointSampler;
static constexpr uint32_t kPushConstantBytes = 224;

static bool createPass(ComputePass &pass, const uint32_t *spirv, size_t bytes, std::vector<Binding> bindings, const char *name) {
    pass.bindings = bindings;
    std::vector<VkDescriptorSetLayoutBinding> b(bindings.size());
    for (size_t i = 0; i < bindings.size(); i++) {
        b[i].binding = (uint32_t)i;
        b[i].descriptorCount = 1;
        b[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        b[i].descriptorType = bindings[i] == SAMPLED ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
            : bindings[i] == STORAGE_IMAGE ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    }
    VkDescriptorSetLayoutCreateInfo dsl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dsl.flags = VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;
    dsl.bindingCount = (uint32_t)b.size();
    dsl.pBindings = b.data();
    if (p_vkCreateDescriptorSetLayout(gDevice, &dsl, nullptr, &pass.setLayout) != VK_SUCCESS) {
        setError("descriptor set layout for %s", name);
        return false;
    }
    VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, kPushConstantBytes};
    VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pl.setLayoutCount = 1;
    pl.pSetLayouts = &pass.setLayout;
    pl.pushConstantRangeCount = 1;
    pl.pPushConstantRanges = &range;
    if (p_vkCreatePipelineLayout(gDevice, &pl, nullptr, &pass.layout) != VK_SUCCESS) {
        setError("pipeline layout for %s", name);
        return false;
    }
    VkShaderModuleCreateInfo smi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smi.codeSize = bytes;
    smi.pCode = spirv;
    VkShaderModule module;
    if (p_vkCreateShaderModule(gDevice, &smi, nullptr, &module) != VK_SUCCESS) {
        setError("shader module for %s", name);
        return false;
    }
    VkComputePipelineCreateInfo cpi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cpi.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    cpi.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpi.stage.module = module;
    cpi.stage.pName = "main";
    cpi.layout = pass.layout;
    VkResult r = p_vkCreateComputePipelines(gDevice, VK_NULL_HANDLE, 1, &cpi, nullptr, &pass.pipeline);
    p_vkDestroyShaderModule(gDevice, module, nullptr);
    if (r != VK_SUCCESS) {
        setError("compute pipeline for %s (%d)", name, r);
        return false;
    }
    return true;
}

void dispatch(VkCommandBuffer cb, PassId id, const Resource *resources, const void *push, uint32_t pushBytes, uint32_t w, uint32_t h) {
    ComputePass &pass = gPasses[id];
    VkDescriptorImageInfo images[8];
    VkDescriptorBufferInfo buffers[8];
    VkWriteDescriptorSet writes[8];
    for (size_t i = 0; i < pass.bindings.size(); i++) {
        writes[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        writes[i].dstBinding = (uint32_t)i;
        writes[i].descriptorCount = 1;
        if (pass.bindings[i] == STORAGE_BUFFER) {
            buffers[i] = {resources[i].buffer, 0, VK_WHOLE_SIZE};
            writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[i].pBufferInfo = &buffers[i];
        } else {
            images[i] = {pass.bindings[i] == SAMPLED ? gPointSampler : VK_NULL_HANDLE, resources[i].view, VK_IMAGE_LAYOUT_GENERAL};
            writes[i].descriptorType = pass.bindings[i] == SAMPLED ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            writes[i].pImageInfo = &images[i];
        }
    }
    p_vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pass.pipeline);
    p_vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pass.layout, 0, (uint32_t)pass.bindings.size(), writes);
    uint8_t constants[kPushConstantBytes] = {};
    memcpy(constants, push, pushBytes < kPushConstantBytes ? pushBytes : kPushConstantBytes);
    p_vkCmdPushConstants(cb, pass.layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, kPushConstantBytes, constants);
    p_vkCmdDispatch(cb, (w + 15) / 16, (h + 15) / 16, 1);
}

// ------------------------------------------------------------------------------------------------ init / shutdown

bool gNgxReady;
bool gDlssAvailable;
bool gFrameGenAvailable;
static uint32_t gFrameGenMaxMultiFrame;
static NVSDK_NGX_Parameter *gCaps;
NVSDK_NGX_Parameter *gParams;
bool gPassesReady;

static bool gInitialized;

// Returns a bitmask: 1 = NGX initialised, 2 = DLSS Super Resolution available, 4 = DLSS Frame Generation available,
// 8 = AMD FidelityFX (FSR) available; or -1 on failure (see dlss_last_error). Without NGX (not an NVIDIA RTX GPU) the
// bridge still works for FSR; dlss_last_error then says why NGX is missing.
EXPORT int dlss_init(uint64_t instance, uint64_t physicalDevice, uint64_t device, uint64_t gipa, uint64_t gdpa,
                     const wchar_t *featureDir, const wchar_t *dataDir, int logging) {
    gInstance = (VkInstance)instance;
    gPhysical = (VkPhysicalDevice)physicalDevice;
    gDevice = (VkDevice)device;
    gGipa = (PFN_vkGetInstanceProcAddr)gipa;
    gGdpa = (PFN_vkGetDeviceProcAddr)gdpa;
    gLogging = logging != 0;
    if (!loadVulkan()) return -1;
    gInitialized = true;
    initTiming();
    int ffx = fsrLoad(featureDir) ? 8 : 0;

    const wchar_t *paths[] = {featureDir};
    NVSDK_NGX_FeatureCommonInfo common{};
    common.PathListInfo.Path = paths;
    common.PathListInfo.Length = 1;
    common.LoggingInfo.MinimumLoggingLevel = logging ? NVSDK_NGX_LOGGING_LEVEL_ON : NVSDK_NGX_LOGGING_LEVEL_OFF;
    NVSDK_NGX_Result r = NVSDK_NGX_VULKAN_Init_with_ProjectID(kProjectId, NVSDK_NGX_ENGINE_TYPE_CUSTOM, "26.3", dataDir,
        gInstance, gPhysical, gDevice, gGipa, gGdpa, &common);
    if (NVSDK_NGX_FAILED(r)) {
        setError("NVSDK_NGX_VULKAN_Init failed: 0x%08x (not an NVIDIA RTX GPU, or the driver is too old)", r);
        return ffx;
    }
    gNgxReady = true;
    r = NVSDK_NGX_VULKAN_GetCapabilityParameters(&gCaps);
    if (NVSDK_NGX_FAILED(r)) {
        setError("NVSDK_NGX_VULKAN_GetCapabilityParameters failed: 0x%08x", r);
        return 1 | ffx;
    }
    NVSDK_NGX_VULKAN_AllocateParameters(&gParams);
    int ss = 0, ssNeedsDriver = 0, fg = 0;
    NVSDK_NGX_Parameter_GetI(gCaps, NVSDK_NGX_Parameter_SuperSampling_Available, &ss);
    NVSDK_NGX_Parameter_GetI(gCaps, NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver, &ssNeedsDriver);
    NVSDK_NGX_Parameter_GetI(gCaps, NVSDK_NGX_Parameter_FrameGeneration_Available, &fg);
    NVSDK_NGX_Parameter_GetUI(gCaps, NVSDK_NGX_DLSSG_Parameter_MultiFrameCountMax, &gFrameGenMaxMultiFrame);
    gDlssAvailable = ss != 0;
    gFrameGenAvailable = fg != 0;
    if (!gDlssAvailable) {
        int result = 0;
        NVSDK_NGX_Parameter_GetI(gCaps, NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult, &result);
        setError("DLSS Super Resolution unavailable (init result 0x%08x%s)", result, ssNeedsDriver ? ", needs a newer driver" : "");
    }
    return 1 | (gDlssAvailable ? 2 : 0) | (gFrameGenAvailable ? 4 : 0) | ffx;
}

// FSR's version ("3.1.4"), known once FSR upscaling has run; returns its length.
EXPORT int dlss_fsr_version(char *buf, int len) {
    if (len <= 0) return 0;
    strncpy_s(buf, (size_t)len, fsrVersion(), _TRUNCATE);
    return (int)strlen(buf);
}

// DLSS's render size range for an output size and quality mode: out = {optimal w, h, max w, h, min w, h}. 1 = ok.
EXPORT int dlss_optimal_settings(int quality, int outW, int outH, uint32_t *out) {
    if (!gNgxReady || !gCaps) return 0;
    float sharpness = 0.0f;
    NVSDK_NGX_Result r = NGX_DLSS_GET_OPTIMAL_SETTINGS(gCaps, (unsigned)outW, (unsigned)outH, (NVSDK_NGX_PerfQuality_Value)quality,
        &out[0], &out[1], &out[2], &out[3], &out[4], &out[5], &sharpness);
    return NVSDK_NGX_SUCCEED(r) ? 1 : 0;
}

EXPORT int dlss_frame_gen_max_multi_frame(void) {
    return (int)gFrameGenMaxMultiFrame;
}

// SPIR-V for the compute passes (compiled from GLSL by Java with shaderc), in PassId order.
EXPORT int dlss_load_shaders(const uint32_t *motion, int motionBytes, const uint32_t *packMerge, int packMergeBytes, const uint32_t *distantMerge, int distantMergeBytes,
                             const uint32_t *post, int postBytes) {
    if (gPassesReady) return 1;
    VkSamplerCreateInfo sci{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    sci.magFilter = sci.minFilter = VK_FILTER_NEAREST;
    sci.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    sci.addressModeU = sci.addressModeV = sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    sci.maxLod = 0.0f;
    if (p_vkCreateSampler(gDevice, &sci, nullptr, &gPointSampler) != VK_SUCCESS) {
        setError("vkCreateSampler failed");
        return 0;
    }
    bool ok = createPass(gPasses[PASS_MOTION], motion, (size_t)motionBytes, {SAMPLED, SAMPLED, STORAGE_IMAGE, STORAGE_IMAGE, STORAGE_BUFFER, STORAGE_IMAGE}, "motion vectors")
        && createPass(gPasses[PASS_PACK_MERGE], packMerge, (size_t)packMergeBytes, {SAMPLED, SAMPLED, SAMPLED, SAMPLED, SAMPLED, STORAGE_BUFFER}, "pack depth merge")
        && createPass(gPasses[PASS_DISTANT_MERGE], distantMerge, (size_t)distantMergeBytes, {SAMPLED, SAMPLED, STORAGE_BUFFER}, "distant depth merge")
        && createPass(gPasses[PASS_POST], post, (size_t)postBytes, {SAMPLED, STORAGE_IMAGE}, "vignette");
    gPassesReady = ok;
    return ok ? 1 : 0;
}

NVSDK_NGX_Resource_VK resourceOf(uint64_t image, uint64_t view, VkFormat format, uint32_t w, uint32_t h, bool readWrite) {
    VkImageSubresourceRange range{aspectOf(format), 0, 1, 0, 1};
    return NVSDK_NGX_Create_ImageView_Resource_VK((VkImageView)view, (VkImage)image, range, format, w, h, readWrite);
}

// ------------------------------------------------------------------------------------------------ shutdown

EXPORT void dlss_shutdown(void) {
    if (!gInitialized) return;
    gInitialized = false;
    fgShutdown();
    deviceWaitIdle();
    fsrShutdown(true);
    srShutdown();
    for (ComputePass &pass : gPasses) {
        if (pass.pipeline) p_vkDestroyPipeline(gDevice, pass.pipeline, nullptr);
        if (pass.layout) p_vkDestroyPipelineLayout(gDevice, pass.layout, nullptr);
        if (pass.setLayout) p_vkDestroyDescriptorSetLayout(gDevice, pass.setLayout, nullptr);
        pass = ComputePass{};
    }
    if (gPointSampler) p_vkDestroySampler(gDevice, gPointSampler, nullptr);
    gPointSampler = VK_NULL_HANDLE;
    gPassesReady = false;
    if (!gNgxReady) return;
    if (gParams) NVSDK_NGX_VULKAN_DestroyParameters(gParams);
    if (gCaps) NVSDK_NGX_VULKAN_DestroyParameters(gCaps);
    gParams = gCaps = nullptr;
    NVSDK_NGX_VULKAN_Shutdown1(gDevice);
    gNgxReady = false;
}


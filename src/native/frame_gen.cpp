// DLSS Frame Generation. Minecraft renders a frame and, instead of blitting it to the swapchain, this file copies it into
// a ring slot (dlss_fg_record) and DLSS-G generates the frame halfway between the previous one and it. A present thread
// owns the swapchain meanwhile: it shows the generated frame, waits half a frame and shows the real one, so the screen
// gets two evenly spaced frames for each one rendered. The render thread may be one frame ahead of the present thread.

#include "bridge.h"

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

// What Java has to add to Minecraft's submission around the recorded commands. Must match DlssNative.java.
struct FgSubmit {
    uint64_t readySemaphore;     // signal readyValue after the commands
    uint64_t readyValue;
    uint64_t presentedSemaphore; // wait for presentedValue before the commands (0 = no wait)
    uint64_t presentedValue;
    uint32_t interpolated;       // a generated frame comes with this one
    uint32_t pad;
};
static_assert(sizeof(FgSubmit) == 40, "FgSubmit layout");

static constexpr uint32_t kFgSlots = 3;
static constexpr uint32_t kFgOps = 4; // present operations in flight on the present thread

static NVSDK_NGX_Parameter *gFgParams;
static NVSDK_NGX_Handle *gFg;
static uint32_t gFgW, gFgH, gFgRenderW, gFgRenderH;
static VkFormat gFgFormat;
static OwnedImage gFgReal[kFgSlots], gFgInterp[kFgSlots];
// The frames as presented: flipped to the screen's orientation and in the swapchain's format, so the present thread only
// copies them. That copy can then run on a queue without graphics (Minecraft's unused compute queue), which doesn't wait
// behind the next frame's rendering the way the graphics queue does.
static OwnedImage gFgOutReal[kFgSlots], gFgOutInterp[kFgSlots];
static uint32_t gFgGraphicsFamily;
static VkFormat gFgSwapFormat;
static bool gFgInterpValid[kFgSlots];
static FgCamera gFgCamera;
static bool gFgCameraValid;    // the motion vectors and depth are this frame's
static bool gFgHistory;        // the previous frame went through DLSS-G
static uint64_t gFgFrameId;
static VkSemaphore gFgReady;     // timeline: render thread's copy + DLSS-G of a frame done
static VkSemaphore gFgPresented; // timeline: present thread done reading a frame's slot
static uint64_t gFgLastQueued;

// Present thread state.
static VkQueue gFgQueue;
static uint32_t gFgQueueFamily;
static VkSwapchainKHR gFgSwapchain;
static std::vector<VkImage> gFgSwapImages;
static std::vector<VkSemaphore> gFgRenderDone; // per swapchain image
static uint32_t gFgSwapW, gFgSwapH;
static VkCommandPool gFgPool;
static VkCommandBuffer gFgCmd[kFgOps];
static VkFence gFgFence[kFgOps];
static VkSemaphore gFgAcquire[kFgOps];
static uint32_t gFgOp;

struct FgJob {
    uint64_t id;
    uint32_t slot;
    bool interp;
    uint64_t presentId; // NVIDIA Reflex: VkPresentIdKHR of the real frame's present (0 = none)
};
static std::mutex gFgMutex;
static std::condition_variable gFgCv;
static std::thread gFgThread;
static bool gFgRunning, gFgStop, gFgPendingSet, gFgBusy;
static FgJob gFgPending;
static volatile int gFgSurfaceState; // 1 suboptimal, 2 out of date: Minecraft should configure the surface again
static double gFgInterval = 1.0 / 60.0; // smoothed time between real frames, seconds
static double gFgPresentMs[2];           // last present thread costs, for the F3 screen

static double nowSeconds() {
    static LARGE_INTEGER freq = [] { LARGE_INTEGER f; QueryPerformanceFrequency(&f); return f; }();
    LARGE_INTEGER t;
    QueryPerformanceCounter(&t);
    return (double)t.QuadPart / (double)freq.QuadPart;
}

// Sleeps until a nowSeconds() time: a high-resolution waitable timer for most of it, then spins the last half millisecond.
static void sleepUntil(double target) {
    static thread_local HANDLE timer = CreateWaitableTimerExW(nullptr, nullptr, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
    double remaining = target - nowSeconds();
    if (remaining > 0.0008 && timer) {
        LARGE_INTEGER due;
        due.QuadPart = -(LONGLONG)((remaining - 0.0005) * 1e7);
        if (SetWaitableTimerEx(timer, &due, 0, nullptr, nullptr, nullptr, 0)) WaitForSingleObject(timer, 100);
    }
    while (nowSeconds() < target) YieldProcessor();
}

static VkSemaphore createSemaphore(bool timeline) {
    VkSemaphoreTypeCreateInfo type{VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO};
    type.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    type.initialValue = 0;
    VkSemaphoreCreateInfo sci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    if (timeline) sci.pNext = &type;
    VkSemaphore sem = VK_NULL_HANDLE;
    p_vkCreateSemaphore(gDevice, &sci, nullptr, &sem);
    return sem;
}

// Submits an empty batch that signals gFgPresented = value (when a frame's slot was not shown, e.g. no swapchain image).
static void signalPresented(uint64_t value) {
    VkTimelineSemaphoreSubmitInfo ts{VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO};
    ts.signalSemaphoreValueCount = 1;
    ts.pSignalSemaphoreValues = &value;
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.pNext = &ts;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &gFgPresented;
    std::lock_guard<std::recursive_mutex> lock(gQueueMutex);
    p_vkQueueSubmit(gFgQueue, 1, &si, VK_NULL_HANDLE);
}

// Shows src (a ring image in GENERAL layout, Minecraft's orientation) on the swapchain; signals gFgPresented = signalValue
// once read (0 = no signal). Returns false if the swapchain needs to be configured again.
static bool presentImage(const OwnedImage &src, uint64_t readyValue, uint64_t signalValue, uint64_t presentId = 0) {
    uint32_t n = gFgOp++ % kFgOps;
    p_vkWaitForFences(gDevice, 1, &gFgFence[n], VK_TRUE, UINT64_MAX);
    uint32_t index = 0;
    VkResult r = p_vkAcquireNextImageKHR(gDevice, gFgSwapchain, 1000000000ull, gFgAcquire[n], VK_NULL_HANDLE, &index);
    if (r == VK_SUBOPTIMAL_KHR) {
        gFgSurfaceState = 1;
    } else if (r != VK_SUCCESS) {
        gFgSurfaceState = 2;
        if (signalValue) signalPresented(signalValue);
        return false;
    }
    p_vkResetFences(gDevice, 1, &gFgFence[n]);
    VkCommandBuffer cb = gFgCmd[n];
    p_vkResetCommandBuffer(cb, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    p_vkBeginCommandBuffer(cb, &bi);
    VkImage swap = gFgSwapImages[index];
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = 0;
    b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = swap;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    p_vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    // src is already flipped and in the swapchain's format (see gFgOutReal). Minecraft draws from the bottom-left, so with
    // a size mismatch the bottom rows line up.
    uint32_t w = src.width < gFgSwapW ? src.width : gFgSwapW;
    uint32_t h = src.height < gFgSwapH ? src.height : gFgSwapH;
    VkImageCopy copy{};
    copy.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.srcOffset = {0, (int32_t)(src.height - h), 0};
    copy.dstOffset = {0, (int32_t)(gFgSwapH - h), 0};
    copy.extent = {w, h, 1};
    p_vkCmdCopyImage(cb, src.image, VK_IMAGE_LAYOUT_GENERAL, swap, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    b.dstAccessMask = 0;
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    p_vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    p_vkEndCommandBuffer(cb);

    VkSemaphore signals[2] = {gFgRenderDone[index], gFgPresented};
    uint64_t signalValues[2] = {0, signalValue};
    VkTimelineSemaphoreSubmitInfo ts{VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO};
    VkSemaphore waits[2] = {gFgAcquire[n], gFgReady};
    uint64_t waitValues[2] = {0, readyValue};
    VkPipelineStageFlags waitStages[2] = {VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT};
    ts.waitSemaphoreValueCount = 2;
    ts.pWaitSemaphoreValues = waitValues;
    ts.signalSemaphoreValueCount = signalValue ? 2 : 1;
    ts.pSignalSemaphoreValues = signalValues;
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.pNext = &ts;
    si.waitSemaphoreCount = 2;
    si.pWaitSemaphores = waits;
    si.pWaitDstStageMask = waitStages;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cb;
    si.signalSemaphoreCount = signalValue ? 2 : 1;
    si.pSignalSemaphores = signals;
    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &gFgRenderDone[index];
    pi.swapchainCount = 1;
    pi.pSwapchains = &gFgSwapchain;
    pi.pImageIndices = &index;
    VkPresentIdKHR pid{VK_STRUCTURE_TYPE_PRESENT_ID_KHR};
    if (presentId) {
        pid.swapchainCount = 1;
        pid.pPresentIds = &presentId;
        pi.pNext = &pid;
    }
    {
        std::lock_guard<std::recursive_mutex> lock(gQueueMutex);
        p_vkQueueSubmit(gFgQueue, 1, &si, gFgFence[n]);
        r = p_vkQueuePresentKHR(gFgQueue, &pi);
    }
    if (r == VK_SUBOPTIMAL_KHR) {
        gFgSurfaceState = 1;
    } else if (r != VK_SUCCESS) {
        gFgSurfaceState = 2;
        return false;
    }
    return true;
}

static void fgThreadMain() {
    double lastReady = 0.0;
    double lastReal = 0.0;
    while (true) {
        FgJob job;
        {
            std::unique_lock<std::mutex> lock(gFgMutex);
            gFgBusy = false;
            gFgCv.notify_all();
            gFgCv.wait(lock, [] { return gFgStop || gFgPendingSet; });
            if (gFgStop) break;
            job = gFgPending;
            gFgPendingSet = false;
            gFgBusy = true;
            gFgCv.notify_all();
        }
        // Wait for the GPU to finish the frame (and its generated frame).
        VkSemaphoreWaitInfo wi{VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO};
        wi.semaphoreCount = 1;
        wi.pSemaphores = &gFgReady;
        wi.pValues = &job.id;
        VkResult r;
        do {
            r = p_vkWaitSemaphores(gDevice, &wi, 100000000ull);
        } while (r == VK_TIMEOUT && !gFgStop);
        if (r != VK_SUCCESS) {
            signalPresented(job.id);
            continue;
        }
        double ready = nowSeconds();
        double interval = ready - lastReady;
        lastReady = ready;
        if (interval > 0.0 && interval < 0.25) gFgInterval += (interval - gFgInterval) * 0.15;

        if (gFgSurfaceState == 2) {
            signalPresented(job.id);
            continue;
        }
        double t0 = nowSeconds();
        if (job.interp) {
            // Even spacing: the generated frame goes half a frame after the last real one, but waits at most a quarter
            // frame (waiting longer would slow down rendering, which the interval would then follow: a downward spiral).
            // With vsync too: on a variable refresh rate (G-Sync) display, FIFO shows each present as soon as it comes, so
            // two back-to-back presents would be shown back to back and then nothing for most of a frame.
            if (lastReal > 0.0) {
                double target = lastReal + gFgInterval * 0.5;
                double latest = nowSeconds() + gFgInterval * 0.25;
                sleepUntil(target < latest ? target : latest);
            }
            t0 = nowSeconds();
            presentImage(gFgOutInterp[job.slot], job.id, 0);
            sleepUntil(t0 + gFgInterval * 0.5);
        }
        double t1 = nowSeconds();
        presentImage(gFgOutReal[job.slot], job.id, job.id, job.presentId);
        lastReal = nowSeconds();
        gFgPresentMs[0] = (t1 - t0) * 1000.0;
        gFgPresentMs[1] = (nowSeconds() - t1) * 1000.0;
    }
    std::lock_guard<std::mutex> lock(gFgMutex);
    gFgBusy = false;
    gFgCv.notify_all();
}

// Waits until the present thread has shown everything queued.
static void fgDrain() {
    std::unique_lock<std::mutex> lock(gFgMutex);
    gFgCv.wait(lock, [] { return !gFgRunning || (!gFgPendingSet && !gFgBusy); });
}

static void fgDestroySwapchainObjects() {
    for (VkSemaphore s : gFgRenderDone) p_vkDestroySemaphore(gDevice, s, nullptr);
    gFgRenderDone.clear();
    gFgSwapImages.clear();
    gFgSwapchain = VK_NULL_HANDLE;
}

// Stops the present thread (before Minecraft destroys the swapchain, or when Frame Generation is switched off).
EXPORT void dlss_fg_stop(void) {
    if (!gFgRunning) return;
    {
        std::lock_guard<std::mutex> lock(gFgMutex);
        gFgStop = true;
        gFgCv.notify_all();
    }
    gFgThread.join();
    {
        std::lock_guard<std::mutex> lock(gFgMutex);
        gFgRunning = false;
        if (gFgPendingSet) gFgPendingSet = false;
    }
    {
        std::lock_guard<std::recursive_mutex> lock(gQueueMutex);
        p_vkQueueWaitIdle(gFgQueue);
    }
    // Frames recorded but never shown: release their slots for the render thread's waits.
    uint64_t value = 0;
    p_vkGetSemaphoreCounterValue(gDevice, gFgPresented, &value);
    if (value < gFgFrameId) {
        VkSemaphoreSignalInfo si{VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO};
        si.semaphore = gFgPresented;
        si.value = gFgFrameId;
        p_vkSignalSemaphore(gDevice, &si);
    }
    for (uint32_t i = 0; i < kFgOps; i++) p_vkWaitForFences(gDevice, 1, &gFgFence[i], VK_TRUE, UINT64_MAX);
    fgDestroySwapchainObjects();
    gFgHistory = false;
}

// Starts (or restarts) the present thread on Minecraft's swapchain. images: count VkImage handles.
// queue: where the present thread presents (ideally not the graphics queue, see gFgOutReal); graphicsFamily: Minecraft's
// rendering queue family; swapFormat: the swapchain's VkFormat.
EXPORT int dlss_fg_start(uint64_t queue, int queueFamily, int graphicsFamily, uint64_t swapchain, int swapFormat, const uint64_t *images,
                         int count, int width, int height) {
    if (!gNgxReady || !gFrameGenAvailable) {
        setError("DLSS Frame Generation unavailable");
        return 0;
    }
    dlss_fg_stop();
    if (!gFgReady) {
        gFgReady = createSemaphore(true);
        gFgPresented = createSemaphore(true);
        NVSDK_NGX_VULKAN_AllocateParameters(&gFgParams);
        if (!gFgReady || !gFgPresented || !gFgParams) {
            setError("could not create the frame generation semaphores");
            return 0;
        }
    }
    gFgQueue = (VkQueue)queue;
    if (gFgGraphicsFamily != (uint32_t)graphicsFamily || gFgQueueFamily != (uint32_t)queueFamily) {
        // The presented images' sharing depends on the families.
        deviceWaitIdle();
        for (uint32_t i = 0; i < kFgSlots; i++) {
            destroyImage(gFgOutReal[i]);
            destroyImage(gFgOutInterp[i]);
        }
    }
    gFgGraphicsFamily = (uint32_t)graphicsFamily;
    gFgSwapFormat = (VkFormat)swapFormat;
    if (!gFgPool || gFgQueueFamily != (uint32_t)queueFamily) {
        if (gFgPool) {
            p_vkDestroyCommandPool(gDevice, gFgPool, nullptr);
            gFgPool = VK_NULL_HANDLE;
        }
        gFgQueueFamily = (uint32_t)queueFamily;
        VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        pci.queueFamilyIndex = gFgQueueFamily;
        if (p_vkCreateCommandPool(gDevice, &pci, nullptr, &gFgPool) != VK_SUCCESS) {
            setError("vkCreateCommandPool failed");
            return 0;
        }
        VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ai.commandPool = gFgPool;
        ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ai.commandBufferCount = kFgOps;
        p_vkAllocateCommandBuffers(gDevice, &ai, gFgCmd);
        for (uint32_t i = 0; i < kFgOps; i++) {
            if (!gFgFence[i]) {
                VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
                fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
                p_vkCreateFence(gDevice, &fci, nullptr, &gFgFence[i]);
            }
            if (!gFgAcquire[i]) gFgAcquire[i] = createSemaphore(false);
        }
    }
    gFgSwapchain = (VkSwapchainKHR)swapchain;
    gFgSwapImages.assign((const VkImage *)images, (const VkImage *)images + count);
    for (int i = 0; i < count; i++) gFgRenderDone.push_back(createSemaphore(false));
    gFgSwapW = (uint32_t)width;
    gFgSwapH = (uint32_t)height;
    gFgSurfaceState = 0;
    gFgStop = false;
    gFgPendingSet = false;
    gFgBusy = false;
    gFgRunning = true;
    gFgThread = std::thread(fgThreadMain);
    return 1;
}

// 0 fine, 1 suboptimal, 2 out of date (Minecraft should configure the surface again).
EXPORT int dlss_fg_surface_state(void) {
    return gFgSurfaceState;
}

EXPORT void dlss_fg_camera(const FgCamera *camera) {
    gFgCamera = *camera;
    gFgCameraValid = true;
}

EXPORT int dlss_fg_present_times(float *out) {
    out[0] = (float)gFgPresentMs[0];
    out[1] = (float)gFgPresentMs[1];
    out[2] = (float)(gFgInterval * 1000.0);
    return 1;
}

static bool ensureFgFeature(VkCommandBuffer cb, uint32_t w, uint32_t h, VkFormat format) {
    uint32_t rw = gMotion.width, rh = gMotion.height;
    if (gFg && gFgW == w && gFgH == h && gFgFormat == format && gFgRenderW == rw && gFgRenderH == rh) return true;
    if (gFg) {
        deviceWaitIdle();
        NVSDK_NGX_VULKAN_ReleaseFeature(gFg);
        gFg = nullptr;
    }
    NVSDK_NGX_DLSSG_Create_Params create{};
    create.Width = w;
    create.Height = h;
    create.NativeBackbufferFormat = (unsigned int)format;
    create.RenderWidth = rw;
    create.RenderHeight = rh;
    NVSDK_NGX_Parameter_SetUI(gFgParams, NVSDK_NGX_DLSSG_Parameter_Width, w);
    NVSDK_NGX_Parameter_SetUI(gFgParams, NVSDK_NGX_DLSSG_Parameter_Height, h);
    NVSDK_NGX_Parameter_SetUI(gFgParams, NVSDK_NGX_DLSSG_Parameter_DynamicResolution, 1);
    NVSDK_NGX_Result r = NGX_VK_CREATE_DLSSG(cb, 1, 1, &gFg, gFgParams, &create);
    if (NVSDK_NGX_FAILED(r)) {
        setError("DLSS Frame Generation creation failed: 0x%08x (%ux%u format %d)", r, w, h, format);
        gFg = nullptr;
        return false;
    }
    gFgW = w;
    gFgH = h;
    gFgFormat = format;
    gFgRenderW = rw;
    gFgRenderH = rh;
    gFgHistory = false;
    return true;
}

// Records, into commandBuffer, the copy of Minecraft's finished frame (final, with the HUD) into a ring slot and, if
// interpolate and this frame has motion vectors, DLSS-G generating the frame before it. Java adds the semaphore operations of
// *out to Minecraft's submission and then queues the frame with dlss_fg_queue. Returns 1, or 0 (see dlss_last_error).
EXPORT int dlss_fg_record(uint64_t commandBuffer, const Tex *final, int interpolate, int notGame, FgSubmit *out) {
    VkCommandBuffer cb = (VkCommandBuffer)commandBuffer;
    bool cameraValid = gFgCameraValid;
    gFgCameraValid = false;
    if (!gFgRunning) {
        setError("present thread not running");
        return 0;
    }
    VkFormat format = (VkFormat)final->format;
    uint32_t w = final->width, h = final->height;
    VkImageUsageFlags usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
        | VK_IMAGE_USAGE_STORAGE_BIT;
    if (!gFgReal[0].image || gFgReal[0].width != w || gFgReal[0].height != h || gFgReal[0].format != format) {
        fgDrain(); // the present thread may still be showing the old images
        for (uint32_t i = 0; i < kFgSlots; i++) {
            if (!ensureImage(gFgReal[i], format, w, h, usage, "frame generation real frame")
                || !ensureImage(gFgInterp[i], format, w, h, usage, "frame generation generated frame")) {
                return 0;
            }
        }
        gFgHistory = false;
    }
    bool shared = gFgQueueFamily != gFgGraphicsFamily;
    uint32_t families[2] = {gFgGraphicsFamily, gFgQueueFamily};
    if (!gFgOutReal[0].image || gFgOutReal[0].width != w || gFgOutReal[0].height != h || gFgOutReal[0].format != gFgSwapFormat) {
        fgDrain();
        for (uint32_t i = 0; i < kFgSlots; i++) {
            if (!ensureImage(gFgOutReal[i], gFgSwapFormat, w, h, VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    "frame generation presented frame", shared ? families : nullptr)
                || !ensureImage(gFgOutInterp[i], gFgSwapFormat, w, h, VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    "frame generation presented generated frame", shared ? families : nullptr)) {
                return 0;
            }
        }
    }
    uint64_t id = ++gFgFrameId;
    uint32_t slot = (uint32_t)(id % kFgSlots);
    for (uint32_t i = 0; i < kFgSlots; i++) {
        transitionFresh(cb, gFgReal[i]);
        transitionFresh(cb, gFgInterp[i]);
        transitionFresh(cb, gFgOutReal[i]);
        transitionFresh(cb, gFgOutInterp[i]);
    }
    globalBarrier(cb);
    VkImageCopy copy{};
    copy.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.extent = {w, h, 1};
    p_vkCmdCopyImage(cb, (VkImage)final->image, VK_IMAGE_LAYOUT_GENERAL, gFgReal[slot].image, VK_IMAGE_LAYOUT_GENERAL, 1, &copy);
    globalBarrier(cb);

    bool interp = false;
    bool inputsValid = cameraValid && gMotion.image && gDlssDepth.image;
    if (interpolate && inputsValid && !notGame && ensureFgFeature(cb, w, h, format)) {
        NVSDK_NGX_Resource_VK backbuffer = resourceOf((uint64_t)gFgReal[slot].image, (uint64_t)gFgReal[slot].view, format, w, h, false);
        NVSDK_NGX_Resource_VK depth = resourceOf((uint64_t)gDlssDepth.image, (uint64_t)gDlssDepth.view, gDlssDepth.format,
            gDlssDepth.width, gDlssDepth.height, false);
        NVSDK_NGX_Resource_VK mvecs = resourceOf((uint64_t)gMotion.image, (uint64_t)gMotion.view, gMotion.format, gMotion.width,
            gMotion.height, false);
        NVSDK_NGX_Resource_VK output = resourceOf((uint64_t)gFgInterp[slot].image, (uint64_t)gFgInterp[slot].view, format, w, h, true);
        OwnedImage *hud = gLastOutput;
        bool hudless = hud && hud->image && hud->width == w && hud->height == h && hud->format == format;
        NVSDK_NGX_Resource_VK hudlessRes{};
        if (hudless) hudlessRes = resourceOf((uint64_t)hud->image, (uint64_t)hud->view, hud->format, w, h, false);

        NVSDK_NGX_VK_DLSSG_Eval_Params eval{};
        eval.pBackbuffer = &backbuffer;
        eval.pDepth = &depth;
        eval.pMVecs = &mvecs;
        eval.pHudless = hudless ? &hudlessRes : nullptr;
        eval.pOutputInterpFrame = &output;
        NVSDK_NGX_DLSSG_Opt_Eval_Params opt{};
        opt.multiFrameCount = 1;
        opt.multiFrameIndex = 1;
        memcpy(opt.cameraViewToClip, gFgCamera.viewToClip, 64);
        memcpy(opt.clipToCameraView, gFgCamera.clipToView, 64);
        for (int i = 0; i < 4; i++) opt.clipToLensClip[i][i] = 1.0f;
        memcpy(opt.clipToPrevClip, gFgCamera.clipToPrevClip, 64);
        memcpy(opt.prevClipToClip, gFgCamera.prevClipToClip, 64);
        opt.jitterOffset[0] = gFgCamera.jitterX;
        opt.jitterOffset[1] = gFgCamera.jitterY;
        // Multiplies the motion vectors into pixels of their own resolution, which they already are.
        opt.mvecScale[0] = 1.0f;
        opt.mvecScale[1] = 1.0f;
        memcpy(opt.cameraPos, gFgCamera.pos, 12);
        memcpy(opt.cameraUp, gFgCamera.up, 12);
        memcpy(opt.cameraRight, gFgCamera.right, 12);
        memcpy(opt.cameraFwd, gFgCamera.fwd, 12);
        opt.cameraNear = gFgCamera.nearZ;
        opt.cameraFar = gFgCamera.farZ;
        opt.cameraFOV = gFgCamera.fov;
        opt.cameraAspectRatio = gFgCamera.aspect;
        opt.colorBuffersHDR = false;
        opt.depthInverted = true;
        opt.cameraMotionIncluded = true;
        opt.reset = gFgCamera.reset != 0 || !gFgHistory;
        opt.notRenderingGameFrames = false;
        opt.orthoProjection = false;
        opt.motionVectorsInvalidValue = 0.0f;
        opt.motionVectorsDilated = false;
        opt.menuDetectionEnabled = false;
        opt.mvecsSubrectSize = {gMotion.width, gMotion.height};
        opt.depthSubrectSize = {gDlssDepth.width, gDlssDepth.height};
        if (hudless) opt.hudLessSubrectSize = {w, h};
        opt.backbufferSubrectSize = {w, h};
        opt.outputInterpSubrectSize = {w, h};
        NVSDK_NGX_Parameter_SetULL(gFgParams, NVSDK_NGX_DLSSG_Parameter_BackbufferFrameID, id);
        NVSDK_NGX_Result r = NGX_VK_EVALUATE_DLSSG(cb, gFg, gFgParams, &eval, &opt);
        if (NVSDK_NGX_FAILED(r)) {
            setError("DLSS Frame Generation evaluate failed: 0x%08x", r);
        } else {
            interp = gFgHistory; // the first frame after a reset has nothing to interpolate from
        }
        gFgHistory = NVSDK_NGX_SUCCEED(r);
        globalBarrier(cb);
    } else {
        gFgHistory = false;
    }
    // Flip (Minecraft's images are upside down relative to the screen) into the swapchain's format for the present thread.
    auto flip = [&](const OwnedImage &src, const OwnedImage &dst) {
        VkImageBlit blit{};
        blit.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        blit.srcOffsets[1] = {(int32_t)w, (int32_t)h, 1};
        blit.dstOffsets[0] = {0, (int32_t)h, 0};
        blit.dstOffsets[1] = {(int32_t)w, 0, 1};
        p_vkCmdBlitImage(cb, src.image, VK_IMAGE_LAYOUT_GENERAL, dst.image, VK_IMAGE_LAYOUT_GENERAL, 1, &blit, VK_FILTER_NEAREST);
    };
    flip(gFgReal[slot], gFgOutReal[slot]);
    if (interp) flip(gFgInterp[slot], gFgOutInterp[slot]);
    globalBarrier(cb);
    out->readySemaphore = (uint64_t)gFgReady;
    out->readyValue = id;
    out->presentedSemaphore = (uint64_t)gFgPresented;
    out->presentedValue = id > kFgSlots ? id - kFgSlots : 0;
    out->interpolated = interp ? 1 : 0;
    return 1;
}

// Hands the frame recorded by the last dlss_fg_record (already submitted) to the present thread. Blocks while the
// present thread still has an earlier frame waiting, which keeps the render thread at most one frame ahead.
EXPORT void dlss_fg_queue(uint64_t id, int interpolated, uint64_t presentId) {
    std::unique_lock<std::mutex> lock(gFgMutex);
    gFgCv.wait(lock, [] { return !gFgRunning || !gFgPendingSet; });
    if (!gFgRunning) return;
    gFgPending = {id, (uint32_t)(id % kFgSlots), interpolated != 0, presentId};
    gFgPendingSet = true;
    gFgLastQueued = id;
    gFgCv.notify_all();
}

void fgShutdown() {
    dlss_fg_stop();
    if (gFg) NVSDK_NGX_VULKAN_ReleaseFeature(gFg);
    gFg = nullptr;
    for (uint32_t i = 0; i < kFgSlots; i++) {
        destroyImage(gFgReal[i]);
        destroyImage(gFgInterp[i]);
        destroyImage(gFgOutReal[i]);
        destroyImage(gFgOutInterp[i]);
    }
    for (uint32_t i = 0; i < kFgOps; i++) {
        if (gFgFence[i]) p_vkDestroyFence(gDevice, gFgFence[i], nullptr);
        if (gFgAcquire[i]) p_vkDestroySemaphore(gDevice, gFgAcquire[i], nullptr);
        gFgFence[i] = VK_NULL_HANDLE;
        gFgAcquire[i] = VK_NULL_HANDLE;
    }
    if (gFgPool) p_vkDestroyCommandPool(gDevice, gFgPool, nullptr);
    gFgPool = VK_NULL_HANDLE;
    if (gFgReady) p_vkDestroySemaphore(gDevice, gFgReady, nullptr);
    if (gFgPresented) p_vkDestroySemaphore(gDevice, gFgPresented, nullptr);
    gFgReady = gFgPresented = VK_NULL_HANDLE;
    if (gFgParams) NVSDK_NGX_VULKAN_DestroyParameters(gFgParams);
    gFgParams = nullptr;
}


// A triangle in a window: GLFW opens it, Vulkan 1.3 draws into its swapchain with dynamic rendering, the shaders of
// shaders/ are embedded by build_shaders. The window may change its size; --frames <n> closes it after n frames, like a
// test does.
#define GLFW_INCLUDE_VULKAN
#include <GLFW/glfw3.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "triangle.frag.spv.h"
#include "triangle.vert.spv.h"

static const int FRAMES_IN_FLIGHT = 2;

static void check(VkResult result, const char* what) {
    if (result != VK_SUCCESS) {
        std::fprintf(stderr, "%s failed: %d\n", what, static_cast<int>(result));
        std::exit(1);
    }
}

static bool has_extension(const std::vector<VkExtensionProperties>& extensions, const char* name) {
    for (const auto& extension : extensions) {
        if (std::strcmp(extension.extensionName, name) == 0) {
            return true;
        }
    }
    return false;
}

// The images the window shows, one after another, with what presenting each waits for
struct Swapchain {
    VkSwapchainKHR handle = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    VkExtent2D extent{};
    std::vector<VkImage> images;
    std::vector<VkImageView> views;
    std::vector<VkSemaphore> rendered;
};

struct Context {
    GLFWwindow* window = nullptr;
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    uint32_t family = 0;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    bool resized = false;
};

static Swapchain create_swapchain(Context& context, VkSwapchainKHR old) {
    VkSurfaceCapabilitiesKHR capabilities;
    check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(context.physical, context.surface, &capabilities), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

    uint32_t count = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(context.physical, context.surface, &count, nullptr);
    std::vector<VkSurfaceFormatKHR> formats(count);
    vkGetPhysicalDeviceSurfaceFormatsKHR(context.physical, context.surface, &count, formats.data());

    // sRGB when the window has it, else what it offers first
    VkSurfaceFormatKHR chosen = formats[0];
    for (const auto& format : formats) {
        if (format.format == VK_FORMAT_B8G8R8A8_SRGB && format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            chosen = format;
        }
    }

    // the size of the surface, or of the framebuffer of the window when the surface leaves it to the swapchain
    VkExtent2D extent = capabilities.currentExtent;
    if (extent.width == UINT32_MAX) {
        int width = 0;
        int height = 0;
        glfwGetFramebufferSize(context.window, &width, &height);
        extent.width = std::clamp(static_cast<uint32_t>(width), capabilities.minImageExtent.width, capabilities.maxImageExtent.width);
        extent.height = std::clamp(static_cast<uint32_t>(height), capabilities.minImageExtent.height, capabilities.maxImageExtent.height);
    }

    uint32_t image_count = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0) {
        image_count = std::min(image_count, capabilities.maxImageCount);
    }

    VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    for (auto candidate : {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR, VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR}) {
        if (capabilities.supportedCompositeAlpha & candidate) {
            alpha = candidate;
            break;
        }
    }

    VkSwapchainCreateInfoKHR info{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    info.surface = context.surface;
    info.minImageCount = image_count;
    info.imageFormat = chosen.format;
    info.imageColorSpace = chosen.colorSpace;
    info.imageExtent = extent;
    info.imageArrayLayers = 1;
    info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.preTransform = capabilities.currentTransform;
    info.compositeAlpha = alpha;
    // FIFO waits for the display, every Vulkan has it
    info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    info.clipped = VK_TRUE;
    info.oldSwapchain = old;

    Swapchain swapchain;
    swapchain.format = chosen.format;
    swapchain.extent = extent;
    check(vkCreateSwapchainKHR(context.device, &info, nullptr, &swapchain.handle), "vkCreateSwapchainKHR");

    vkGetSwapchainImagesKHR(context.device, swapchain.handle, &count, nullptr);
    swapchain.images.resize(count);
    vkGetSwapchainImagesKHR(context.device, swapchain.handle, &count, swapchain.images.data());

    for (VkImage image : swapchain.images) {
        VkImageViewCreateInfo view_info{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        view_info.image = image;
        view_info.viewType = VK_IMAGE_VIEW_TYPE_2D;
        view_info.format = swapchain.format;
        view_info.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkImageView view;
        check(vkCreateImageView(context.device, &view_info, nullptr, &view), "vkCreateImageView");
        swapchain.views.push_back(view);

        // one for every image: presenting it waits for its drawing, a semaphore of a frame would be in use still
        VkSemaphoreCreateInfo semaphore_info{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VkSemaphore semaphore;
        check(vkCreateSemaphore(context.device, &semaphore_info, nullptr, &semaphore), "vkCreateSemaphore");
        swapchain.rendered.push_back(semaphore);
    }

    return swapchain;
}

static void destroy_swapchain(Context& context, Swapchain& swapchain) {
    for (size_t index = 0; index < swapchain.views.size(); index++) {
        vkDestroyImageView(context.device, swapchain.views[index], nullptr);
        vkDestroySemaphore(context.device, swapchain.rendered[index], nullptr);
    }
    vkDestroySwapchainKHR(context.device, swapchain.handle, nullptr);
    swapchain = Swapchain{};
}

static VkShaderModule shader_module(VkDevice device, const uint32_t* code, size_t size) {
    VkShaderModuleCreateInfo info{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    info.codeSize = size;
    info.pCode = code;
    VkShaderModule module;
    check(vkCreateShaderModule(device, &info, nullptr, &module), "vkCreateShaderModule");
    return module;
}

// The triangle for images of the format, with the size set when it draws
static VkPipeline create_pipeline(VkDevice device, VkPipelineLayout layout, VkFormat format) {
    VkShaderModule vertex = shader_module(device, triangle_vert_spv, triangle_vert_spv_size);
    VkShaderModule fragment = shader_module(device, triangle_frag_spv, triangle_frag_spv_size);

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertex;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fragment;
    stages[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vertex_input{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo assembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkPipelineViewportStateCreateInfo viewport{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    viewport.viewportCount = 1;
    viewport.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo rasterization{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rasterization.polygonMode = VK_POLYGON_MODE_FILL;
    rasterization.cullMode = VK_CULL_MODE_NONE;
    rasterization.frontFace = VK_FRONT_FACE_CLOCKWISE;
    rasterization.lineWidth = 1.0f;

    VkPipelineMultisampleStateCreateInfo multisample{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState attachment{};
    attachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo blend{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    blend.attachmentCount = 1;
    blend.pAttachments = &attachment;

    VkDynamicState dynamic_states[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dynamic.dynamicStateCount = 2;
    dynamic.pDynamicStates = dynamic_states;

    // dynamic rendering: the format of the image instead of a render pass
    VkPipelineRenderingCreateInfo rendering{VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO};
    rendering.colorAttachmentCount = 1;
    rendering.pColorAttachmentFormats = &format;

    VkGraphicsPipelineCreateInfo info{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    info.pNext = &rendering;
    info.stageCount = 2;
    info.pStages = stages;
    info.pVertexInputState = &vertex_input;
    info.pInputAssemblyState = &assembly;
    info.pViewportState = &viewport;
    info.pRasterizationState = &rasterization;
    info.pMultisampleState = &multisample;
    info.pColorBlendState = &blend;
    info.pDynamicState = &dynamic;
    info.layout = layout;

    VkPipeline pipeline;
    check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &info, nullptr, &pipeline), "vkCreateGraphicsPipelines");
    vkDestroyShaderModule(device, vertex, nullptr);
    vkDestroyShaderModule(device, fragment, nullptr);
    return pipeline;
}

static void transition(VkCommandBuffer commands, VkImage image, VkImageLayout from, VkImageLayout to, VkAccessFlags source_access,
                       VkAccessFlags target_access, VkPipelineStageFlags source_stage, VkPipelineStageFlags target_stage) {
    VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    barrier.srcAccessMask = source_access;
    barrier.dstAccessMask = target_access;
    barrier.oldLayout = from;
    barrier.newLayout = to;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commands, source_stage, target_stage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
}

static void record(VkCommandBuffer commands, const Swapchain& swapchain, uint32_t image, VkPipeline pipeline) {
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    check(vkBeginCommandBuffer(commands, &begin), "vkBeginCommandBuffer");

    transition(commands, swapchain.images[image], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 0,
               VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);

    VkRenderingAttachmentInfo color{VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO};
    color.imageView = swapchain.views[image];
    color.imageLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    color.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    color.clearValue.color = {{0.05f, 0.05f, 0.08f, 1.0f}};

    VkRenderingInfo rendering{VK_STRUCTURE_TYPE_RENDERING_INFO};
    rendering.renderArea = {{0, 0}, swapchain.extent};
    rendering.layerCount = 1;
    rendering.colorAttachmentCount = 1;
    rendering.pColorAttachments = &color;
    vkCmdBeginRendering(commands, &rendering);

    VkViewport viewport{0.0f, 0.0f, static_cast<float>(swapchain.extent.width), static_cast<float>(swapchain.extent.height), 0.0f, 1.0f};
    VkRect2D scissor{{0, 0}, swapchain.extent};
    vkCmdSetViewport(commands, 0, 1, &viewport);
    vkCmdSetScissor(commands, 0, 1, &scissor);
    vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
    vkCmdDraw(commands, 3, 1, 0, 0);
    vkCmdEndRendering(commands);

    transition(commands, swapchain.images[image], VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
               VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");
}

// A new swapchain of the size the window has now, none while it is minimized
static void recreate(Context& context, Swapchain& swapchain) {
    int width = 0;
    int height = 0;
    glfwGetFramebufferSize(context.window, &width, &height);
    while ((width == 0 || height == 0) && !glfwWindowShouldClose(context.window)) {
        glfwWaitEvents();
        glfwGetFramebufferSize(context.window, &width, &height);
    }

    vkDeviceWaitIdle(context.device);
    Swapchain old = swapchain;
    swapchain = create_swapchain(context, old.handle);
    destroy_swapchain(context, old);
    context.resized = false;
}

int main(int argc, char** argv) {
    // --frames <n>: closes after n frames
    long frames_wanted = -1;
    for (int index = 1; index + 1 < argc; index++) {
        if (std::string(argv[index]) == "--frames") {
            frames_wanted = std::strtol(argv[index + 1], nullptr, 10);
        }
    }

    if (!glfwInit()) {
        std::fprintf(stderr, "GLFW found no display to open a window on\n");
        return 1;
    }
    if (!glfwVulkanSupported()) {
        std::fprintf(stderr, "GLFW found no Vulkan loader\n");
        return 1;
    }

    Context context;
    glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
    context.window = glfwCreateWindow(800, 600, "{{build.name}}", nullptr, nullptr);
    glfwSetWindowUserPointer(context.window, &context);
    glfwSetFramebufferSizeCallback(context.window, [](GLFWwindow* window, int, int) {
        static_cast<Context*>(glfwGetWindowUserPointer(window))->resized = true;
    });

    // what GLFW needs for its surface, and on macOS the portability of MoltenVK
    uint32_t glfw_count = 0;
    const char** glfw_extensions = glfwGetRequiredInstanceExtensions(&glfw_count);
    std::vector<const char*> instance_extensions(glfw_extensions, glfw_extensions + glfw_count);

    uint32_t count = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    vkEnumerateInstanceExtensionProperties(nullptr, &count, available.data());

    VkInstanceCreateInfo instance_info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    if (has_extension(available, VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)) {
        instance_extensions.push_back(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
        instance_info.flags = VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
    }

    VkApplicationInfo application{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    application.pApplicationName = "{{build.name}}";
    application.apiVersion = VK_API_VERSION_1_3;
    instance_info.pApplicationInfo = &application;
    instance_info.enabledExtensionCount = static_cast<uint32_t>(instance_extensions.size());
    instance_info.ppEnabledExtensionNames = instance_extensions.data();
    check(vkCreateInstance(&instance_info, nullptr, &context.instance), "vkCreateInstance, is there a Vulkan driver");
    check(glfwCreateWindowSurface(context.instance, context.window, nullptr, &context.surface), "glfwCreateWindowSurface");

    // a device of Vulkan 1.3 with dynamic rendering and a queue that draws and presents to the window
    count = 0;
    vkEnumeratePhysicalDevices(context.instance, &count, nullptr);
    std::vector<VkPhysicalDevice> devices(count);
    vkEnumeratePhysicalDevices(context.instance, &count, devices.data());

    for (VkPhysicalDevice candidate : devices) {
        VkPhysicalDeviceProperties properties;
        vkGetPhysicalDeviceProperties(candidate, &properties);
        VkPhysicalDeviceVulkan13Features supported{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
        VkPhysicalDeviceFeatures2 features{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &supported};
        vkGetPhysicalDeviceFeatures2(candidate, &features);
        if (properties.apiVersion < VK_API_VERSION_1_3 || !supported.dynamicRendering) {
            continue;
        }

        uint32_t families = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &families, nullptr);
        std::vector<VkQueueFamilyProperties> queue_families(families);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &families, queue_families.data());

        for (uint32_t index = 0; index < families && context.physical == VK_NULL_HANDLE; index++) {
            VkBool32 presents = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(candidate, index, context.surface, &presents);
            if ((queue_families[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) && presents) {
                context.physical = candidate;
                context.family = index;
                std::printf("device: %s\n", properties.deviceName);
            }
        }
    }
    if (context.physical == VK_NULL_HANDLE) {
        std::fprintf(stderr, "no device of Vulkan 1.3 draws into this window\n");
        return 1;
    }

    count = 0;
    vkEnumerateDeviceExtensionProperties(context.physical, nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> device_available(count);
    vkEnumerateDeviceExtensionProperties(context.physical, nullptr, &count, device_available.data());
    std::vector<const char*> device_extensions = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    if (has_extension(device_available, "VK_KHR_portability_subset")) {
        device_extensions.push_back("VK_KHR_portability_subset");
    }

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queue_info.queueFamilyIndex = context.family;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &priority;

    VkPhysicalDeviceVulkan13Features enabled{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
    enabled.dynamicRendering = VK_TRUE;

    VkDeviceCreateInfo device_info{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    device_info.pNext = &enabled;
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos = &queue_info;
    device_info.enabledExtensionCount = static_cast<uint32_t>(device_extensions.size());
    device_info.ppEnabledExtensionNames = device_extensions.data();
    check(vkCreateDevice(context.physical, &device_info, nullptr, &context.device), "vkCreateDevice");
    vkGetDeviceQueue(context.device, context.family, 0, &context.queue);

    Swapchain swapchain = create_swapchain(context, VK_NULL_HANDLE);

    VkPipelineLayoutCreateInfo layout_info{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    VkPipelineLayout layout;
    check(vkCreatePipelineLayout(context.device, &layout_info, nullptr, &layout), "vkCreatePipelineLayout");
    VkPipeline pipeline = create_pipeline(context.device, layout, swapchain.format);

    // what each frame in flight has of its own
    VkCommandPoolCreateInfo pool_info{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pool_info.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool_info.queueFamilyIndex = context.family;
    VkCommandPool pool;
    check(vkCreateCommandPool(context.device, &pool_info, nullptr, &pool), "vkCreateCommandPool");

    VkCommandBuffer commands[FRAMES_IN_FLIGHT];
    VkCommandBufferAllocateInfo allocate_info{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocate_info.commandPool = pool;
    allocate_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate_info.commandBufferCount = FRAMES_IN_FLIGHT;
    check(vkAllocateCommandBuffers(context.device, &allocate_info, commands), "vkAllocateCommandBuffers");

    VkSemaphore acquired[FRAMES_IN_FLIGHT];
    VkFence done[FRAMES_IN_FLIGHT];
    for (int frame = 0; frame < FRAMES_IN_FLIGHT; frame++) {
        VkSemaphoreCreateInfo semaphore_info{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        check(vkCreateSemaphore(context.device, &semaphore_info, nullptr, &acquired[frame]), "vkCreateSemaphore");
        VkFenceCreateInfo fence_info{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fence_info.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        check(vkCreateFence(context.device, &fence_info, nullptr, &done[frame]), "vkCreateFence");
    }

    long frames = 0;
    while (!glfwWindowShouldClose(context.window) && (frames_wanted < 0 || frames < frames_wanted)) {
        glfwPollEvents();
        int frame = static_cast<int>(frames % FRAMES_IN_FLIGHT);
        vkWaitForFences(context.device, 1, &done[frame], VK_TRUE, UINT64_MAX);

        uint32_t image = 0;
        VkResult acquiring = vkAcquireNextImageKHR(context.device, swapchain.handle, UINT64_MAX, acquired[frame], VK_NULL_HANDLE, &image);
        if (acquiring == VK_ERROR_OUT_OF_DATE_KHR) {
            recreate(context, swapchain);
            continue;
        }
        if (acquiring != VK_SUBOPTIMAL_KHR) {
            check(acquiring, "vkAcquireNextImageKHR");
        }

        vkResetFences(context.device, 1, &done[frame]);
        vkResetCommandBuffer(commands[frame], 0);
        record(commands[frame], swapchain, image, pipeline);

        VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.waitSemaphoreCount = 1;
        submit.pWaitSemaphores = &acquired[frame];
        submit.pWaitDstStageMask = &wait_stage;
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &commands[frame];
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &swapchain.rendered[image];
        check(vkQueueSubmit(context.queue, 1, &submit, done[frame]), "vkQueueSubmit");

        VkPresentInfoKHR present{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        present.waitSemaphoreCount = 1;
        present.pWaitSemaphores = &swapchain.rendered[image];
        present.swapchainCount = 1;
        present.pSwapchains = &swapchain.handle;
        present.pImageIndices = &image;
        VkResult presenting = vkQueuePresentKHR(context.queue, &present);
        frames++;

        if (presenting == VK_ERROR_OUT_OF_DATE_KHR || presenting == VK_SUBOPTIMAL_KHR || context.resized) {
            recreate(context, swapchain);
        } else {
            check(presenting, "vkQueuePresentKHR");
        }
    }

    vkDeviceWaitIdle(context.device);
    std::printf("rendered %ld frames\n", frames);

    for (int frame = 0; frame < FRAMES_IN_FLIGHT; frame++) {
        vkDestroySemaphore(context.device, acquired[frame], nullptr);
        vkDestroyFence(context.device, done[frame], nullptr);
    }
    vkDestroyCommandPool(context.device, pool, nullptr);
    vkDestroyPipeline(context.device, pipeline, nullptr);
    vkDestroyPipelineLayout(context.device, layout, nullptr);
    destroy_swapchain(context, swapchain);
    vkDestroyDevice(context.device, nullptr);
    vkDestroySurfaceKHR(context.instance, context.surface, nullptr);
    vkDestroyInstance(context.instance, nullptr);
    glfwDestroyWindow(context.window);
    glfwTerminate();
}

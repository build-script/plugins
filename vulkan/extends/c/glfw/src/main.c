// A triangle in a window: GLFW opens it, Vulkan 1.3 draws into its swapchain with dynamic rendering, the shaders of
// shaders/ are embedded by build_shaders. The window may change its size; --frames <n> closes it after n frames, like a
// test does.
#define GLFW_INCLUDE_VULKAN
#include <GLFW/glfw3.h>

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "triangle.frag.spv.h"
#include "triangle.vert.spv.h"

#define FRAMES_IN_FLIGHT 2

static void check(VkResult result, const char* what) {
    if (result != VK_SUCCESS) {
        fprintf(stderr, "%s failed: %d\n", what, (int) result);
        exit(1);
    }
}

static bool has_extension(const VkExtensionProperties* extensions, uint32_t count, const char* name) {
    for (uint32_t index = 0; index < count; index++) {
        if (strcmp(extensions[index].extensionName, name) == 0) {
            return true;
        }
    }
    return false;
}

static uint32_t clamped(uint32_t value, uint32_t low, uint32_t high) {
    return value < low ? low : value > high ? high : value;
}

// The images the window shows, one after another, with what presenting each waits for
typedef struct {
    VkSwapchainKHR handle;
    VkFormat format;
    VkExtent2D extent;
    uint32_t count;
    VkImage* images;
    VkImageView* views;
    VkSemaphore* rendered;
} Swapchain;

typedef struct {
    GLFWwindow* window;
    VkInstance instance;
    VkSurfaceKHR surface;
    VkPhysicalDevice physical;
    uint32_t family;
    VkDevice device;
    VkQueue queue;
    bool resized;
} Context;

static Swapchain create_swapchain(Context* context, VkSwapchainKHR old) {
    VkSurfaceCapabilitiesKHR capabilities;
    check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(context->physical, context->surface, &capabilities), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

    uint32_t count = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(context->physical, context->surface, &count, NULL);
    VkSurfaceFormatKHR* formats = calloc(count + 1, sizeof(VkSurfaceFormatKHR));
    vkGetPhysicalDeviceSurfaceFormatsKHR(context->physical, context->surface, &count, formats);

    // sRGB when the window has it, else what it offers first
    VkSurfaceFormatKHR chosen = formats[0];
    for (uint32_t index = 0; index < count; index++) {
        if (formats[index].format == VK_FORMAT_B8G8R8A8_SRGB && formats[index].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            chosen = formats[index];
        }
    }
    free(formats);

    // the size of the surface, or of the framebuffer of the window when the surface leaves it to the swapchain
    VkExtent2D extent = capabilities.currentExtent;
    if (extent.width == UINT32_MAX) {
        int width = 0;
        int height = 0;
        glfwGetFramebufferSize(context->window, &width, &height);
        extent.width = clamped((uint32_t) width, capabilities.minImageExtent.width, capabilities.maxImageExtent.width);
        extent.height = clamped((uint32_t) height, capabilities.minImageExtent.height, capabilities.maxImageExtent.height);
    }

    uint32_t image_count = capabilities.minImageCount + 1;
    if (capabilities.maxImageCount > 0 && image_count > capabilities.maxImageCount) {
        image_count = capabilities.maxImageCount;
    }

    VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    const VkCompositeAlphaFlagBitsKHR candidates[] = {
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
        VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
    };
    for (size_t index = 0; index < 4; index++) {
        if (capabilities.supportedCompositeAlpha & candidates[index]) {
            alpha = candidates[index];
            break;
        }
    }

    VkSwapchainCreateInfoKHR info = {
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR,
        .surface = context->surface,
        .minImageCount = image_count,
        .imageFormat = chosen.format,
        .imageColorSpace = chosen.colorSpace,
        .imageExtent = extent,
        .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .preTransform = capabilities.currentTransform,
        .compositeAlpha = alpha,
        // FIFO waits for the display, every Vulkan has it
        .presentMode = VK_PRESENT_MODE_FIFO_KHR,
        .clipped = VK_TRUE,
        .oldSwapchain = old,
    };

    Swapchain swapchain = {.format = chosen.format, .extent = extent};
    check(vkCreateSwapchainKHR(context->device, &info, NULL, &swapchain.handle), "vkCreateSwapchainKHR");

    vkGetSwapchainImagesKHR(context->device, swapchain.handle, &swapchain.count, NULL);
    swapchain.images = calloc(swapchain.count, sizeof(VkImage));
    swapchain.views = calloc(swapchain.count, sizeof(VkImageView));
    swapchain.rendered = calloc(swapchain.count, sizeof(VkSemaphore));
    vkGetSwapchainImagesKHR(context->device, swapchain.handle, &swapchain.count, swapchain.images);

    for (uint32_t index = 0; index < swapchain.count; index++) {
        VkImageViewCreateInfo view_info = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = swapchain.images[index],
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = swapchain.format,
            .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
        };
        check(vkCreateImageView(context->device, &view_info, NULL, &swapchain.views[index]), "vkCreateImageView");

        // one for every image: presenting it waits for its drawing, a semaphore of a frame would be in use still
        VkSemaphoreCreateInfo semaphore_info = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        check(vkCreateSemaphore(context->device, &semaphore_info, NULL, &swapchain.rendered[index]), "vkCreateSemaphore");
    }

    return swapchain;
}

static void destroy_swapchain(Context* context, Swapchain* swapchain) {
    for (uint32_t index = 0; index < swapchain->count; index++) {
        vkDestroyImageView(context->device, swapchain->views[index], NULL);
        vkDestroySemaphore(context->device, swapchain->rendered[index], NULL);
    }
    vkDestroySwapchainKHR(context->device, swapchain->handle, NULL);
    free(swapchain->images);
    free(swapchain->views);
    free(swapchain->rendered);
    *swapchain = (Swapchain) {0};
}

static VkShaderModule shader_module(VkDevice device, const uint32_t* code, size_t size) {
    VkShaderModuleCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = size,
        .pCode = code,
    };
    VkShaderModule module;
    check(vkCreateShaderModule(device, &info, NULL, &module), "vkCreateShaderModule");
    return module;
}

// The triangle for images of the format, with the size set when it draws
static VkPipeline create_pipeline(VkDevice device, VkPipelineLayout layout, VkFormat format) {
    VkShaderModule vertex = shader_module(device, triangle_vert_spv, triangle_vert_spv_size);
    VkShaderModule fragment = shader_module(device, triangle_frag_spv, triangle_frag_spv_size);

    VkPipelineShaderStageCreateInfo stages[2] = {
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = vertex, .pName = "main"},
        {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = fragment, .pName = "main"},
    };

    VkPipelineVertexInputStateCreateInfo vertex_input = {.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo assembly = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
    };
    VkPipelineViewportStateCreateInfo viewport = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
        .viewportCount = 1,
        .scissorCount = 1,
    };
    VkPipelineRasterizationStateCreateInfo rasterization = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .polygonMode = VK_POLYGON_MODE_FILL,
        .cullMode = VK_CULL_MODE_NONE,
        .frontFace = VK_FRONT_FACE_CLOCKWISE,
        .lineWidth = 1.0f,
    };
    VkPipelineMultisampleStateCreateInfo multisample = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
        .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT,
    };
    VkPipelineColorBlendAttachmentState attachment = {
        .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT,
    };
    VkPipelineColorBlendStateCreateInfo blend = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
        .attachmentCount = 1,
        .pAttachments = &attachment,
    };
    VkDynamicState dynamic_states[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamic = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
        .dynamicStateCount = 2,
        .pDynamicStates = dynamic_states,
    };

    // dynamic rendering: the format of the image instead of a render pass
    VkPipelineRenderingCreateInfo rendering = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO,
        .colorAttachmentCount = 1,
        .pColorAttachmentFormats = &format,
    };

    VkGraphicsPipelineCreateInfo info = {
        .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .pNext = &rendering,
        .stageCount = 2,
        .pStages = stages,
        .pVertexInputState = &vertex_input,
        .pInputAssemblyState = &assembly,
        .pViewportState = &viewport,
        .pRasterizationState = &rasterization,
        .pMultisampleState = &multisample,
        .pColorBlendState = &blend,
        .pDynamicState = &dynamic,
        .layout = layout,
    };

    VkPipeline pipeline;
    check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &info, NULL, &pipeline), "vkCreateGraphicsPipelines");
    vkDestroyShaderModule(device, vertex, NULL);
    vkDestroyShaderModule(device, fragment, NULL);
    return pipeline;
}

static void transition(VkCommandBuffer commands, VkImage image, VkImageLayout from, VkImageLayout to, VkAccessFlags source_access,
                       VkAccessFlags target_access, VkPipelineStageFlags source_stage, VkPipelineStageFlags target_stage) {
    VkImageMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = source_access,
        .dstAccessMask = target_access,
        .oldLayout = from,
        .newLayout = to,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
    };
    vkCmdPipelineBarrier(commands, source_stage, target_stage, 0, 0, NULL, 0, NULL, 1, &barrier);
}

static void record(VkCommandBuffer commands, const Swapchain* swapchain, uint32_t image, VkPipeline pipeline) {
    VkCommandBufferBeginInfo begin = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    check(vkBeginCommandBuffer(commands, &begin), "vkBeginCommandBuffer");

    transition(commands, swapchain->images[image], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 0,
               VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);

    VkRenderingAttachmentInfo color = {
        .sType = VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO,
        .imageView = swapchain->views[image],
        .imageLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        .loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR,
        .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
        .clearValue = {.color = {.float32 = {0.05f, 0.05f, 0.08f, 1.0f}}},
    };
    VkRenderingInfo rendering = {
        .sType = VK_STRUCTURE_TYPE_RENDERING_INFO,
        .renderArea = {{0, 0}, swapchain->extent},
        .layerCount = 1,
        .colorAttachmentCount = 1,
        .pColorAttachments = &color,
    };
    vkCmdBeginRendering(commands, &rendering);

    VkViewport viewport = {0.0f, 0.0f, (float) swapchain->extent.width, (float) swapchain->extent.height, 0.0f, 1.0f};
    VkRect2D scissor = {{0, 0}, swapchain->extent};
    vkCmdSetViewport(commands, 0, 1, &viewport);
    vkCmdSetScissor(commands, 0, 1, &scissor);
    vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
    vkCmdDraw(commands, 3, 1, 0, 0);
    vkCmdEndRendering(commands);

    transition(commands, swapchain->images[image], VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
               VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
    check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");
}

// A new swapchain of the size the window has now, none while it is minimized
static void recreate(Context* context, Swapchain* swapchain) {
    int width = 0;
    int height = 0;
    glfwGetFramebufferSize(context->window, &width, &height);
    while ((width == 0 || height == 0) && !glfwWindowShouldClose(context->window)) {
        glfwWaitEvents();
        glfwGetFramebufferSize(context->window, &width, &height);
    }

    vkDeviceWaitIdle(context->device);
    Swapchain old = *swapchain;
    *swapchain = create_swapchain(context, old.handle);
    destroy_swapchain(context, &old);
    context->resized = false;
}

static void on_resize(GLFWwindow* window, int width, int height) {
    (void) width;
    (void) height;
    ((Context*) glfwGetWindowUserPointer(window))->resized = true;
}

int main(int argc, char** argv) {
    // --frames <n>: closes after n frames
    long frames_wanted = -1;
    for (int index = 1; index + 1 < argc; index++) {
        if (strcmp(argv[index], "--frames") == 0) {
            frames_wanted = strtol(argv[index + 1], NULL, 10);
        }
    }

    if (!glfwInit()) {
        fprintf(stderr, "GLFW found no display to open a window on\n");
        return 1;
    }
    if (!glfwVulkanSupported()) {
        fprintf(stderr, "GLFW found no Vulkan loader\n");
        return 1;
    }

    Context context = {0};
    glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
    context.window = glfwCreateWindow(800, 600, "{{build.name}}", NULL, NULL);
    glfwSetWindowUserPointer(context.window, &context);
    glfwSetFramebufferSizeCallback(context.window, on_resize);

    // what GLFW needs for its surface, and on macOS the portability of MoltenVK
    uint32_t glfw_count = 0;
    const char** glfw_extensions = glfwGetRequiredInstanceExtensions(&glfw_count);
    const char** instance_extensions = calloc(glfw_count + 1, sizeof(const char*));
    memcpy(instance_extensions, glfw_extensions, glfw_count * sizeof(const char*));
    uint32_t extension_count = glfw_count;

    uint32_t count = 0;
    vkEnumerateInstanceExtensionProperties(NULL, &count, NULL);
    VkExtensionProperties* available = calloc(count + 1, sizeof(VkExtensionProperties));
    vkEnumerateInstanceExtensionProperties(NULL, &count, available);

    VkInstanceCreateInfo instance_info = {.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    if (has_extension(available, count, VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)) {
        instance_extensions[extension_count++] = VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME;
        instance_info.flags = VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
    }
    free(available);

    VkApplicationInfo application = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "{{build.name}}",
        .apiVersion = VK_API_VERSION_1_3,
    };
    instance_info.pApplicationInfo = &application;
    instance_info.enabledExtensionCount = extension_count;
    instance_info.ppEnabledExtensionNames = instance_extensions;
    check(vkCreateInstance(&instance_info, NULL, &context.instance), "vkCreateInstance, is there a Vulkan driver");
    free(instance_extensions);
    check(glfwCreateWindowSurface(context.instance, context.window, NULL, &context.surface), "glfwCreateWindowSurface");

    // a device of Vulkan 1.3 with dynamic rendering and a queue that draws and presents to the window
    count = 0;
    vkEnumeratePhysicalDevices(context.instance, &count, NULL);
    VkPhysicalDevice* devices = calloc(count + 1, sizeof(VkPhysicalDevice));
    vkEnumeratePhysicalDevices(context.instance, &count, devices);

    for (uint32_t device_index = 0; device_index < count && context.physical == VK_NULL_HANDLE; device_index++) {
        VkPhysicalDevice candidate = devices[device_index];
        VkPhysicalDeviceProperties properties;
        vkGetPhysicalDeviceProperties(candidate, &properties);
        VkPhysicalDeviceVulkan13Features supported = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
        VkPhysicalDeviceFeatures2 features = {.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, .pNext = &supported};
        vkGetPhysicalDeviceFeatures2(candidate, &features);
        if (properties.apiVersion < VK_API_VERSION_1_3 || !supported.dynamicRendering) {
            continue;
        }

        uint32_t families = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &families, NULL);
        VkQueueFamilyProperties* queue_families = calloc(families + 1, sizeof(VkQueueFamilyProperties));
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &families, queue_families);

        for (uint32_t index = 0; index < families && context.physical == VK_NULL_HANDLE; index++) {
            VkBool32 presents = VK_FALSE;
            vkGetPhysicalDeviceSurfaceSupportKHR(candidate, index, context.surface, &presents);
            if ((queue_families[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) && presents) {
                context.physical = candidate;
                context.family = index;
                printf("device: %s\n", properties.deviceName);
            }
        }
        free(queue_families);
    }
    free(devices);
    if (context.physical == VK_NULL_HANDLE) {
        fprintf(stderr, "no device of Vulkan 1.3 draws into this window\n");
        return 1;
    }

    count = 0;
    vkEnumerateDeviceExtensionProperties(context.physical, NULL, &count, NULL);
    VkExtensionProperties* device_available = calloc(count + 1, sizeof(VkExtensionProperties));
    vkEnumerateDeviceExtensionProperties(context.physical, NULL, &count, device_available);
    const char* device_extensions[2] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME, "VK_KHR_portability_subset"};
    uint32_t device_extension_count = has_extension(device_available, count, "VK_KHR_portability_subset") ? 2 : 1;
    free(device_available);

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = context.family,
        .queueCount = 1,
        .pQueuePriorities = &priority,
    };
    VkPhysicalDeviceVulkan13Features enabled = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES,
        .dynamicRendering = VK_TRUE,
    };
    VkDeviceCreateInfo device_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .pNext = &enabled,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queue_info,
        .enabledExtensionCount = device_extension_count,
        .ppEnabledExtensionNames = device_extensions,
    };
    check(vkCreateDevice(context.physical, &device_info, NULL, &context.device), "vkCreateDevice");
    vkGetDeviceQueue(context.device, context.family, 0, &context.queue);

    Swapchain swapchain = create_swapchain(&context, VK_NULL_HANDLE);

    VkPipelineLayoutCreateInfo layout_info = {.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    VkPipelineLayout layout;
    check(vkCreatePipelineLayout(context.device, &layout_info, NULL, &layout), "vkCreatePipelineLayout");
    VkPipeline pipeline = create_pipeline(context.device, layout, swapchain.format);

    // what each frame in flight has of its own
    VkCommandPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = context.family,
    };
    VkCommandPool pool;
    check(vkCreateCommandPool(context.device, &pool_info, NULL, &pool), "vkCreateCommandPool");

    VkCommandBuffer commands[FRAMES_IN_FLIGHT];
    VkCommandBufferAllocateInfo allocate_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = FRAMES_IN_FLIGHT,
    };
    check(vkAllocateCommandBuffers(context.device, &allocate_info, commands), "vkAllocateCommandBuffers");

    VkSemaphore acquired[FRAMES_IN_FLIGHT];
    VkFence done[FRAMES_IN_FLIGHT];
    for (int frame = 0; frame < FRAMES_IN_FLIGHT; frame++) {
        VkSemaphoreCreateInfo semaphore_info = {.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        check(vkCreateSemaphore(context.device, &semaphore_info, NULL, &acquired[frame]), "vkCreateSemaphore");
        VkFenceCreateInfo fence_info = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO, .flags = VK_FENCE_CREATE_SIGNALED_BIT};
        check(vkCreateFence(context.device, &fence_info, NULL, &done[frame]), "vkCreateFence");
    }

    long frames = 0;
    while (!glfwWindowShouldClose(context.window) && (frames_wanted < 0 || frames < frames_wanted)) {
        glfwPollEvents();
        int frame = (int) (frames % FRAMES_IN_FLIGHT);
        vkWaitForFences(context.device, 1, &done[frame], VK_TRUE, UINT64_MAX);

        uint32_t image = 0;
        VkResult acquiring = vkAcquireNextImageKHR(context.device, swapchain.handle, UINT64_MAX, acquired[frame], VK_NULL_HANDLE, &image);
        if (acquiring == VK_ERROR_OUT_OF_DATE_KHR) {
            recreate(&context, &swapchain);
            continue;
        }
        if (acquiring != VK_SUBOPTIMAL_KHR) {
            check(acquiring, "vkAcquireNextImageKHR");
        }

        vkResetFences(context.device, 1, &done[frame]);
        vkResetCommandBuffer(commands[frame], 0);
        record(commands[frame], &swapchain, image, pipeline);

        VkPipelineStageFlags wait_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit = {
            .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
            .waitSemaphoreCount = 1,
            .pWaitSemaphores = &acquired[frame],
            .pWaitDstStageMask = &wait_stage,
            .commandBufferCount = 1,
            .pCommandBuffers = &commands[frame],
            .signalSemaphoreCount = 1,
            .pSignalSemaphores = &swapchain.rendered[image],
        };
        check(vkQueueSubmit(context.queue, 1, &submit, done[frame]), "vkQueueSubmit");

        VkPresentInfoKHR present = {
            .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,
            .waitSemaphoreCount = 1,
            .pWaitSemaphores = &swapchain.rendered[image],
            .swapchainCount = 1,
            .pSwapchains = &swapchain.handle,
            .pImageIndices = &image,
        };
        VkResult presenting = vkQueuePresentKHR(context.queue, &present);
        frames++;

        if (presenting == VK_ERROR_OUT_OF_DATE_KHR || presenting == VK_SUBOPTIMAL_KHR || context.resized) {
            recreate(&context, &swapchain);
        } else {
            check(presenting, "vkQueuePresentKHR");
        }
    }

    vkDeviceWaitIdle(context.device);
    printf("rendered %ld frames\n", frames);

    for (int frame = 0; frame < FRAMES_IN_FLIGHT; frame++) {
        vkDestroySemaphore(context.device, acquired[frame], NULL);
        vkDestroyFence(context.device, done[frame], NULL);
    }
    vkDestroyCommandPool(context.device, pool, NULL);
    vkDestroyPipeline(context.device, pipeline, NULL);
    vkDestroyPipelineLayout(context.device, layout, NULL);
    destroy_swapchain(&context, &swapchain);
    vkDestroyDevice(context.device, NULL);
    vkDestroySurfaceKHR(context.instance, context.surface, NULL);
    vkDestroyInstance(context.instance, NULL);
    glfwDestroyWindow(context.window);
    glfwTerminate();
    return 0;
}

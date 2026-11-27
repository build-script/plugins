// A triangle in a window: GLFW opens it, Vulkan 1.3 draws into its swapchain with dynamic rendering, through LWJGL. The
// shaders of shaders/ are translated by build_shaders, the jar has them as resources. The window may change its size;
// --frames <n> closes it after n frames, like a test does.
package {{build.package}};

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK13.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.*;

public class Main {
    static final int FRAMES_IN_FLIGHT = 2;

    // The images the window shows, one after another, with what presenting each waits for
    record Swapchain(long handle, int format, int width, int height, long[] images, long[] views, long[] rendered) {}

    static long window;
    static VkInstance instance;
    static long surface;
    static VkPhysicalDevice physical;
    static int family;
    static VkDevice device;
    static VkQueue queue;
    static boolean resized;

    static void check(int result, String what) {
        if (result != VK_SUCCESS) {
            System.err.println(what + " failed: " + result);
            System.exit(1);
        }
    }

    // SPIR-V of the jar, in memory outside the heap of the JVM, which Vulkan reads
    static ByteBuffer spirv(String name) throws IOException {
        try (InputStream stream = Main.class.getResourceAsStream("/" + name)) {
            if (stream == null) {
                throw new IOException("the jar has no " + name);
            }
            byte[] bytes = stream.readAllBytes();
            return MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        }
    }

    static boolean hasExtension(VkExtensionProperties.Buffer extensions, String name) {
        for (VkExtensionProperties extension : extensions) {
            if (extension.extensionNameString().equals(name)) {
                return true;
            }
        }
        return false;
    }

    static Swapchain createSwapchain(long old) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, capabilities), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

            IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, count, null);
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, count, formats);

            // sRGB when the window has it, else what it offers first
            VkSurfaceFormatKHR chosen = formats.get(0);
            for (VkSurfaceFormatKHR format : formats) {
                if (format.format() == VK_FORMAT_B8G8R8A8_SRGB && format.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    chosen = format;
                }
            }

            // the size of the surface, or of the framebuffer of the window when the surface leaves it to the swapchain
            int width = capabilities.currentExtent().width();
            int height = capabilities.currentExtent().height();
            if (width == -1) {
                IntBuffer framebufferWidth = stack.mallocInt(1);
                IntBuffer framebufferHeight = stack.mallocInt(1);
                glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
                width = Math.clamp(framebufferWidth.get(0), capabilities.minImageExtent().width(), capabilities.maxImageExtent().width());
                height = Math.clamp(framebufferHeight.get(0), capabilities.minImageExtent().height(), capabilities.maxImageExtent().height());
            }

            int imageCount = capabilities.minImageCount() + 1;
            if (capabilities.maxImageCount() > 0) {
                imageCount = Math.min(imageCount, capabilities.maxImageCount());
            }

            int alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
            int[] candidates = {
                VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
                VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
            };
            for (int candidate : candidates) {
                if ((capabilities.supportedCompositeAlpha() & candidate) != 0) {
                    alpha = candidate;
                    break;
                }
            }

            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .surface(surface)
                .minImageCount(imageCount)
                .imageFormat(chosen.format())
                .imageColorSpace(chosen.colorSpace())
                .imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .preTransform(capabilities.currentTransform())
                .compositeAlpha(alpha)
                // FIFO waits for the display, every Vulkan has it
                .presentMode(VK_PRESENT_MODE_FIFO_KHR)
                .clipped(true)
                .oldSwapchain(old);
            info.imageExtent().set(width, height);

            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(device, info, null, handle), "vkCreateSwapchainKHR");
            long swapchain = handle.get(0);

            vkGetSwapchainImagesKHR(device, swapchain, count, null);
            LongBuffer images = stack.mallocLong(count.get(0));
            vkGetSwapchainImagesKHR(device, swapchain, count, images);

            long[] imageHandles = new long[count.get(0)];
            long[] views = new long[count.get(0)];
            long[] rendered = new long[count.get(0)];
            for (int index = 0; index < imageHandles.length; index++) {
                imageHandles[index] = images.get(index);
                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(imageHandles[index])
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(chosen.format());
                viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                check(vkCreateImageView(device, viewInfo, null, handle), "vkCreateImageView");
                views[index] = handle.get(0);

                // one for every image: presenting it waits for its drawing, a semaphore of a frame would be in use still
                VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
                check(vkCreateSemaphore(device, semaphoreInfo, null, handle), "vkCreateSemaphore");
                rendered[index] = handle.get(0);
            }

            return new Swapchain(swapchain, chosen.format(), width, height, imageHandles, views, rendered);
        }
    }

    static void destroySwapchain(Swapchain swapchain) {
        for (int index = 0; index < swapchain.views().length; index++) {
            vkDestroyImageView(device, swapchain.views()[index], null);
            vkDestroySemaphore(device, swapchain.rendered()[index], null);
        }
        vkDestroySwapchainKHR(device, swapchain.handle(), null);
    }

    static long shaderModule(String name) throws IOException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer code = spirv(name);
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(code);
            LongBuffer module = stack.mallocLong(1);
            check(vkCreateShaderModule(device, info, null, module), "vkCreateShaderModule");
            MemoryUtil.memFree(code);
            return module.get(0);
        }
    }

    // The triangle for images of the format, with the size set when it draws
    static long createPipeline(long layout, int format) throws IOException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long vertex = shaderModule("triangle.vert.spv");
            long fragment = shaderModule("triangle.frag.spv");

            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"));
            stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);
            VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                .viewportCount(1)
                .scissorCount(1);
            VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                .polygonMode(VK_POLYGON_MODE_FILL)
                .cullMode(VK_CULL_MODE_NONE)
                .frontFace(VK_FRONT_FACE_CLOCKWISE)
                .lineWidth(1f);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineColorBlendAttachmentState.Buffer attachments = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            attachments.get(0).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .pAttachments(attachments);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

            // dynamic rendering: the format of the image instead of a render pass
            VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                .colorAttachmentCount(1)
                .pColorAttachmentFormats(stack.ints(format));

            VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            info.get(0).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pNext(rendering)
                .pStages(stages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(assembly)
                .pViewportState(viewport)
                .pRasterizationState(rasterization)
                .pMultisampleState(multisample)
                .pColorBlendState(blend)
                .pDynamicState(dynamic)
                .layout(layout);

            LongBuffer pipeline = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, info, null, pipeline), "vkCreateGraphicsPipelines");
            vkDestroyShaderModule(device, vertex, null);
            vkDestroyShaderModule(device, fragment, null);
            return pipeline.get(0);
        }
    }

    static void transition(MemoryStack stack, VkCommandBuffer commands, long image, int from, int to, int sourceAccess,
                           int targetAccess, int sourceStage, int targetStage) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .srcAccessMask(sourceAccess)
            .dstAccessMask(targetAccess)
            .oldLayout(from)
            .newLayout(to)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image);
        barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(commands, sourceStage, targetStage, 0, null, null, barrier);
    }

    static void record(VkCommandBuffer commands, Swapchain swapchain, int image, long pipeline) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(commands, begin), "vkBeginCommandBuffer");

            transition(stack, commands, swapchain.images()[image], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 0,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);

            VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack);
            color.get(0).sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                .imageView(swapchain.views()[image])
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            color.get(0).clearValue().color().float32(0, 0.05f).float32(1, 0.05f).float32(2, 0.08f).float32(3, 1f);

            VkRenderingInfo rendering = VkRenderingInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
                .layerCount(1)
                .pColorAttachments(color);
            rendering.renderArea().extent().set(swapchain.width(), swapchain.height());
            vkCmdBeginRendering(commands, rendering);

            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0).width(swapchain.width()).height(swapchain.height()).maxDepth(1f);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).extent().set(swapchain.width(), swapchain.height());
            vkCmdSetViewport(commands, 0, viewport);
            vkCmdSetScissor(commands, 0, scissor);
            vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            vkCmdDraw(commands, 3, 1, 0, 0);
            vkCmdEndRendering(commands);

            transition(stack, commands, swapchain.images()[image], VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");
        }
    }

    // A new swapchain of the size the window has now, none while it is minimized
    static Swapchain recreate(Swapchain swapchain) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            glfwGetFramebufferSize(window, width, height);
            while ((width.get(0) == 0 || height.get(0) == 0) && !glfwWindowShouldClose(window)) {
                glfwWaitEvents();
                glfwGetFramebufferSize(window, width, height);
            }
        }

        vkDeviceWaitIdle(device);
        Swapchain created = createSwapchain(swapchain.handle());
        destroySwapchain(swapchain);
        resized = false;
        return created;
    }

    public static void main(String[] args) throws IOException {
        // --frames <n>: closes after n frames
        long framesWanted = -1;
        for (int index = 0; index + 1 < args.length; index++) {
            if (args[index].equals("--frames")) {
                framesWanted = Long.parseLong(args[index + 1]);
            }
        }

        // GLFW of macOS wants the first thread of the process, which the JVM keeps for itself: this GLFW does without it
        if (Platform.get() == Platform.MACOSX) {
            Configuration.GLFW_LIBRARY_NAME.set("glfw_async");
        }

        if (!glfwInit()) {
            System.err.println("GLFW found no display to open a window on");
            System.exit(1);
        }
        if (!glfwVulkanSupported()) {
            System.err.println("GLFW found no Vulkan loader");
            System.exit(1);
        }

        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        window = glfwCreateWindow(800, 600, "{{build.name}}", NULL, NULL);
        glfwSetFramebufferSizeCallback(window, (handle, width, height) -> resized = true);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            PointerBuffer pointer = stack.mallocPointer(1);
            LongBuffer handle = stack.mallocLong(1);

            // what GLFW needs for its surface, and on macOS the portability of MoltenVK
            PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
            if (glfwExtensions == null) {
                throw new IllegalStateException("GLFW names no extensions for its surface");
            }
            vkEnumerateInstanceExtensionProperties((CharSequence) null, count, null);
            VkExtensionProperties.Buffer available = VkExtensionProperties.malloc(count.get(0), stack);
            vkEnumerateInstanceExtensionProperties((CharSequence) null, count, available);
            boolean portability = hasExtension(available, VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);

            PointerBuffer instanceExtensions = stack.mallocPointer(glfwExtensions.remaining() + 1);
            instanceExtensions.put(glfwExtensions);
            if (portability) {
                instanceExtensions.put(stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME));
            }
            instanceExtensions.flip();

            VkApplicationInfo application = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("{{build.name}}"))
                .apiVersion(VK_API_VERSION_1_3);
            VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .flags(portability ? VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR : 0)
                .pApplicationInfo(application)
                .ppEnabledExtensionNames(instanceExtensions);
            check(vkCreateInstance(instanceInfo, null, pointer), "vkCreateInstance, is there a Vulkan driver");
            instance = new VkInstance(pointer.get(0), instanceInfo);
            check(glfwCreateWindowSurface(instance, window, null, handle), "glfwCreateWindowSurface");
            surface = handle.get(0);

            // a device of Vulkan 1.3 with dynamic rendering and a queue that draws and presents to the window
            vkEnumeratePhysicalDevices(instance, count, null);
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            vkEnumeratePhysicalDevices(instance, count, devices);

            for (int deviceIndex = 0; deviceIndex < devices.capacity() && physical == null; deviceIndex++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(deviceIndex), instance);
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(candidate, properties);
                VkPhysicalDeviceVulkan13Features supported = VkPhysicalDeviceVulkan13Features.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES);
                VkPhysicalDeviceFeatures2 features = VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                    .pNext(supported);
                vkGetPhysicalDeviceFeatures2(candidate, features);
                if (properties.apiVersion() < VK_API_VERSION_1_3 || !supported.dynamicRendering()) {
                    continue;
                }

                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
                VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
                IntBuffer presents = stack.mallocInt(1);
                for (int index = 0; index < families.capacity(); index++) {
                    vkGetPhysicalDeviceSurfaceSupportKHR(candidate, index, surface, presents);
                    if ((families.get(index).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0 && presents.get(0) == VK_TRUE) {
                        physical = candidate;
                        family = index;
                        System.out.println("device: " + properties.deviceNameString());
                        break;
                    }
                }
            }
            if (physical == null) {
                System.err.println("no device of Vulkan 1.3 draws into this window");
                System.exit(1);
            }

            vkEnumerateDeviceExtensionProperties(physical, (CharSequence) null, count, null);
            VkExtensionProperties.Buffer deviceAvailable = VkExtensionProperties.malloc(count.get(0), stack);
            vkEnumerateDeviceExtensionProperties(physical, (CharSequence) null, count, deviceAvailable);
            PointerBuffer deviceExtensions = hasExtension(deviceAvailable, "VK_KHR_portability_subset")
                ? stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME), stack.UTF8("VK_KHR_portability_subset"))
                : stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));

            VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueInfo.get(0).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(family)
                .pQueuePriorities(stack.floats(1f));
            VkPhysicalDeviceVulkan13Features enabled = VkPhysicalDeviceVulkan13Features.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
                .dynamicRendering(true);
            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pNext(enabled)
                .pQueueCreateInfos(queueInfo)
                .ppEnabledExtensionNames(deviceExtensions);
            check(vkCreateDevice(physical, deviceInfo, null, pointer), "vkCreateDevice");
            device = new VkDevice(pointer.get(0), physical, deviceInfo);
            vkGetDeviceQueue(device, family, 0, pointer);
            queue = new VkQueue(pointer.get(0), device);
        }

        Swapchain swapchain = createSwapchain(VK_NULL_HANDLE);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            PointerBuffer pointers = stack.mallocPointer(FRAMES_IN_FLIGHT);

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            check(vkCreatePipelineLayout(device, layoutInfo, null, handle), "vkCreatePipelineLayout");
            long layout = handle.get(0);
            long pipeline = createPipeline(layout, swapchain.format());

            // what each frame in flight has of its own
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(family);
            check(vkCreateCommandPool(device, poolInfo, null, handle), "vkCreateCommandPool");
            long pool = handle.get(0);

            VkCommandBufferAllocateInfo allocateInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(pool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(FRAMES_IN_FLIGHT);
            check(vkAllocateCommandBuffers(device, allocateInfo, pointers), "vkAllocateCommandBuffers");
            VkCommandBuffer[] commands = new VkCommandBuffer[FRAMES_IN_FLIGHT];
            long[] acquired = new long[FRAMES_IN_FLIGHT];
            long[] done = new long[FRAMES_IN_FLIGHT];
            for (int frame = 0; frame < FRAMES_IN_FLIGHT; frame++) {
                commands[frame] = new VkCommandBuffer(pointers.get(frame), device);
                VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
                check(vkCreateSemaphore(device, semaphoreInfo, null, handle), "vkCreateSemaphore");
                acquired[frame] = handle.get(0);
                VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO).flags(VK_FENCE_CREATE_SIGNALED_BIT);
                check(vkCreateFence(device, fenceInfo, null, handle), "vkCreateFence");
                done[frame] = handle.get(0);
            }

            long frames = 0;
            while (!glfwWindowShouldClose(window) && (framesWanted < 0 || frames < framesWanted)) {
                glfwPollEvents();
                int frame = (int) (frames % FRAMES_IN_FLIGHT);
                vkWaitForFences(device, done[frame], true, -1L);

                int presenting;
                try (MemoryStack frameStack = MemoryStack.stackPush()) {
                    IntBuffer image = frameStack.mallocInt(1);
                    int acquiring = vkAcquireNextImageKHR(device, swapchain.handle(), -1L, acquired[frame], VK_NULL_HANDLE, image);
                    if (acquiring == VK_ERROR_OUT_OF_DATE_KHR) {
                        swapchain = recreate(swapchain);
                        continue;
                    }
                    if (acquiring != VK_SUBOPTIMAL_KHR) {
                        check(acquiring, "vkAcquireNextImageKHR");
                    }

                    vkResetFences(device, done[frame]);
                    vkResetCommandBuffer(commands[frame], 0);
                    record(commands[frame], swapchain, image.get(0), pipeline);

                    VkSubmitInfo submit = VkSubmitInfo.calloc(frameStack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .waitSemaphoreCount(1)
                        .pWaitSemaphores(frameStack.longs(acquired[frame]))
                        .pWaitDstStageMask(frameStack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                        .pCommandBuffers(frameStack.pointers(commands[frame]))
                        .pSignalSemaphores(frameStack.longs(swapchain.rendered()[image.get(0)]));
                    check(vkQueueSubmit(queue, submit, done[frame]), "vkQueueSubmit");

                    VkPresentInfoKHR present = VkPresentInfoKHR.calloc(frameStack)
                        .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                        .pWaitSemaphores(frameStack.longs(swapchain.rendered()[image.get(0)]))
                        .swapchainCount(1)
                        .pSwapchains(frameStack.longs(swapchain.handle()))
                        .pImageIndices(frameStack.ints(image.get(0)));
                    presenting = vkQueuePresentKHR(queue, present);
                }
                frames++;

                if (presenting == VK_ERROR_OUT_OF_DATE_KHR || presenting == VK_SUBOPTIMAL_KHR || resized) {
                    swapchain = recreate(swapchain);
                } else {
                    check(presenting, "vkQueuePresentKHR");
                }
            }

            vkDeviceWaitIdle(device);
            System.out.println("rendered " + frames + " frames");

            for (int frame = 0; frame < FRAMES_IN_FLIGHT; frame++) {
                vkDestroySemaphore(device, acquired[frame], null);
                vkDestroyFence(device, done[frame], null);
            }
            vkDestroyCommandPool(device, pool, null);
            vkDestroyPipeline(device, pipeline, null);
            vkDestroyPipelineLayout(device, layout, null);
        }

        destroySwapchain(swapchain);
        vkDestroyDevice(device, null);
        vkDestroySurfaceKHR(instance, surface, null);
        vkDestroyInstance(instance, null);
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}

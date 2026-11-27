// A triangle in a window: GLFW opens it, Vulkan 1.3 draws into its swapchain with dynamic rendering, through LWJGL. The
// shaders of shaders/ are translated by build_shaders, the jar has them as resources. The window may change its size;
// --frames <n> closes it after n frames, like a test does.
package {{build.package}}

import java.nio.ByteBuffer
import kotlin.system.exitProcess
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface
import org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions
import org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported
import org.lwjgl.system.Configuration
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.Platform
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME
import org.lwjgl.vulkan.KHRSurface.*
import org.lwjgl.vulkan.KHRSwapchain.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2
import org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.*

const val FRAMES_IN_FLIGHT = 2

fun check(result: Int, what: String) {
    if (result != VK_SUCCESS) {
        System.err.println("$what failed: $result")
        exitProcess(1)
    }
}

// SPIR-V of the jar, in memory outside the heap of the JVM, which Vulkan reads
fun spirv(name: String): ByteBuffer {
    val bytes = object {}.javaClass.getResourceAsStream("/$name")?.use { it.readBytes() }
        ?: error("the jar has no $name")
    return MemoryUtil.memAlloc(bytes.size).put(bytes).flip()
}

// The images the window shows, one after another, with what presenting each waits for
class Swapchain(
    val handle: Long,
    val format: Int,
    val width: Int,
    val height: Int,
    val images: LongArray,
    val views: LongArray,
    val rendered: LongArray,
)

class Context(val window: Long) {
    lateinit var instance: VkInstance
    var surface = 0L
    lateinit var physical: VkPhysicalDevice
    var family = 0
    lateinit var device: VkDevice
    lateinit var queue: VkQueue
    var resized = false
}

fun createSwapchain(context: Context, old: Long): Swapchain = MemoryStack.stackPush().use { stack ->
    val capabilities = VkSurfaceCapabilitiesKHR.malloc(stack)
    check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(context.physical, context.surface, capabilities), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR")

    val count = stack.mallocInt(1)
    vkGetPhysicalDeviceSurfaceFormatsKHR(context.physical, context.surface, count, null)
    val formats = VkSurfaceFormatKHR.malloc(count[0], stack)
    vkGetPhysicalDeviceSurfaceFormatsKHR(context.physical, context.surface, count, formats)

    // sRGB when the window has it, else what it offers first
    val chosen = formats.firstOrNull { it.format() == VK_FORMAT_B8G8R8A8_SRGB && it.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR }
        ?: formats[0]

    // the size of the surface, or of the framebuffer of the window when the surface leaves it to the swapchain
    var width = capabilities.currentExtent().width()
    var height = capabilities.currentExtent().height()
    if (width == -1) {
        val framebufferWidth = stack.mallocInt(1)
        val framebufferHeight = stack.mallocInt(1)
        glfwGetFramebufferSize(context.window, framebufferWidth, framebufferHeight)
        width = framebufferWidth[0].coerceIn(capabilities.minImageExtent().width(), capabilities.maxImageExtent().width())
        height = framebufferHeight[0].coerceIn(capabilities.minImageExtent().height(), capabilities.maxImageExtent().height())
    }

    var imageCount = capabilities.minImageCount() + 1
    if (capabilities.maxImageCount() > 0) {
        imageCount = minOf(imageCount, capabilities.maxImageCount())
    }

    val alpha = listOf(
        VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
        VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
    ).firstOrNull { capabilities.supportedCompositeAlpha() and it != 0 } ?: VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR

    val info = VkSwapchainCreateInfoKHR.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
        .surface(context.surface)
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
        .oldSwapchain(old)
    info.imageExtent().set(width, height)

    val handle = stack.mallocLong(1)
    check(vkCreateSwapchainKHR(context.device, info, null, handle), "vkCreateSwapchainKHR")
    val swapchain = handle[0]

    vkGetSwapchainImagesKHR(context.device, swapchain, count, null)
    val images = stack.mallocLong(count[0])
    vkGetSwapchainImagesKHR(context.device, swapchain, count, images)

    val views = LongArray(count[0])
    val rendered = LongArray(count[0])
    for (index in 0 until count[0]) {
        val viewInfo = VkImageViewCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
            .image(images[index])
            .viewType(VK_IMAGE_VIEW_TYPE_2D)
            .format(chosen.format())
        viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1)
        check(vkCreateImageView(context.device, viewInfo, null, handle), "vkCreateImageView")
        views[index] = handle[0]

        // one for every image: presenting it waits for its drawing, a semaphore of a frame would be in use still
        val semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
        check(vkCreateSemaphore(context.device, semaphoreInfo, null, handle), "vkCreateSemaphore")
        rendered[index] = handle[0]
    }

    Swapchain(swapchain, chosen.format(), width, height, LongArray(count[0]) { images[it] }, views, rendered)
}

fun destroySwapchain(context: Context, swapchain: Swapchain) {
    for (index in swapchain.views.indices) {
        vkDestroyImageView(context.device, swapchain.views[index], null)
        vkDestroySemaphore(context.device, swapchain.rendered[index], null)
    }
    vkDestroySwapchainKHR(context.device, swapchain.handle, null)
}

fun shaderModule(device: VkDevice, name: String): Long = MemoryStack.stackPush().use { stack ->
    val code = spirv(name)
    val info = VkShaderModuleCreateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
        .pCode(code)
    val module = stack.mallocLong(1)
    check(vkCreateShaderModule(device, info, null, module), "vkCreateShaderModule")
    MemoryUtil.memFree(code)
    module[0]
}

// The triangle for images of the format, with the size set when it draws
fun createPipeline(device: VkDevice, layout: Long, format: Int): Long = MemoryStack.stackPush().use { stack ->
    val vertex = shaderModule(device, "triangle.vert.spv")
    val fragment = shaderModule(device, "triangle.frag.spv")

    val stages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
    stages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"))
    stages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"))

    val vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
    val assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
        .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
    val viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
        .viewportCount(1)
        .scissorCount(1)
    val rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
        .polygonMode(VK_POLYGON_MODE_FILL)
        .cullMode(VK_CULL_MODE_NONE)
        .frontFace(VK_FRONT_FACE_CLOCKWISE)
        .lineWidth(1f)
    val multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
        .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
    val attachments = VkPipelineColorBlendAttachmentState.calloc(1, stack)
    attachments[0].colorWriteMask(VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT)
    val blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
        .pAttachments(attachments)
    val dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
        .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))

    // dynamic rendering: the format of the image instead of a render pass
    val rendering = VkPipelineRenderingCreateInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
        .colorAttachmentCount(1)
        .pColorAttachmentFormats(stack.ints(format))

    val info = VkGraphicsPipelineCreateInfo.calloc(1, stack)
    info[0].sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
        .pNext(rendering)
        .pStages(stages)
        .pVertexInputState(vertexInput)
        .pInputAssemblyState(assembly)
        .pViewportState(viewport)
        .pRasterizationState(rasterization)
        .pMultisampleState(multisample)
        .pColorBlendState(blend)
        .pDynamicState(dynamic)
        .layout(layout)

    val pipeline = stack.mallocLong(1)
    check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, info, null, pipeline), "vkCreateGraphicsPipelines")
    vkDestroyShaderModule(device, vertex, null)
    vkDestroyShaderModule(device, fragment, null)
    pipeline[0]
}

fun transition(
    stack: MemoryStack, commands: VkCommandBuffer, image: Long, from: Int, to: Int,
    sourceAccess: Int, targetAccess: Int, sourceStage: Int, targetStage: Int,
) {
    val barrier = VkImageMemoryBarrier.calloc(1, stack)
    barrier[0].sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
        .srcAccessMask(sourceAccess)
        .dstAccessMask(targetAccess)
        .oldLayout(from)
        .newLayout(to)
        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
        .image(image)
    barrier[0].subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1)
    vkCmdPipelineBarrier(commands, sourceStage, targetStage, 0, null, null, barrier)
}

fun record(commands: VkCommandBuffer, swapchain: Swapchain, image: Int, pipeline: Long) = MemoryStack.stackPush().use { stack ->
    val begin = VkCommandBufferBeginInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
    check(vkBeginCommandBuffer(commands, begin), "vkBeginCommandBuffer")

    transition(
        stack, commands, swapchain.images[image], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 0,
        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
    )

    val color = VkRenderingAttachmentInfo.calloc(1, stack)
    color[0].sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
        .imageView(swapchain.views[image])
        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
        .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
        .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
    color[0].clearValue().color().float32(0, 0.05f).float32(1, 0.05f).float32(2, 0.08f).float32(3, 1f)

    val rendering = VkRenderingInfo.calloc(stack)
        .sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
        .layerCount(1)
        .pColorAttachments(color)
    rendering.renderArea().extent().set(swapchain.width, swapchain.height)
    vkCmdBeginRendering(commands, rendering)

    val viewport = VkViewport.calloc(1, stack)
    viewport[0].width(swapchain.width.toFloat()).height(swapchain.height.toFloat()).maxDepth(1f)
    val scissor = VkRect2D.calloc(1, stack)
    scissor[0].extent().set(swapchain.width, swapchain.height)
    vkCmdSetViewport(commands, 0, viewport)
    vkCmdSetScissor(commands, 0, scissor)
    vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline)
    vkCmdDraw(commands, 3, 1, 0, 0)
    vkCmdEndRendering(commands)

    transition(
        stack, commands, swapchain.images[image], VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
    )
    check(vkEndCommandBuffer(commands), "vkEndCommandBuffer")
}

// A new swapchain of the size the window has now, none while it is minimized
fun recreate(context: Context, swapchain: Swapchain): Swapchain = MemoryStack.stackPush().use { stack ->
    val width = stack.mallocInt(1)
    val height = stack.mallocInt(1)
    glfwGetFramebufferSize(context.window, width, height)
    while ((width[0] == 0 || height[0] == 0) && !glfwWindowShouldClose(context.window)) {
        glfwWaitEvents()
        glfwGetFramebufferSize(context.window, width, height)
    }

    vkDeviceWaitIdle(context.device)
    val created = createSwapchain(context, swapchain.handle)
    destroySwapchain(context, swapchain)
    context.resized = false
    created
}

fun main(args: Array<String>) {
    // --frames <n>: closes after n frames
    val framesWanted = args.indexOf("--frames").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toLongOrNull() } ?: -1L

    // GLFW of macOS wants the first thread of the process, which the JVM keeps for itself: this GLFW does without it
    if (Platform.get() == Platform.MACOSX) {
        Configuration.GLFW_LIBRARY_NAME.set("glfw_async")
    }

    if (!glfwInit()) {
        System.err.println("GLFW found no display to open a window on")
        exitProcess(1)
    }
    if (!glfwVulkanSupported()) {
        System.err.println("GLFW found no Vulkan loader")
        exitProcess(1)
    }

    glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)
    val context = Context(glfwCreateWindow(800, 600, "{{build.name}}", NULL, NULL))
    glfwSetFramebufferSizeCallback(context.window) { _, _, _ -> context.resized = true }

    MemoryStack.stackPush().use { stack ->
        val count = stack.mallocInt(1)
        val pointer = stack.mallocPointer(1)
        val handle = stack.mallocLong(1)

        // what GLFW needs for its surface, and on macOS the portability of MoltenVK
        val glfwExtensions = glfwGetRequiredInstanceExtensions() ?: error("GLFW names no extensions for its surface")
        vkEnumerateInstanceExtensionProperties(null as CharSequence?, count, null)
        val available = VkExtensionProperties.malloc(count[0], stack)
        vkEnumerateInstanceExtensionProperties(null as CharSequence?, count, available)
        val portability = available.any { it.extensionNameString() == VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME }

        val instanceExtensions = stack.mallocPointer(glfwExtensions.remaining() + 1)
        instanceExtensions.put(glfwExtensions)
        if (portability) {
            instanceExtensions.put(stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME))
        }
        instanceExtensions.flip()

        val application = VkApplicationInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
            .pApplicationName(stack.UTF8("{{build.name}}"))
            .apiVersion(VK_API_VERSION_1_3)
        val instanceInfo = VkInstanceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
            .flags(if (portability) VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR else 0)
            .pApplicationInfo(application)
            .ppEnabledExtensionNames(instanceExtensions)
        check(vkCreateInstance(instanceInfo, null, pointer), "vkCreateInstance, is there a Vulkan driver")
        context.instance = VkInstance(pointer[0], instanceInfo)
        check(glfwCreateWindowSurface(context.instance, context.window, null, handle), "glfwCreateWindowSurface")
        context.surface = handle[0]

        // a device of Vulkan 1.3 with dynamic rendering and a queue that draws and presents to the window
        vkEnumeratePhysicalDevices(context.instance, count, null)
        val devices = stack.mallocPointer(count[0])
        vkEnumeratePhysicalDevices(context.instance, count, devices)

        var found = false
        for (deviceIndex in 0 until devices.capacity()) {
            if (found) break
            val candidate = VkPhysicalDevice(devices[deviceIndex], context.instance)
            val properties = VkPhysicalDeviceProperties.malloc(stack)
            vkGetPhysicalDeviceProperties(candidate, properties)
            val supported = VkPhysicalDeviceVulkan13Features.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
            val features = VkPhysicalDeviceFeatures2.calloc(stack).sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2).pNext(supported)
            vkGetPhysicalDeviceFeatures2(candidate, features)
            if (properties.apiVersion() < VK_API_VERSION_1_3 || !supported.dynamicRendering()) {
                continue
            }

            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null)
            val families = VkQueueFamilyProperties.malloc(count[0], stack)
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families)
            val presents = stack.mallocInt(1)
            for (index in 0 until families.capacity()) {
                vkGetPhysicalDeviceSurfaceSupportKHR(candidate, index, context.surface, presents)
                if (families[index].queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0 && presents[0] == VK_TRUE) {
                    context.physical = candidate
                    context.family = index
                    println("device: ${properties.deviceNameString()}")
                    found = true
                    break
                }
            }
        }
        if (!found) {
            System.err.println("no device of Vulkan 1.3 draws into this window")
            exitProcess(1)
        }

        vkEnumerateDeviceExtensionProperties(context.physical, null as CharSequence?, count, null)
        val deviceAvailable = VkExtensionProperties.malloc(count[0], stack)
        vkEnumerateDeviceExtensionProperties(context.physical, null as CharSequence?, count, deviceAvailable)
        val subset = deviceAvailable.any { it.extensionNameString() == "VK_KHR_portability_subset" }
        val deviceExtensions = if (subset) {
            stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME), stack.UTF8("VK_KHR_portability_subset"))
        } else {
            stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME))
        }

        val queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
        queueInfo[0].sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
            .queueFamilyIndex(context.family)
            .pQueuePriorities(stack.floats(1f))
        val enabled = VkPhysicalDeviceVulkan13Features.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES)
            .dynamicRendering(true)
        val deviceInfo = VkDeviceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
            .pNext(enabled)
            .pQueueCreateInfos(queueInfo)
            .ppEnabledExtensionNames(deviceExtensions)
        check(vkCreateDevice(context.physical, deviceInfo, null, pointer), "vkCreateDevice")
        context.device = VkDevice(pointer[0], context.physical, deviceInfo)
        vkGetDeviceQueue(context.device, context.family, 0, pointer)
        context.queue = VkQueue(pointer[0], context.device)
    }

    var swapchain = createSwapchain(context, VK_NULL_HANDLE)

    MemoryStack.stackPush().use { stack ->
        val handle = stack.mallocLong(1)
        val pointers = stack.mallocPointer(FRAMES_IN_FLIGHT)

        val layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
        check(vkCreatePipelineLayout(context.device, layoutInfo, null, handle), "vkCreatePipelineLayout")
        val layout = handle[0]
        val pipeline = createPipeline(context.device, layout, swapchain.format)

        // what each frame in flight has of its own
        val poolInfo = VkCommandPoolCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
            .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
            .queueFamilyIndex(context.family)
        check(vkCreateCommandPool(context.device, poolInfo, null, handle), "vkCreateCommandPool")
        val pool = handle[0]

        val allocateInfo = VkCommandBufferAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            .commandPool(pool)
            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            .commandBufferCount(FRAMES_IN_FLIGHT)
        check(vkAllocateCommandBuffers(context.device, allocateInfo, pointers), "vkAllocateCommandBuffers")
        val commands = Array(FRAMES_IN_FLIGHT) { VkCommandBuffer(pointers[it], context.device) }

        val acquired = LongArray(FRAMES_IN_FLIGHT)
        val done = LongArray(FRAMES_IN_FLIGHT)
        for (frame in 0 until FRAMES_IN_FLIGHT) {
            val semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
            check(vkCreateSemaphore(context.device, semaphoreInfo, null, handle), "vkCreateSemaphore")
            acquired[frame] = handle[0]
            val fenceInfo = VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO).flags(VK_FENCE_CREATE_SIGNALED_BIT)
            check(vkCreateFence(context.device, fenceInfo, null, handle), "vkCreateFence")
            done[frame] = handle[0]
        }

        var frames = 0L
        while (!glfwWindowShouldClose(context.window) && (framesWanted < 0 || frames < framesWanted)) {
            glfwPollEvents()
            val frame = (frames % FRAMES_IN_FLIGHT).toInt()
            vkWaitForFences(context.device, done[frame], true, -1L)

            val presented = MemoryStack.stackPush().use { frameStack ->
                val image = frameStack.mallocInt(1)
                val acquiring = vkAcquireNextImageKHR(context.device, swapchain.handle, -1L, acquired[frame], VK_NULL_HANDLE, image)
                if (acquiring == VK_ERROR_OUT_OF_DATE_KHR) {
                    return@use null
                }
                if (acquiring != VK_SUBOPTIMAL_KHR) {
                    check(acquiring, "vkAcquireNextImageKHR")
                }

                vkResetFences(context.device, done[frame])
                vkResetCommandBuffer(commands[frame], 0)
                record(commands[frame], swapchain, image[0], pipeline)

                val submit = VkSubmitInfo.calloc(frameStack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .waitSemaphoreCount(1)
                    .pWaitSemaphores(frameStack.longs(acquired[frame]))
                    .pWaitDstStageMask(frameStack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(frameStack.pointers(commands[frame]))
                    .pSignalSemaphores(frameStack.longs(swapchain.rendered[image[0]]))
                check(vkQueueSubmit(context.queue, submit, done[frame]), "vkQueueSubmit")

                val present = VkPresentInfoKHR.calloc(frameStack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(frameStack.longs(swapchain.rendered[image[0]]))
                    .swapchainCount(1)
                    .pSwapchains(frameStack.longs(swapchain.handle))
                    .pImageIndices(frameStack.ints(image[0]))
                vkQueuePresentKHR(context.queue, present)
            }

            if (presented == null) {
                swapchain = recreate(context, swapchain)
                continue
            }
            frames++

            if (presented == VK_ERROR_OUT_OF_DATE_KHR || presented == VK_SUBOPTIMAL_KHR || context.resized) {
                swapchain = recreate(context, swapchain)
            } else {
                check(presented, "vkQueuePresentKHR")
            }
        }

        vkDeviceWaitIdle(context.device)
        println("rendered $frames frames")

        for (frame in 0 until FRAMES_IN_FLIGHT) {
            vkDestroySemaphore(context.device, acquired[frame], null)
            vkDestroyFence(context.device, done[frame], null)
        }
        vkDestroyCommandPool(context.device, pool, null)
        vkDestroyPipeline(context.device, pipeline, null)
        vkDestroyPipelineLayout(context.device, layout, null)
    }

    destroySwapchain(context, swapchain)
    vkDestroyDevice(context.device, null)
    vkDestroySurfaceKHR(context.instance, context.surface, null)
    vkDestroyInstance(context.instance, null)
    glfwDestroyWindow(context.window)
    glfwTerminate()
}

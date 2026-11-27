// Doubles 16 numbers on the GPU with the compute shader shaders/double.comp, which build_shaders translates to SPIR-V:
// the jar has it as a resource. No window: it runs on every driver, also on lavapipe of Mesa.
package {{build.package}}

import java.nio.ByteBuffer
import kotlin.system.exitProcess
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3
import org.lwjgl.vulkan.*

const val COUNT = 16

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

fun main() {
    val code = spirv("double.comp.spv")

    MemoryStack.stackPush().use { stack ->
        val count = stack.mallocInt(1)
        val pointer = stack.mallocPointer(1)
        val handle = stack.mallocLong(1)

        // MoltenVK on macOS is a portability driver: the loader shows it only to instances that ask for it
        vkEnumerateInstanceExtensionProperties(null as CharSequence?, count, null)
        val instanceExtensions = VkExtensionProperties.malloc(count[0], stack)
        vkEnumerateInstanceExtensionProperties(null as CharSequence?, count, instanceExtensions)
        val portability = instanceExtensions.any { it.extensionNameString() == VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME }

        val application = VkApplicationInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
            .pApplicationName(stack.UTF8("{{build.name}}"))
            .apiVersion(VK_API_VERSION_1_3)
        val instanceInfo = VkInstanceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
            .pApplicationInfo(application)
        if (portability) {
            instanceInfo.flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
                .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)))
        }
        check(vkCreateInstance(instanceInfo, null, pointer), "vkCreateInstance, is there a Vulkan driver")
        val instance = VkInstance(pointer[0], instanceInfo)

        // the first device with a queue for compute
        vkEnumeratePhysicalDevices(instance, count, null)
        val devices = stack.mallocPointer(count[0])
        vkEnumeratePhysicalDevices(instance, count, devices)

        var physical: VkPhysicalDevice? = null
        var family = 0
        for (index in 0 until devices.capacity()) {
            val candidate = VkPhysicalDevice(devices[index], instance)
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null)
            val families = VkQueueFamilyProperties.malloc(count[0], stack)
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families)
            val compute = families.indexOfFirst { it.queueFlags() and VK_QUEUE_COMPUTE_BIT != 0 }
            if (compute >= 0) {
                physical = candidate
                family = compute
                break
            }
        }
        if (physical == null) {
            System.err.println("no Vulkan device with a compute queue")
            exitProcess(1)
        }

        val properties = VkPhysicalDeviceProperties.malloc(stack)
        vkGetPhysicalDeviceProperties(physical, properties)
        println("device: ${properties.deviceNameString()}")

        // a portability driver wants VK_KHR_portability_subset when it offers it
        vkEnumerateDeviceExtensionProperties(physical, null as CharSequence?, count, null)
        val deviceExtensions = VkExtensionProperties.malloc(count[0], stack)
        vkEnumerateDeviceExtensionProperties(physical, null as CharSequence?, count, deviceExtensions)
        val needsSubset = deviceExtensions.any { it.extensionNameString() == "VK_KHR_portability_subset" }

        val queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
        queueInfo[0].sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
            .queueFamilyIndex(family)
            .pQueuePriorities(stack.floats(1f))
        val deviceInfo = VkDeviceCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
            .pQueueCreateInfos(queueInfo)
        if (needsSubset) {
            deviceInfo.ppEnabledExtensionNames(stack.pointers(stack.UTF8("VK_KHR_portability_subset")))
        }
        check(vkCreateDevice(physical, deviceInfo, null, pointer), "vkCreateDevice")
        val device = VkDevice(pointer[0], physical, deviceInfo)
        vkGetDeviceQueue(device, family, 0, pointer)
        val queue = VkQueue(pointer[0], device)

        // a buffer the CPU writes and reads, the shader doubles it
        val size = COUNT * 4L
        val bufferInfo = VkBufferCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
            .size(size)
            .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
        check(vkCreateBuffer(device, bufferInfo, null, handle), "vkCreateBuffer")
        val buffer = handle[0]

        val requirements = VkMemoryRequirements.malloc(stack)
        vkGetBufferMemoryRequirements(device, buffer, requirements)
        val memoryProperties = VkPhysicalDeviceMemoryProperties.malloc(stack)
        vkGetPhysicalDeviceMemoryProperties(physical, memoryProperties)
        val visible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        val type = (0 until memoryProperties.memoryTypeCount()).first {
            requirements.memoryTypeBits() and (1 shl it) != 0 &&
                memoryProperties.memoryTypes(it).propertyFlags() and visible == visible
        }

        val allocateInfo = VkMemoryAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
            .allocationSize(requirements.size())
            .memoryTypeIndex(type)
        check(vkAllocateMemory(device, allocateInfo, null, handle), "vkAllocateMemory")
        val memory = handle[0]
        check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory")

        check(vkMapMemory(device, memory, 0, size, 0, pointer), "vkMapMemory")
        val numbers = MemoryUtil.memIntBuffer(pointer[0], COUNT)
        for (index in 0 until COUNT) {
            numbers.put(index, index + 1)
        }

        // the pipeline: one storage buffer at binding 0, the shader
        val bindings = VkDescriptorSetLayoutBinding.calloc(1, stack)
        bindings[0].binding(0)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .descriptorCount(1)
            .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
        val setLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
            .pBindings(bindings)
        check(vkCreateDescriptorSetLayout(device, setLayoutInfo, null, handle), "vkCreateDescriptorSetLayout")
        val setLayout = handle[0]

        val pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
            .pSetLayouts(stack.longs(setLayout))
        check(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, handle), "vkCreatePipelineLayout")
        val pipelineLayout = handle[0]

        val moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
            .pCode(code)
        check(vkCreateShaderModule(device, moduleInfo, null, handle), "vkCreateShaderModule")
        val shaderModule = handle[0]

        val pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack)
        pipelineInfo[0].sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO).layout(pipelineLayout)
        pipelineInfo[0].stage()
            .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
            .stage(VK_SHADER_STAGE_COMPUTE_BIT)
            .module(shaderModule)
            .pName(stack.UTF8("main"))
        check(vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, handle), "vkCreateComputePipelines")
        val pipeline = handle[0]

        val poolSizes = VkDescriptorPoolSize.calloc(1, stack)
        poolSizes[0].type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
        val poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
            .maxSets(1)
            .pPoolSizes(poolSizes)
        check(vkCreateDescriptorPool(device, poolInfo, null, handle), "vkCreateDescriptorPool")
        val pool = handle[0]

        val setInfo = VkDescriptorSetAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
            .descriptorPool(pool)
            .pSetLayouts(stack.longs(setLayout))
        check(vkAllocateDescriptorSets(device, setInfo, handle), "vkAllocateDescriptorSets")
        val set = handle[0]

        val described = VkDescriptorBufferInfo.calloc(1, stack)
        described[0].buffer(buffer).offset(0).range(VK_WHOLE_SIZE)
        val writes = VkWriteDescriptorSet.calloc(1, stack)
        writes[0].sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
            .dstSet(set)
            .descriptorCount(1)
            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
            .pBufferInfo(described)
        vkUpdateDescriptorSets(device, writes, null)

        // one dispatch of 16 invocations, then the results for the CPU
        val commandPoolInfo = VkCommandPoolCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
            .queueFamilyIndex(family)
        check(vkCreateCommandPool(device, commandPoolInfo, null, handle), "vkCreateCommandPool")
        val commandPool = handle[0]

        val commandInfo = VkCommandBufferAllocateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
            .commandPool(commandPool)
            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
            .commandBufferCount(1)
        check(vkAllocateCommandBuffers(device, commandInfo, pointer), "vkAllocateCommandBuffers")
        val commands = VkCommandBuffer(pointer[0], device)

        val begin = VkCommandBufferBeginInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)
        check(vkBeginCommandBuffer(commands, begin), "vkBeginCommandBuffer")
        vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline)
        vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null)
        vkCmdDispatch(commands, 1, 1, 1)
        val barrier = VkMemoryBarrier.calloc(1, stack)
        barrier[0].sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
            .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
            .dstAccessMask(VK_ACCESS_HOST_READ_BIT)
        vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null)
        check(vkEndCommandBuffer(commands), "vkEndCommandBuffer")

        val fenceInfo = VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
        check(vkCreateFence(device, fenceInfo, null, handle), "vkCreateFence")
        val fence = handle[0]

        val submit = VkSubmitInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
            .pCommandBuffers(stack.pointers(commands))
        check(vkQueueSubmit(queue, submit, fence), "vkQueueSubmit")
        check(vkWaitForFences(device, fence, true, -1L), "vkWaitForFences")

        println("doubled: " + (0 until COUNT).joinToString(" ") { numbers[it].toString() })

        vkDestroyFence(device, fence, null)
        vkDestroyCommandPool(device, commandPool, null)
        vkDestroyDescriptorPool(device, pool, null)
        vkDestroyPipeline(device, pipeline, null)
        vkDestroyShaderModule(device, shaderModule, null)
        vkDestroyPipelineLayout(device, pipelineLayout, null)
        vkDestroyDescriptorSetLayout(device, setLayout, null)
        vkUnmapMemory(device, memory)
        vkDestroyBuffer(device, buffer, null)
        vkFreeMemory(device, memory, null)
        vkDestroyDevice(device, null)
        vkDestroyInstance(instance, null)
    }

    MemoryUtil.memFree(code)
}

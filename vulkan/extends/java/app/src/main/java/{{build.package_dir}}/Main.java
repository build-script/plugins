// Doubles 16 numbers on the GPU with the compute shader shaders/double.comp, which build_shaders translates to SPIR-V:
// the jar has it as a resource. No window: it runs on every driver, also on lavapipe of Mesa.
package {{build.package}};

import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

public class Main {
    static final int COUNT = 16;

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

    public static void main(String[] args) throws IOException {
        ByteBuffer code = spirv("double.comp.spv");

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            PointerBuffer pointer = stack.mallocPointer(1);
            LongBuffer handle = stack.mallocLong(1);

            // MoltenVK on macOS is a portability driver: the loader shows it only to instances that ask for it
            vkEnumerateInstanceExtensionProperties((CharSequence) null, count, null);
            VkExtensionProperties.Buffer instanceExtensions = VkExtensionProperties.malloc(count.get(0), stack);
            vkEnumerateInstanceExtensionProperties((CharSequence) null, count, instanceExtensions);
            boolean portability = hasExtension(instanceExtensions, VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);

            VkApplicationInfo application = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("{{build.name}}"))
                .apiVersion(VK_API_VERSION_1_3);
            VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(application);
            if (portability) {
                instanceInfo.flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
                    .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)));
            }
            check(vkCreateInstance(instanceInfo, null, pointer), "vkCreateInstance, is there a Vulkan driver");
            VkInstance instance = new VkInstance(pointer.get(0), instanceInfo);

            // the first device with a queue for compute
            vkEnumeratePhysicalDevices(instance, count, null);
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            vkEnumeratePhysicalDevices(instance, count, devices);

            VkPhysicalDevice physical = null;
            int family = 0;
            for (int deviceIndex = 0; deviceIndex < devices.capacity() && physical == null; deviceIndex++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(deviceIndex), instance);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
                VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, families);
                for (int index = 0; index < families.capacity(); index++) {
                    if ((families.get(index).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                        physical = candidate;
                        family = index;
                        break;
                    }
                }
            }
            if (physical == null) {
                System.err.println("no Vulkan device with a compute queue");
                System.exit(1);
            }

            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            System.out.println("device: " + properties.deviceNameString());

            // a portability driver wants VK_KHR_portability_subset when it offers it
            vkEnumerateDeviceExtensionProperties(physical, (CharSequence) null, count, null);
            VkExtensionProperties.Buffer deviceExtensions = VkExtensionProperties.malloc(count.get(0), stack);
            vkEnumerateDeviceExtensionProperties(physical, (CharSequence) null, count, deviceExtensions);
            boolean needsSubset = hasExtension(deviceExtensions, "VK_KHR_portability_subset");

            VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
            queueInfo.get(0).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                .queueFamilyIndex(family)
                .pQueuePriorities(stack.floats(1f));
            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queueInfo);
            if (needsSubset) {
                deviceInfo.ppEnabledExtensionNames(stack.pointers(stack.UTF8("VK_KHR_portability_subset")));
            }
            check(vkCreateDevice(physical, deviceInfo, null, pointer), "vkCreateDevice");
            VkDevice device = new VkDevice(pointer.get(0), physical, deviceInfo);
            vkGetDeviceQueue(device, family, 0, pointer);
            VkQueue queue = new VkQueue(pointer.get(0), device);

            // a buffer the CPU writes and reads, the shader doubles it
            long size = COUNT * 4L;
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            check(vkCreateBuffer(device, bufferInfo, null, handle), "vkCreateBuffer");
            long buffer = handle.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, requirements);
            VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physical, memoryProperties);
            int visible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            int type = -1;
            for (int index = 0; index < memoryProperties.memoryTypeCount() && type < 0; index++) {
                if ((requirements.memoryTypeBits() & (1 << index)) != 0
                    && (memoryProperties.memoryTypes(index).propertyFlags() & visible) == visible) {
                    type = index;
                }
            }

            VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(requirements.size())
                .memoryTypeIndex(type);
            check(vkAllocateMemory(device, allocateInfo, null, handle), "vkAllocateMemory");
            long memory = handle.get(0);
            check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");

            check(vkMapMemory(device, memory, 0, size, 0, pointer), "vkMapMemory");
            IntBuffer numbers = MemoryUtil.memIntBuffer(pointer.get(0), COUNT);
            for (int index = 0; index < COUNT; index++) {
                numbers.put(index, index + 1);
            }

            // the pipeline: one storage buffer at binding 0, the shader
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
            bindings.get(0).binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo setLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings);
            check(vkCreateDescriptorSetLayout(device, setLayoutInfo, null, handle), "vkCreateDescriptorSetLayout");
            long setLayout = handle.get(0);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(setLayout));
            check(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, handle), "vkCreatePipelineLayout");
            long pipelineLayout = handle.get(0);

            VkShaderModuleCreateInfo moduleInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(code);
            check(vkCreateShaderModule(device, moduleInfo, null, handle), "vkCreateShaderModule");
            long shaderModule = handle.get(0);

            VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO).layout(pipelineLayout);
            pipelineInfo.get(0).stage()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                .module(shaderModule)
                .pName(stack.UTF8("main"));
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, handle), "vkCreateComputePipelines");
            long pipeline = handle.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .maxSets(1)
                .pPoolSizes(poolSizes);
            check(vkCreateDescriptorPool(device, poolInfo, null, handle), "vkCreateDescriptorPool");
            long pool = handle.get(0);

            VkDescriptorSetAllocateInfo setInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(pool)
                .pSetLayouts(stack.longs(setLayout));
            check(vkAllocateDescriptorSets(device, setInfo, handle), "vkAllocateDescriptorSets");
            long set = handle.get(0);

            VkDescriptorBufferInfo.Buffer described = VkDescriptorBufferInfo.calloc(1, stack);
            described.get(0).buffer(buffer).offset(0).range(VK_WHOLE_SIZE);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(set)
                .descriptorCount(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .pBufferInfo(described);
            vkUpdateDescriptorSets(device, writes, null);

            // one dispatch of 16 invocations, then the results for the CPU
            VkCommandPoolCreateInfo commandPoolInfo = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .queueFamilyIndex(family);
            check(vkCreateCommandPool(device, commandPoolInfo, null, handle), "vkCreateCommandPool");
            long commandPool = handle.get(0);

            VkCommandBufferAllocateInfo commandInfo = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1);
            check(vkAllocateCommandBuffers(device, commandInfo, pointer), "vkAllocateCommandBuffers");
            VkCommandBuffer commands = new VkCommandBuffer(pointer.get(0), device);

            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(commands, begin), "vkBeginCommandBuffer");
            vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
            vkCmdDispatch(commands, 1, 1, 1);
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null);
            check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            check(vkCreateFence(device, fenceInfo, null, handle), "vkCreateFence");
            long fence = handle.get(0);

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(commands));
            check(vkQueueSubmit(queue, submit, fence), "vkQueueSubmit");
            check(vkWaitForFences(device, fence, true, -1L), "vkWaitForFences");

            StringBuilder doubled = new StringBuilder("doubled:");
            for (int index = 0; index < COUNT; index++) {
                doubled.append(' ').append(numbers.get(index));
            }
            System.out.println(doubled);

            vkDestroyFence(device, fence, null);
            vkDestroyCommandPool(device, commandPool, null);
            vkDestroyDescriptorPool(device, pool, null);
            vkDestroyPipeline(device, pipeline, null);
            vkDestroyShaderModule(device, shaderModule, null);
            vkDestroyPipelineLayout(device, pipelineLayout, null);
            vkDestroyDescriptorSetLayout(device, setLayout, null);
            vkUnmapMemory(device, memory);
            vkDestroyBuffer(device, buffer, null);
            vkFreeMemory(device, memory, null);
            vkDestroyDevice(device, null);
            vkDestroyInstance(instance, null);
        }

        MemoryUtil.memFree(code);
    }
}

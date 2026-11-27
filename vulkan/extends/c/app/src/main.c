// Doubles 16 numbers on the GPU with the compute shader shaders/double.comp, which build_shaders translates to SPIR-V
// and embeds as double_comp_spv in double.comp.spv.h. No window: it runs on every driver, also on lavapipe of Mesa.
#include <vulkan/vulkan.h>

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "double.comp.spv.h"

#define COUNT 16

static void check(VkResult result, const char* what) {
    if (result != VK_SUCCESS) {
        fprintf(stderr, "%s failed: %d\n", what, (int) result);
        exit(1);
    }
}

// MoltenVK on macOS is a portability driver: the loader shows it only to instances that ask for it
static bool has_extension(const VkExtensionProperties* extensions, uint32_t count, const char* name) {
    for (uint32_t index = 0; index < count; index++) {
        if (strcmp(extensions[index].extensionName, name) == 0) {
            return true;
        }
    }
    return false;
}

int main(void) {
    uint32_t count = 0;
    vkEnumerateInstanceExtensionProperties(NULL, &count, NULL);
    VkExtensionProperties* instance_extensions = calloc(count + 1, sizeof(VkExtensionProperties));
    vkEnumerateInstanceExtensionProperties(NULL, &count, instance_extensions);

    VkApplicationInfo application = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "{{build.name}}",
        .apiVersion = VK_API_VERSION_1_3,
    };

    const char* portability = VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME;
    VkInstanceCreateInfo instance_info = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &application,
    };
    if (has_extension(instance_extensions, count, portability)) {
        instance_info.flags = VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
        instance_info.enabledExtensionCount = 1;
        instance_info.ppEnabledExtensionNames = &portability;
    }
    free(instance_extensions);

    VkInstance instance;
    check(vkCreateInstance(&instance_info, NULL, &instance), "vkCreateInstance, is there a Vulkan driver");

    // the first device with a queue for compute
    count = 0;
    vkEnumeratePhysicalDevices(instance, &count, NULL);
    VkPhysicalDevice* devices = calloc(count + 1, sizeof(VkPhysicalDevice));
    vkEnumeratePhysicalDevices(instance, &count, devices);

    VkPhysicalDevice physical = VK_NULL_HANDLE;
    uint32_t family = 0;
    for (uint32_t device_index = 0; device_index < count && physical == VK_NULL_HANDLE; device_index++) {
        uint32_t families = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(devices[device_index], &families, NULL);
        VkQueueFamilyProperties* properties = calloc(families + 1, sizeof(VkQueueFamilyProperties));
        vkGetPhysicalDeviceQueueFamilyProperties(devices[device_index], &families, properties);

        for (uint32_t index = 0; index < families && physical == VK_NULL_HANDLE; index++) {
            if (properties[index].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                physical = devices[device_index];
                family = index;
            }
        }
        free(properties);
    }
    free(devices);
    if (physical == VK_NULL_HANDLE) {
        fprintf(stderr, "no Vulkan device with a compute queue\n");
        return 1;
    }

    VkPhysicalDeviceProperties device_properties;
    vkGetPhysicalDeviceProperties(physical, &device_properties);
    printf("device: %s\n", device_properties.deviceName);

    // a portability driver wants VK_KHR_portability_subset when it offers it
    count = 0;
    vkEnumerateDeviceExtensionProperties(physical, NULL, &count, NULL);
    VkExtensionProperties* device_extensions = calloc(count + 1, sizeof(VkExtensionProperties));
    vkEnumerateDeviceExtensionProperties(physical, NULL, &count, device_extensions);
    const char* subset = "VK_KHR_portability_subset";
    bool needs_subset = has_extension(device_extensions, count, subset);
    free(device_extensions);

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = family,
        .queueCount = 1,
        .pQueuePriorities = &priority,
    };
    VkDeviceCreateInfo device_info = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queue_info,
        .enabledExtensionCount = needs_subset ? 1 : 0,
        .ppEnabledExtensionNames = &subset,
    };
    VkDevice device;
    check(vkCreateDevice(physical, &device_info, NULL, &device), "vkCreateDevice");
    VkQueue queue;
    vkGetDeviceQueue(device, family, 0, &queue);

    // a buffer the CPU writes and reads, the shader doubles it
    VkBufferCreateInfo buffer_info = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = COUNT * sizeof(uint32_t),
        .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
    };
    VkBuffer buffer;
    check(vkCreateBuffer(device, &buffer_info, NULL, &buffer), "vkCreateBuffer");

    VkMemoryRequirements requirements;
    vkGetBufferMemoryRequirements(device, buffer, &requirements);
    VkPhysicalDeviceMemoryProperties memory_properties;
    vkGetPhysicalDeviceMemoryProperties(physical, &memory_properties);

    const VkMemoryPropertyFlags visible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    uint32_t type = UINT32_MAX;
    for (uint32_t index = 0; index < memory_properties.memoryTypeCount && type == UINT32_MAX; index++) {
        if ((requirements.memoryTypeBits & (1u << index)) && (memory_properties.memoryTypes[index].propertyFlags & visible) == visible) {
            type = index;
        }
    }

    VkMemoryAllocateInfo allocate_info = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = requirements.size,
        .memoryTypeIndex = type,
    };
    VkDeviceMemory memory;
    check(vkAllocateMemory(device, &allocate_info, NULL, &memory), "vkAllocateMemory");
    check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");

    uint32_t* numbers = NULL;
    check(vkMapMemory(device, memory, 0, buffer_info.size, 0, (void**) &numbers), "vkMapMemory");
    for (uint32_t index = 0; index < COUNT; index++) {
        numbers[index] = index + 1;
    }

    // the pipeline: one storage buffer at binding 0, the shader
    VkDescriptorSetLayoutBinding binding = {
        .binding = 0,
        .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
        .descriptorCount = 1,
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
    };
    VkDescriptorSetLayoutCreateInfo set_layout_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 1,
        .pBindings = &binding,
    };
    VkDescriptorSetLayout set_layout;
    check(vkCreateDescriptorSetLayout(device, &set_layout_info, NULL, &set_layout), "vkCreateDescriptorSetLayout");

    VkPipelineLayoutCreateInfo pipeline_layout_info = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
        .pSetLayouts = &set_layout,
    };
    VkPipelineLayout pipeline_layout;
    check(vkCreatePipelineLayout(device, &pipeline_layout_info, NULL, &pipeline_layout), "vkCreatePipelineLayout");

    VkShaderModuleCreateInfo module_info = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = double_comp_spv_size,
        .pCode = double_comp_spv,
    };
    VkShaderModule shader_module;
    check(vkCreateShaderModule(device, &module_info, NULL, &shader_module), "vkCreateShaderModule");

    VkComputePipelineCreateInfo pipeline_info = {
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_COMPUTE_BIT,
            .module = shader_module,
            .pName = "main",
        },
        .layout = pipeline_layout,
    };
    VkPipeline pipeline;
    check(vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &pipeline_info, NULL, &pipeline), "vkCreateComputePipelines");

    VkDescriptorPoolSize pool_size = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1};
    VkDescriptorPoolCreateInfo pool_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = 1,
        .poolSizeCount = 1,
        .pPoolSizes = &pool_size,
    };
    VkDescriptorPool pool;
    check(vkCreateDescriptorPool(device, &pool_info, NULL, &pool), "vkCreateDescriptorPool");

    VkDescriptorSetAllocateInfo set_info = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = pool,
        .descriptorSetCount = 1,
        .pSetLayouts = &set_layout,
    };
    VkDescriptorSet set;
    check(vkAllocateDescriptorSets(device, &set_info, &set), "vkAllocateDescriptorSets");

    VkDescriptorBufferInfo described = {buffer, 0, VK_WHOLE_SIZE};
    VkWriteDescriptorSet write = {
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
        .dstSet = set,
        .descriptorCount = 1,
        .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
        .pBufferInfo = &described,
    };
    vkUpdateDescriptorSets(device, 1, &write, 0, NULL);

    // one dispatch of 16 invocations, then the results for the CPU
    VkCommandPoolCreateInfo command_pool_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .queueFamilyIndex = family,
    };
    VkCommandPool command_pool;
    check(vkCreateCommandPool(device, &command_pool_info, NULL, &command_pool), "vkCreateCommandPool");

    VkCommandBufferAllocateInfo command_info = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = command_pool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    VkCommandBuffer commands;
    check(vkAllocateCommandBuffers(device, &command_info, &commands), "vkAllocateCommandBuffers");

    VkCommandBufferBeginInfo begin = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    check(vkBeginCommandBuffer(commands, &begin), "vkBeginCommandBuffer");
    vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_layout, 0, 1, &set, 0, NULL);
    vkCmdDispatch(commands, 1, 1, 1);

    VkMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
    };
    vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &barrier, 0, NULL, 0, NULL);
    check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");

    VkFenceCreateInfo fence_info = {.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence fence;
    check(vkCreateFence(device, &fence_info, NULL, &fence), "vkCreateFence");

    VkSubmitInfo submit = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1,
        .pCommandBuffers = &commands,
    };
    check(vkQueueSubmit(queue, 1, &submit, fence), "vkQueueSubmit");
    check(vkWaitForFences(device, 1, &fence, VK_TRUE, UINT64_MAX), "vkWaitForFences");

    printf("doubled:");
    for (uint32_t index = 0; index < COUNT; index++) {
        printf(" %u", numbers[index]);
    }
    printf("\n");

    vkDestroyFence(device, fence, NULL);
    vkDestroyCommandPool(device, command_pool, NULL);
    vkDestroyDescriptorPool(device, pool, NULL);
    vkDestroyPipeline(device, pipeline, NULL);
    vkDestroyShaderModule(device, shader_module, NULL);
    vkDestroyPipelineLayout(device, pipeline_layout, NULL);
    vkDestroyDescriptorSetLayout(device, set_layout, NULL);
    vkUnmapMemory(device, memory);
    vkDestroyBuffer(device, buffer, NULL);
    vkFreeMemory(device, memory, NULL);
    vkDestroyDevice(device, NULL);
    vkDestroyInstance(instance, NULL);
    return 0;
}

// Doubles 16 numbers on the GPU with the compute shader shaders/double.comp, which build_shaders translates to SPIR-V
// in build/shaders/{{build.name}}-shaders. No window: it runs on every driver, also on lavapipe of Mesa.
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"unsafe"

	"github.com/bbredesen/go-vk"
)

const count = 16

func check(err error, what string) {
	if err != nil {
		fmt.Fprintf(os.Stderr, "%s failed: %v\n", what, err)
		os.Exit(1)
	}
}

// useLoader takes the loader vulkan() builds, which xkit run puts on the path of the system
func useLoader() {
	switch runtime.GOOS {
	case "darwin":
		vk.OverrideDefaultVulkanLibrary("libvulkan.1.dylib")
	case "linux":
		vk.OverrideDefaultVulkanLibrary("libvulkan.so.1")
	}
}

// spirv reads a shader of build_shaders, relative to the module, where xkit run starts the program
func spirv(name string) []uint32 {
	bytes, err := os.ReadFile(filepath.Join("build", "shaders", "{{build.name}}-shaders", name))
	check(err, "reading "+name)
	words := make([]uint32, len(bytes)/4)
	copy(unsafe.Slice((*byte)(unsafe.Pointer(&words[0])), len(bytes)), bytes)
	return words
}

func hasExtension(extensions []vk.ExtensionProperties, name string) bool {
	for _, extension := range extensions {
		if extension.ExtensionName == name {
			return true
		}
	}
	return false
}

func main() {
	useLoader()
	code := spirv("double.comp.spv")

	// MoltenVK on macOS is a portability driver: the loader shows it only to instances that ask for it
	instanceExtensions, err := vk.EnumerateInstanceExtensionProperties("")
	check(err, "vkEnumerateInstanceExtensionProperties")
	instanceInfo := vk.InstanceCreateInfo{
		PApplicationInfo: &vk.ApplicationInfo{PApplicationName: "{{build.name}}", ApiVersion: vk.API_VERSION_1_3},
	}
	if hasExtension(instanceExtensions, vk.KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME) {
		instanceInfo.Flags = vk.InstanceCreateFlags(vk.INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
		instanceInfo.PpEnabledExtensionNames = []string{vk.KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME}
	}
	instance, err := vk.CreateInstance(&instanceInfo, nil)
	check(err, "vkCreateInstance, is there a Vulkan driver")

	// the first device with a queue for compute
	devices, err := vk.EnumeratePhysicalDevices(instance)
	check(err, "vkEnumeratePhysicalDevices")
	var physical vk.PhysicalDevice
	family := -1
	for _, candidate := range devices {
		for index, properties := range vk.GetPhysicalDeviceQueueFamilyProperties(candidate) {
			if properties.QueueFlags&vk.QueueFlags(vk.QUEUE_COMPUTE_BIT) != 0 {
				physical, family = candidate, index
				break
			}
		}
		if family >= 0 {
			break
		}
	}
	if family < 0 {
		fmt.Fprintln(os.Stderr, "no Vulkan device with a compute queue")
		os.Exit(1)
	}
	fmt.Println("device:", vk.GetPhysicalDeviceProperties(physical).DeviceName)

	// a portability driver wants VK_KHR_portability_subset when it offers it
	deviceExtensions, err := vk.EnumerateDeviceExtensionProperties(physical, "")
	check(err, "vkEnumerateDeviceExtensionProperties")
	deviceInfo := vk.DeviceCreateInfo{
		PQueueCreateInfos: []vk.DeviceQueueCreateInfo{{QueueFamilyIndex: uint32(family), PQueuePriorities: []float32{1}}},
	}
	if hasExtension(deviceExtensions, "VK_KHR_portability_subset") {
		deviceInfo.PpEnabledExtensionNames = []string{"VK_KHR_portability_subset"}
	}
	device, err := vk.CreateDevice(physical, &deviceInfo, nil)
	check(err, "vkCreateDevice")
	queue := vk.GetDeviceQueue(device, uint32(family), 0)

	// a buffer the CPU writes and reads, the shader doubles it
	size := vk.DeviceSize(count * 4)
	buffer, err := vk.CreateBuffer(device, &vk.BufferCreateInfo{
		Size:  size,
		Usage: vk.BufferUsageFlags(vk.BUFFER_USAGE_STORAGE_BUFFER_BIT),
	}, nil)
	check(err, "vkCreateBuffer")

	requirements := vk.GetBufferMemoryRequirements(device, buffer)
	memoryProperties := vk.GetPhysicalDeviceMemoryProperties(physical)
	visible := vk.MemoryPropertyFlags(vk.MEMORY_PROPERTY_HOST_VISIBLE_BIT | vk.MEMORY_PROPERTY_HOST_COHERENT_BIT)
	memoryType := -1
	for index := 0; index < int(memoryProperties.MemoryTypeCount) && memoryType < 0; index++ {
		if requirements.MemoryTypeBits&(1<<index) != 0 && memoryProperties.MemoryTypes[index].PropertyFlags&visible == visible {
			memoryType = index
		}
	}

	memory, err := vk.AllocateMemory(device, &vk.MemoryAllocateInfo{AllocationSize: requirements.Size, MemoryTypeIndex: uint32(memoryType)}, nil)
	check(err, "vkAllocateMemory")
	check(vk.BindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory")

	mapped, err := vk.MapMemory(device, memory, 0, size, 0)
	check(err, "vkMapMemory")
	numbers := unsafe.Slice((*uint32)(unsafe.Pointer(mapped)), count)
	for index := range numbers {
		numbers[index] = uint32(index + 1)
	}

	// the pipeline: one storage buffer at binding 0, the shader
	setLayout, err := vk.CreateDescriptorSetLayout(device, &vk.DescriptorSetLayoutCreateInfo{
		PBindings: []vk.DescriptorSetLayoutBinding{{
			Binding:         0,
			DescriptorType:  vk.DESCRIPTOR_TYPE_STORAGE_BUFFER,
			DescriptorCount: 1,
			StageFlags:      vk.ShaderStageFlags(vk.SHADER_STAGE_COMPUTE_BIT),
		}},
	}, nil)
	check(err, "vkCreateDescriptorSetLayout")

	pipelineLayout, err := vk.CreatePipelineLayout(device, &vk.PipelineLayoutCreateInfo{PSetLayouts: []vk.DescriptorSetLayout{setLayout}}, nil)
	check(err, "vkCreatePipelineLayout")

	shaderModule, err := vk.CreateShaderModule(device, &vk.ShaderModuleCreateInfo{CodeSize: uintptr(len(code) * 4), PCode: &code[0]}, nil)
	check(err, "vkCreateShaderModule")

	pipelines, err := vk.CreateComputePipelines(device, vk.PipelineCache(vk.NULL_HANDLE), []vk.ComputePipelineCreateInfo{{
		Stage:  vk.PipelineShaderStageCreateInfo{Stage: vk.SHADER_STAGE_COMPUTE_BIT, Module: shaderModule, PName: "main"},
		Layout: pipelineLayout,
	}}, nil)
	check(err, "vkCreateComputePipelines")
	pipeline := pipelines[0]

	pool, err := vk.CreateDescriptorPool(device, &vk.DescriptorPoolCreateInfo{
		MaxSets:    1,
		PPoolSizes: []vk.DescriptorPoolSize{{Typ: vk.DESCRIPTOR_TYPE_STORAGE_BUFFER, DescriptorCount: 1}},
	}, nil)
	check(err, "vkCreateDescriptorPool")

	sets, err := vk.AllocateDescriptorSets(device, &vk.DescriptorSetAllocateInfo{DescriptorPool: pool, PSetLayouts: []vk.DescriptorSetLayout{setLayout}})
	check(err, "vkAllocateDescriptorSets")
	vk.UpdateDescriptorSets(device, []vk.WriteDescriptorSet{{
		DstSet:         sets[0],
		DescriptorType: vk.DESCRIPTOR_TYPE_STORAGE_BUFFER,
		PBufferInfo:    []vk.DescriptorBufferInfo{{Buffer: buffer, Rang: vk.DeviceSize(vk.WHOLE_SIZE)}},
	}}, nil)

	// one dispatch of 16 invocations, then the results for the CPU
	commandPool, err := vk.CreateCommandPool(device, &vk.CommandPoolCreateInfo{QueueFamilyIndex: uint32(family)}, nil)
	check(err, "vkCreateCommandPool")
	commandBuffers, err := vk.AllocateCommandBuffers(device, &vk.CommandBufferAllocateInfo{
		CommandPool:        commandPool,
		Level:              vk.COMMAND_BUFFER_LEVEL_PRIMARY,
		CommandBufferCount: 1,
	})
	check(err, "vkAllocateCommandBuffers")
	commands := commandBuffers[0]

	check(vk.BeginCommandBuffer(commands, &vk.CommandBufferBeginInfo{Flags: vk.CommandBufferUsageFlags(vk.COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)}), "vkBeginCommandBuffer")
	vk.CmdBindPipeline(commands, vk.PIPELINE_BIND_POINT_COMPUTE, pipeline)
	vk.CmdBindDescriptorSets(commands, vk.PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, sets, nil)
	vk.CmdDispatch(commands, 1, 1, 1)
	vk.CmdPipelineBarrier(commands, vk.PipelineStageFlags(vk.PIPELINE_STAGE_COMPUTE_SHADER_BIT), vk.PipelineStageFlags(vk.PIPELINE_STAGE_HOST_BIT), 0,
		[]vk.MemoryBarrier{{SrcAccessMask: vk.AccessFlags(vk.ACCESS_SHADER_WRITE_BIT), DstAccessMask: vk.AccessFlags(vk.ACCESS_HOST_READ_BIT)}}, nil, nil)
	check(vk.EndCommandBuffer(commands), "vkEndCommandBuffer")

	fence, err := vk.CreateFence(device, &vk.FenceCreateInfo{}, nil)
	check(err, "vkCreateFence")
	check(vk.QueueSubmit(queue, []vk.SubmitInfo{{PCommandBuffers: []vk.CommandBuffer{commands}}}, fence), "vkQueueSubmit")
	check(vk.WaitForFences(device, []vk.Fence{fence}, true, ^uint64(0)), "vkWaitForFences")

	doubled := make([]string, count)
	for index, number := range numbers {
		doubled[index] = fmt.Sprint(number)
	}
	fmt.Println("doubled: " + strings.Join(doubled, " "))

	vk.DestroyFence(device, fence, nil)
	vk.DestroyCommandPool(device, commandPool, nil)
	vk.DestroyDescriptorPool(device, pool, nil)
	vk.DestroyPipeline(device, pipeline, nil)
	vk.DestroyShaderModule(device, shaderModule, nil)
	vk.DestroyPipelineLayout(device, pipelineLayout, nil)
	vk.DestroyDescriptorSetLayout(device, setLayout, nil)
	vk.UnmapMemory(device, memory)
	vk.DestroyBuffer(device, buffer, nil)
	vk.FreeMemory(device, memory, nil)
	vk.DestroyDevice(device, nil)
	vk.DestroyInstance(instance, nil)
}

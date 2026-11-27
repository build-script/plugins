// A triangle in a window: GLFW opens it, Vulkan 1.3 draws into its swapchain with dynamic rendering, through go-vk. The
// shaders of shaders/ are translated by build_shaders into build/shaders/{{build.name}}-shaders. The window may change
// its size; --frames <n> closes it after n frames, like a test does.
package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"unsafe"

	"github.com/bbredesen/go-vk"
)

const framesInFlight = 2

func check(err error, what string) {
	if err != nil {
		fmt.Fprintf(os.Stderr, "%s failed: %v\n", what, err)
		os.Exit(1)
	}
}

// useLoader takes the loader vulkan() builds, which build run puts on the path of the system
func useLoader() {
	switch runtime.GOOS {
	case "darwin":
		vk.OverrideDefaultVulkanLibrary("libvulkan.1.dylib")
	case "linux":
		vk.OverrideDefaultVulkanLibrary("libvulkan.so.1")
	}
}

// spirv reads a shader of build_shaders, relative to the module, where build run starts the program
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

// swapchain holds the images the window shows, one after another, with what presenting each waits for
type swapchain struct {
	handle   vk.SwapchainKHR
	format   vk.Format
	extent   vk.Extent2D
	images   []vk.Image
	views    []vk.ImageView
	rendered []vk.Semaphore
}

type context struct {
	window   *window
	instance vk.Instance
	surface  vk.SurfaceKHR
	physical vk.PhysicalDevice
	family   uint32
	device   vk.Device
	queue    vk.Queue
}

func createSwapchain(c *context, old vk.SwapchainKHR) *swapchain {
	capabilities, err := vk.GetPhysicalDeviceSurfaceCapabilitiesKHR(c.physical, c.surface)
	check(err, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR")
	formats, err := vk.GetPhysicalDeviceSurfaceFormatsKHR(c.physical, c.surface)
	check(err, "vkGetPhysicalDeviceSurfaceFormatsKHR")

	// sRGB when the window has it, else what it offers first
	chosen := formats[0]
	for _, format := range formats {
		if format.Format == vk.FORMAT_B8G8R8A8_SRGB && format.ColorSpace == vk.COLOR_SPACE_SRGB_NONLINEAR_KHR {
			chosen = format
		}
	}

	// the size of the surface, or of the framebuffer of the window when the surface leaves it to the swapchain
	extent := capabilities.CurrentExtent
	if extent.Width == ^uint32(0) {
		width, height := c.window.framebufferSize()
		extent.Width = min(max(width, capabilities.MinImageExtent.Width), capabilities.MaxImageExtent.Width)
		extent.Height = min(max(height, capabilities.MinImageExtent.Height), capabilities.MaxImageExtent.Height)
	}

	imageCount := capabilities.MinImageCount + 1
	if capabilities.MaxImageCount > 0 {
		imageCount = min(imageCount, capabilities.MaxImageCount)
	}

	alpha := vk.COMPOSITE_ALPHA_OPAQUE_BIT_KHR
	for _, candidate := range []vk.CompositeAlphaFlagBitsKHR{
		vk.COMPOSITE_ALPHA_OPAQUE_BIT_KHR, vk.COMPOSITE_ALPHA_INHERIT_BIT_KHR,
		vk.COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, vk.COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
	} {
		if capabilities.SupportedCompositeAlpha&vk.CompositeAlphaFlagsKHR(candidate) != 0 {
			alpha = candidate
			break
		}
	}

	handle, err := vk.CreateSwapchainKHR(c.device, &vk.SwapchainCreateInfoKHR{
		Surface:          c.surface,
		MinImageCount:    imageCount,
		ImageFormat:      chosen.Format,
		ImageColorSpace:  chosen.ColorSpace,
		ImageExtent:      extent,
		ImageArrayLayers: 1,
		ImageUsage:       vk.ImageUsageFlags(vk.IMAGE_USAGE_COLOR_ATTACHMENT_BIT),
		ImageSharingMode: vk.SHARING_MODE_EXCLUSIVE,
		PreTransform:     capabilities.CurrentTransform,
		CompositeAlpha:   alpha,
		// FIFO waits for the display, every Vulkan has it
		PresentMode:  vk.PRESENT_MODE_FIFO_KHR,
		Clipped:      true,
		OldSwapchain: old,
	}, nil)
	check(err, "vkCreateSwapchainKHR")

	images, err := vk.GetSwapchainImagesKHR(c.device, handle)
	check(err, "vkGetSwapchainImagesKHR")

	created := &swapchain{handle: handle, format: chosen.Format, extent: extent, images: images}
	for _, image := range images {
		view, err := vk.CreateImageView(c.device, &vk.ImageViewCreateInfo{
			Image:    image,
			ViewType: vk.IMAGE_VIEW_TYPE_2D,
			Format:   chosen.Format,
			SubresourceRange: vk.ImageSubresourceRange{
				AspectMask: vk.ImageAspectFlags(vk.IMAGE_ASPECT_COLOR_BIT),
				LevelCount: 1,
				LayerCount: 1,
			},
		}, nil)
		check(err, "vkCreateImageView")
		created.views = append(created.views, view)

		// one for every image: presenting it waits for its drawing, a semaphore of a frame would be in use still
		semaphore, err := vk.CreateSemaphore(c.device, &vk.SemaphoreCreateInfo{}, nil)
		check(err, "vkCreateSemaphore")
		created.rendered = append(created.rendered, semaphore)
	}

	return created
}

func destroySwapchain(c *context, s *swapchain) {
	for index := range s.views {
		vk.DestroyImageView(c.device, s.views[index], nil)
		vk.DestroySemaphore(c.device, s.rendered[index], nil)
	}
	vk.DestroySwapchainKHR(c.device, s.handle, nil)
}

func shaderModule(device vk.Device, name string) vk.ShaderModule {
	code := spirv(name)
	module, err := vk.CreateShaderModule(device, &vk.ShaderModuleCreateInfo{CodeSize: uintptr(len(code) * 4), PCode: &code[0]}, nil)
	check(err, "vkCreateShaderModule")
	return module
}

// createPipeline draws the triangle into images of the format, with the size set when it draws
func createPipeline(device vk.Device, layout vk.PipelineLayout, format vk.Format) vk.Pipeline {
	vertex := shaderModule(device, "triangle.vert.spv")
	fragment := shaderModule(device, "triangle.frag.spv")

	// dynamic rendering: the format of the image instead of a render pass
	rendering := vk.PipelineRenderingCreateInfo{PColorAttachmentFormats: []vk.Format{format}}

	pipelines, err := vk.CreateGraphicsPipelines(device, vk.PipelineCache(vk.NULL_HANDLE), []vk.GraphicsPipelineCreateInfo{{
		PNext: unsafe.Pointer(rendering.Vulkanize()),
		PStages: []vk.PipelineShaderStageCreateInfo{
			{Stage: vk.SHADER_STAGE_VERTEX_BIT, Module: vertex, PName: "main"},
			{Stage: vk.SHADER_STAGE_FRAGMENT_BIT, Module: fragment, PName: "main"},
		},
		PVertexInputState:   &vk.PipelineVertexInputStateCreateInfo{},
		PInputAssemblyState: &vk.PipelineInputAssemblyStateCreateInfo{Topology: vk.PRIMITIVE_TOPOLOGY_TRIANGLE_LIST},
		// one of each, set when it draws
		PViewportState: &vk.PipelineViewportStateCreateInfo{PViewports: []vk.Viewport{{}}, PScissors: []vk.Rect2D{{}}},
		PRasterizationState: &vk.PipelineRasterizationStateCreateInfo{
			PolygonMode: vk.POLYGON_MODE_FILL,
			CullMode:    vk.CullModeFlags(vk.CULL_MODE_NONE),
			FrontFace:   vk.FRONT_FACE_CLOCKWISE,
			LineWidth:   1,
		},
		PMultisampleState: &vk.PipelineMultisampleStateCreateInfo{RasterizationSamples: vk.SAMPLE_COUNT_1_BIT},
		PColorBlendState: &vk.PipelineColorBlendStateCreateInfo{
			PAttachments: []vk.PipelineColorBlendAttachmentState{{
				ColorWriteMask: vk.ColorComponentFlags(vk.COLOR_COMPONENT_R_BIT | vk.COLOR_COMPONENT_G_BIT | vk.COLOR_COMPONENT_B_BIT | vk.COLOR_COMPONENT_A_BIT),
			}},
		},
		PDynamicState: &vk.PipelineDynamicStateCreateInfo{PDynamicStates: []vk.DynamicState{vk.DYNAMIC_STATE_VIEWPORT, vk.DYNAMIC_STATE_SCISSOR}},
		Layout:        layout,
	}}, nil)
	check(err, "vkCreateGraphicsPipelines")

	vk.DestroyShaderModule(device, vertex, nil)
	vk.DestroyShaderModule(device, fragment, nil)
	return pipelines[0]
}

func transition(commands vk.CommandBuffer, image vk.Image, from, to vk.ImageLayout, sourceAccess, targetAccess vk.AccessFlagBits, sourceStage, targetStage vk.PipelineStageFlagBits) {
	vk.CmdPipelineBarrier(commands, vk.PipelineStageFlags(sourceStage), vk.PipelineStageFlags(targetStage), 0, nil, nil, []vk.ImageMemoryBarrier{{
		SrcAccessMask:       vk.AccessFlags(sourceAccess),
		DstAccessMask:       vk.AccessFlags(targetAccess),
		OldLayout:           from,
		NewLayout:           to,
		SrcQueueFamilyIndex: vk.QUEUE_FAMILY_IGNORED,
		DstQueueFamilyIndex: vk.QUEUE_FAMILY_IGNORED,
		Image:               image,
		SubresourceRange:    vk.ImageSubresourceRange{AspectMask: vk.ImageAspectFlags(vk.IMAGE_ASPECT_COLOR_BIT), LevelCount: 1, LayerCount: 1},
	}})
}

func record(commands vk.CommandBuffer, s *swapchain, image uint32, pipeline vk.Pipeline) {
	check(vk.BeginCommandBuffer(commands, &vk.CommandBufferBeginInfo{Flags: vk.CommandBufferUsageFlags(vk.COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)}), "vkBeginCommandBuffer")

	transition(commands, s.images[image], vk.IMAGE_LAYOUT_UNDEFINED, vk.IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 0,
		vk.ACCESS_COLOR_ATTACHMENT_WRITE_BIT, vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)

	var color vk.ClearColorValue
	color.AsTypeFloat32([4]float32{0.05, 0.05, 0.08, 1})
	var clear vk.ClearValue
	clear.AsColor(color)

	vk.CmdBeginRendering(commands, &vk.RenderingInfo{
		RenderArea: vk.Rect2D{Extent: s.extent},
		LayerCount: 1,
		PColorAttachments: []vk.RenderingAttachmentInfo{{
			ImageView:   s.views[image],
			ImageLayout: vk.IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
			LoadOp:      vk.ATTACHMENT_LOAD_OP_CLEAR,
			StoreOp:     vk.ATTACHMENT_STORE_OP_STORE,
			ClearValue:  clear,
		}},
	})

	vk.CmdSetViewport(commands, 0, []vk.Viewport{{Width: float32(s.extent.Width), Height: float32(s.extent.Height), MaxDepth: 1}})
	vk.CmdSetScissor(commands, 0, []vk.Rect2D{{Extent: s.extent}})
	vk.CmdBindPipeline(commands, vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline)
	vk.CmdDraw(commands, 3, 1, 0, 0)
	vk.CmdEndRendering(commands)

	transition(commands, s.images[image], vk.IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, vk.IMAGE_LAYOUT_PRESENT_SRC_KHR,
		vk.ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0, vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, vk.PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT)
	check(vk.EndCommandBuffer(commands), "vkEndCommandBuffer")
}

// recreate makes a swapchain of the size the window has now, none while it is minimized
func recreate(c *context, s *swapchain) *swapchain {
	width, height := c.window.framebufferSize()
	for (width == 0 || height == 0) && !c.window.shouldClose() {
		waitEvents()
		width, height = c.window.framebufferSize()
	}

	check(vk.DeviceWaitIdle(c.device), "vkDeviceWaitIdle")
	created := createSwapchain(c, s.handle)
	destroySwapchain(c, s)
	return created
}

func main() {
	framesWanted := flag.Int64("frames", -1, "closes the window after this many frames")
	flag.Parse()
	useLoader()

	w, err := openWindow("{{build.name}}", 800, 600)
	check(err, "opening the window")
	c := &context{window: w}

	// what GLFW needs for its surface, and on macOS the portability of MoltenVK
	instanceExtensions := requiredExtensions()
	available, err := vk.EnumerateInstanceExtensionProperties("")
	check(err, "vkEnumerateInstanceExtensionProperties")
	instanceInfo := vk.InstanceCreateInfo{
		PApplicationInfo: &vk.ApplicationInfo{PApplicationName: "{{build.name}}", ApiVersion: vk.API_VERSION_1_3},
	}
	if hasExtension(available, vk.KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME) {
		instanceExtensions = append(instanceExtensions, vk.KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)
		instanceInfo.Flags = vk.InstanceCreateFlags(vk.INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
	}
	instanceInfo.PpEnabledExtensionNames = instanceExtensions
	c.instance, err = vk.CreateInstance(&instanceInfo, nil)
	check(err, "vkCreateInstance, is there a Vulkan driver")
	c.surface, err = w.createSurface(c.instance)
	check(err, "creating the surface")

	// a device of Vulkan 1.3, which always has dynamic rendering, with a queue that draws and presents to the window
	devices, err := vk.EnumeratePhysicalDevices(c.instance)
	check(err, "vkEnumeratePhysicalDevices")
	found := false
	for _, candidate := range devices {
		properties := vk.GetPhysicalDeviceProperties(candidate)
		if properties.ApiVersion < vk.API_VERSION_1_3 {
			continue
		}
		for index, family := range vk.GetPhysicalDeviceQueueFamilyProperties(candidate) {
			presents, err := vk.GetPhysicalDeviceSurfaceSupportKHR(candidate, uint32(index), c.surface)
			if err == nil && presents && family.QueueFlags&vk.QueueFlags(vk.QUEUE_GRAPHICS_BIT) != 0 {
				c.physical, c.family, found = candidate, uint32(index), true
				fmt.Println("device:", properties.DeviceName)
				break
			}
		}
		if found {
			break
		}
	}
	if !found {
		fmt.Fprintln(os.Stderr, "no device of Vulkan 1.3 draws into this window")
		os.Exit(1)
	}

	deviceAvailable, err := vk.EnumerateDeviceExtensionProperties(c.physical, "")
	check(err, "vkEnumerateDeviceExtensionProperties")
	deviceExtensions := []string{vk.KHR_SWAPCHAIN_EXTENSION_NAME}
	if hasExtension(deviceAvailable, "VK_KHR_portability_subset") {
		deviceExtensions = append(deviceExtensions, "VK_KHR_portability_subset")
	}
	enabled := vk.PhysicalDeviceVulkan13Features{DynamicRendering: true}
	c.device, err = vk.CreateDevice(c.physical, &vk.DeviceCreateInfo{
		PNext:                   unsafe.Pointer(enabled.Vulkanize()),
		PQueueCreateInfos:       []vk.DeviceQueueCreateInfo{{QueueFamilyIndex: c.family, PQueuePriorities: []float32{1}}},
		PpEnabledExtensionNames: deviceExtensions,
	}, nil)
	check(err, "vkCreateDevice")
	c.queue = vk.GetDeviceQueue(c.device, c.family, 0)

	chain := createSwapchain(c, vk.SwapchainKHR(vk.NULL_HANDLE))
	layout, err := vk.CreatePipelineLayout(c.device, &vk.PipelineLayoutCreateInfo{}, nil)
	check(err, "vkCreatePipelineLayout")
	pipeline := createPipeline(c.device, layout, chain.format)

	// what each frame in flight has of its own
	pool, err := vk.CreateCommandPool(c.device, &vk.CommandPoolCreateInfo{
		Flags:            vk.CommandPoolCreateFlags(vk.COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT),
		QueueFamilyIndex: c.family,
	}, nil)
	check(err, "vkCreateCommandPool")
	commands, err := vk.AllocateCommandBuffers(c.device, &vk.CommandBufferAllocateInfo{
		CommandPool:        pool,
		Level:              vk.COMMAND_BUFFER_LEVEL_PRIMARY,
		CommandBufferCount: framesInFlight,
	})
	check(err, "vkAllocateCommandBuffers")

	var acquired [framesInFlight]vk.Semaphore
	var done [framesInFlight]vk.Fence
	for frame := range framesInFlight {
		acquired[frame], err = vk.CreateSemaphore(c.device, &vk.SemaphoreCreateInfo{}, nil)
		check(err, "vkCreateSemaphore")
		done[frame], err = vk.CreateFence(c.device, &vk.FenceCreateInfo{Flags: vk.FenceCreateFlags(vk.FENCE_CREATE_SIGNALED_BIT)}, nil)
		check(err, "vkCreateFence")
	}

	frames := int64(0)
	for !w.shouldClose() && (*framesWanted < 0 || frames < *framesWanted) {
		pollEvents()
		frame := frames % framesInFlight
		check(vk.WaitForFences(c.device, []vk.Fence{done[frame]}, true, ^uint64(0)), "vkWaitForFences")

		image, err := vk.AcquireNextImageKHR(c.device, chain.handle, ^uint64(0), acquired[frame], vk.Fence(vk.NULL_HANDLE))
		if err == vk.ERROR_OUT_OF_DATE_KHR {
			chain = recreate(c, chain)
			continue
		}
		if err != vk.SUBOPTIMAL_KHR {
			check(err, "vkAcquireNextImageKHR")
		}

		check(vk.ResetFences(c.device, []vk.Fence{done[frame]}), "vkResetFences")
		check(vk.ResetCommandBuffer(commands[frame], 0), "vkResetCommandBuffer")
		record(commands[frame], chain, image, pipeline)

		check(vk.QueueSubmit(c.queue, []vk.SubmitInfo{{
			PWaitSemaphores:   []vk.Semaphore{acquired[frame]},
			PWaitDstStageMask: []vk.PipelineStageFlags{vk.PipelineStageFlags(vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)},
			PCommandBuffers:   []vk.CommandBuffer{commands[frame]},
			PSignalSemaphores: []vk.Semaphore{chain.rendered[image]},
		}}, done[frame]), "vkQueueSubmit")

		presenting := vk.QueuePresentKHR(c.queue, &vk.PresentInfoKHR{
			PWaitSemaphores: []vk.Semaphore{chain.rendered[image]},
			PSwapchains:     []vk.SwapchainKHR{chain.handle},
			PImageIndices:   []uint32{image},
		})
		frames++

		// a window of another size than the swapchain: GLFW changed it
		width, height := w.framebufferSize()
		if presenting == vk.ERROR_OUT_OF_DATE_KHR || presenting == vk.SUBOPTIMAL_KHR || width != chain.extent.Width || height != chain.extent.Height {
			chain = recreate(c, chain)
		} else {
			check(presenting, "vkQueuePresentKHR")
		}
	}

	check(vk.DeviceWaitIdle(c.device), "vkDeviceWaitIdle")
	fmt.Printf("rendered %d frames\n", frames)

	for frame := range framesInFlight {
		vk.DestroySemaphore(c.device, acquired[frame], nil)
		vk.DestroyFence(c.device, done[frame], nil)
	}
	vk.DestroyCommandPool(c.device, pool, nil)
	vk.DestroyPipeline(c.device, pipeline, nil)
	vk.DestroyPipelineLayout(c.device, layout, nil)
	destroySwapchain(c, chain)
	vk.DestroyDevice(c.device, nil)
	vk.DestroySurfaceKHR(c.instance, c.surface, nil)
	vk.DestroyInstance(c.instance, nil)
	w.close()
}

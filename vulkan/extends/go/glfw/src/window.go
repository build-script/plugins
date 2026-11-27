package main

// The window of GLFW, the port glfw3 of vcpkg: build_go gives cgo its headers and its library, like those of Vulkan

/*
#define GLFW_INCLUDE_VULKAN
#include <GLFW/glfw3.h>
#include <stdlib.h>

// GLFW takes the loader the program links, which go-vk loads as well
static void use_linked_loader(void) {
    glfwInitVulkanLoader(vkGetInstanceProcAddr);
}
*/
import "C"

import (
	"errors"
	"fmt"
	"runtime"
	"unsafe"

	"github.com/bbredesen/go-vk"
)

// GLFW of macOS wants the first thread of the process: main stays on it
func init() {
	runtime.LockOSThread()
}

type window struct {
	handle *C.GLFWwindow
}

func openWindow(title string, width, height int) (*window, error) {
	C.use_linked_loader()

	if C.glfwInit() == 0 {
		return nil, errors.New("GLFW found no display to open a window on")
	}
	if C.glfwVulkanSupported() == 0 {
		return nil, errors.New("GLFW found no Vulkan loader")
	}

	C.glfwWindowHint(C.GLFW_CLIENT_API, C.GLFW_NO_API)
	name := C.CString(title)
	defer C.free(unsafe.Pointer(name))

	handle := C.glfwCreateWindow(C.int(width), C.int(height), name, nil, nil)
	if handle == nil {
		return nil, errors.New("GLFW opened no window")
	}
	return &window{handle}, nil
}

func (w *window) shouldClose() bool {
	return C.glfwWindowShouldClose(w.handle) != 0
}

func (w *window) framebufferSize() (uint32, uint32) {
	var width, height C.int
	C.glfwGetFramebufferSize(w.handle, &width, &height)
	return uint32(width), uint32(height)
}

func pollEvents() {
	C.glfwPollEvents()
}

func waitEvents() {
	C.glfwWaitEvents()
}

// requiredExtensions are what the instance needs for surfaces of windows
func requiredExtensions() []string {
	var count C.uint32_t
	names := C.glfwGetRequiredInstanceExtensions(&count)
	extensions := []string{}
	for _, name := range unsafe.Slice(names, int(count)) {
		extensions = append(extensions, C.GoString(name))
	}
	return extensions
}

// createSurface makes the surface of the window for the instance of go-vk, whose handles are those of Vulkan
func (w *window) createSurface(instance vk.Instance) (vk.SurfaceKHR, error) {
	var surface C.VkSurfaceKHR
	result := C.glfwCreateWindowSurface(C.VkInstance(unsafe.Pointer(uintptr(instance))), w.handle, nil, &surface)
	if result != C.VK_SUCCESS {
		return 0, fmt.Errorf("glfwCreateWindowSurface: %d", int(result))
	}
	return vk.SurfaceKHR(uintptr(unsafe.Pointer(surface))), nil
}

func (w *window) close() {
	C.glfwDestroyWindow(w.handle)
	C.glfwTerminate()
}

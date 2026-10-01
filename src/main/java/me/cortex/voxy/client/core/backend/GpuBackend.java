package me.cortex.voxy.client.core.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;

import java.util.Locale;

//Voxy Vulkan only runs when minecraft itself is running on its Vulkan backend
public final class GpuBackend {
    private static Boolean VULKAN;

    public static boolean isVulkan() {
        if (VULKAN == null) {
            VULKAN = detect();
        }
        return VULKAN;
    }

    private static boolean detect() {
        var device = RenderSystem.getDevice();
        if (device instanceof FrontendGpuDevice frontend) {
            return frontend.backend instanceof VulkanDevice;
        }
        //Fallback on the reported backend name
        return device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan");
    }

    private GpuBackend() {}
}

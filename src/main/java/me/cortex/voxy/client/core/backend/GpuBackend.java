package me.cortex.voxy.client.core.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import me.cortex.voxy.common.Logger;

import java.util.Locale;

//Which graphics api minecraft is currently running on, voxy selects its rendering backend from this
public enum GpuBackend {
    OPENGL,
    VULKAN;

    private static GpuBackend CURRENT;

    public static GpuBackend current() {
        if (CURRENT == null) {
            CURRENT = detect();
            Logger.info("Voxy rendering backend: " + CURRENT);
        }
        return CURRENT;
    }

    public static boolean isVulkan() {
        return current() == VULKAN;
    }

    public static boolean isOpenGL() {
        return current() == OPENGL;
    }

    private static GpuBackend detect() {
        var device = RenderSystem.getDevice();
        if (device instanceof FrontendGpuDevice frontend) {
            var backend = frontend.backend;
            if (backend instanceof VulkanDevice) {
                return VULKAN;
            }
            return OPENGL;
        }
        //Fallback on the reported backend name
        String name = device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT);
        if (name.contains("vulkan")) {
            return VULKAN;
        }
        return OPENGL;
    }
}

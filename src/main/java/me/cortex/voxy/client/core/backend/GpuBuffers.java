package me.cortex.voxy.client.core.backend;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.vulkan.VkGpuBuffer;

//Creates buffers for the active backend, for use by shared (cpu side) systems
public final class GpuBuffers {
    //Zero initialized, same as new GlBuffer(size)
    public static IGpuBuffer create(long size) {
        if (GpuBackend.isVulkan()) {
            return new VkGpuBuffer(size);
        }
        return new GlBuffer(size);
    }

    public static IGpuBuffer createUninitialized(long size) {
        if (GpuBackend.isVulkan()) {
            return new VkGpuBuffer(size, false);
        }
        return new GlBuffer(size, false);
    }

    private GpuBuffers() {}
}

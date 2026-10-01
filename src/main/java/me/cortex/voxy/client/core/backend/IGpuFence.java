package me.cortex.voxy.client.core.backend;

//A point in the gpu command stream, GlFence on OpenGL and a minecraft submit fence on Vulkan
public interface IGpuFence {
    boolean signaled();

    void free();
}

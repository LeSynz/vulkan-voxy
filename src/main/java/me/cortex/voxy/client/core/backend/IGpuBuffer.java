package me.cortex.voxy.client.core.backend;

//A gpu buffer on either backend (GlBuffer on OpenGL, VkGpuBuffer on Vulkan). Shared (cpu side) systems only
// need the size and lifetime, binding is done by backend specific code.
public interface IGpuBuffer {
    long size();

    void free();

    void assertNotFreed();

    IGpuBuffer zero();

    IGpuBuffer zeroRange(long offset, long size);

    IGpuBuffer fill(int data);
}

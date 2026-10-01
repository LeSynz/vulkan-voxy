package me.cortex.voxy.client.core.vulkan;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

//Cache of VkSamplers, replaces glGenSamplers + glSamplerParameteri
public final class VkSamplers {
    private static final Long2LongOpenHashMap CACHE = new Long2LongOpenHashMap();

    //maxLod < 0 means VK_LOD_CLAMP_NONE
    public static long get(int minFilter, int magFilter, int mipmapMode, boolean clampToEdge, float maxLod) {
        long key = minFilter | ((long) magFilter << 4) | ((long) mipmapMode << 8) | ((clampToEdge ? 1L : 0L) << 12) | ((long) Float.floatToIntBits(maxLod) << 16);
        long sampler = CACHE.get(key);
        if (sampler != 0) {
            return sampler;
        }
        try (var stack = MemoryStack.stackPush()) {
            int address = clampToEdge ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT;
            var sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(magFilter)
                    .minFilter(minFilter)
                    .mipmapMode(mipmapMode)
                    .addressModeU(address)
                    .addressModeV(address)
                    .addressModeW(address)
                    .minLod(0)
                    .maxLod(maxLod < 0 ? VK_LOD_CLAMP_NONE : maxLod)
                    .compareEnable(false)
                    .anisotropyEnable(false);
            var p = stack.mallocLong(1);
            VkContext.check(vkCreateSampler(VkContext.get().vkDevice, sci, null, p), "vkCreateSampler");
            sampler = p.get(0);
        }
        CACHE.put(key, sampler);
        return sampler;
    }

    //GL_NEAREST / GL_NEAREST, no mips
    public static long nearestClamp() {
        return get(VK_FILTER_NEAREST, VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST, true, 0);
    }

    //GL_NEAREST_MIPMAP_NEAREST / GL_NEAREST
    public static long nearestMipNearestClamp() {
        return get(VK_FILTER_NEAREST, VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST, true, -1);
    }

    //GL_LINEAR / GL_LINEAR, clamp to edge
    public static long linearClamp() {
        return get(VK_FILTER_LINEAR, VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, true, 0);
    }

    public static void destroyAll() {
        var dev = VkContext.get().vkDevice;
        for (long s : CACHE.values()) {
            vkDestroySampler(dev, s, null);
        }
        CACHE.clear();
    }

    private VkSamplers() {}
}

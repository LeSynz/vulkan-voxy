package me.cortex.voxy.client.core.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;

import static org.lwjgl.vulkan.VK10.*;

public final class VkFormats {
    public static boolean isDepth(int format) {
        return switch (format) {
            case VK_FORMAT_D16_UNORM, VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D32_SFLOAT, VK_FORMAT_D16_UNORM_S8_UINT,
                 VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT_S8_UINT -> true;
            default -> false;
        };
    }

    public static boolean hasStencil(int format) {
        return format == VK_FORMAT_D16_UNORM_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT || format == VK_FORMAT_D32_SFLOAT_S8_UINT;
    }

    public static boolean isInteger(int format) {
        return switch (format) {
            case VK_FORMAT_R32_UINT, VK_FORMAT_R32_SINT, VK_FORMAT_R8G8B8A8_UINT -> true;
            default -> false;
        };
    }

    public static long texelSize(int format) {
        return switch (format) {
            case VK_FORMAT_D32_SFLOAT_S8_UINT -> 8;
            case VK_FORMAT_D16_UNORM -> 2;
            default -> 4;
        };
    }

    private static int optimalFeatures(int format) {
        try (var stack = MemoryStack.stackPush()) {
            var props = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(VkContext.get().physicalDevice, format, props);
            return props.optimalTilingFeatures();
        }
    }

    public static boolean supportsLinearFilter(int format) {
        return (optimalFeatures(format) & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0;
    }

    public static boolean supportsStorage(int format) {
        return (optimalFeatures(format) & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0;
    }

    private static boolean supportsDepthAttachment(int format) {
        int f = optimalFeatures(format);
        return (f & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0 && (f & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0;
    }

    private static int depthStencil = -1;
    //GL_DEPTH24_STENCIL8, AMD does not support D24S8 so fall back to D32S8
    public static int depthStencil() {
        if (depthStencil == -1) {
            depthStencil = supportsDepthAttachment(VK_FORMAT_D24_UNORM_S8_UINT) ? VK_FORMAT_D24_UNORM_S8_UINT : VK_FORMAT_D32_SFLOAT_S8_UINT;
        }
        return depthStencil;
    }

    private static int depth24 = -1;
    //GL_DEPTH_COMPONENT24
    public static int depth24() {
        if (depth24 == -1) {
            depth24 = supportsDepthAttachment(VK_FORMAT_X8_D24_UNORM_PACK32) ? VK_FORMAT_X8_D24_UNORM_PACK32 : VK_FORMAT_D32_SFLOAT;
        }
        return depth24;
    }

    private VkFormats() {}
}

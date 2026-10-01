package me.cortex.voxy.client.core.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.backend.vulkan.Destroyable;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

//Wraps minecraft's own vulkan device, voxy never creates its own device/queue, everything is recorded into
// command buffers that are spliced into minecraft's command stream
public final class VkContext {
    private static VkContext INSTANCE;

    public static VkContext get() {
        if (INSTANCE == null) {
            throw new IllegalStateException("Vulkan context not initialized");
        }
        return INSTANCE;
    }

    public static boolean isInitialized() {
        return INSTANCE != null;
    }

    public static VkContext init() {
        if (INSTANCE == null) {
            INSTANCE = new VkContext();
        }
        return INSTANCE;
    }

    public final VulkanDevice device;
    public final VkDevice vkDevice;
    public final VkPhysicalDevice physicalDevice;
    public final long vma;
    public final VulkanQueue queue;
    public final VulkanCommandEncoder encoder;
    public final FrontendCommandEncoder frontendEncoder;
    public final VkCommandStream stream;

    public final boolean voxyFeaturesEnabled;
    public final boolean wireframeSupported;

    public final int vendorId;
    public final int driverId;
    public final String deviceName;
    public final String driverName;
    public final long maxStorageBufferRange;
    public final int minStorageBufferOffsetAlignment;
    public final int minUniformBufferOffsetAlignment;
    public final int maxPushConstantsSize;
    public final long maxMemoryAllocationSize;
    public final float timestampPeriod;
    public final int subgroupSize;
    public final int subgroupSupportedOperations;
    public final int subgroupSupportedStages;
    public final long deviceLocalHeapSize;

    private VkContext() {
        var frontend = (FrontendGpuDevice) RenderSystem.getDevice();
        this.device = (VulkanDevice) frontend.backend;
        this.vkDevice = this.device.vkDevice();
        this.physicalDevice = this.vkDevice.getPhysicalDevice();
        this.vma = this.device.vma();
        this.queue = this.device.graphicsQueue();
        this.frontendEncoder = (FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder();
        this.encoder = (VulkanCommandEncoder) this.frontendEncoder.backend();

        this.voxyFeaturesEnabled = VoxyVulkanFeatures.isEnabled(this.device);
        this.wireframeSupported = this.device.enabledFeatures.contains(VulkanFeatureSets.WIREFRAME_FEATURESET);

        try (var stack = MemoryStack.stackPush()) {
            var subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
            var maintenance3 = VkPhysicalDeviceMaintenance3Properties.calloc(stack).sType$Default();
            var driver = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default();
            var props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default()
                    .pNext(subgroup)
                    .pNext(maintenance3)
                    .pNext(driver);
            vkGetPhysicalDeviceProperties2(this.physicalDevice, props2);
            var props = props2.properties();
            var limits = props.limits();

            this.vendorId = props.vendorID();
            this.deviceName = props.deviceNameString();
            this.driverId = driver.driverID();
            this.driverName = driver.driverNameString() + " " + driver.driverInfoString();
            this.maxStorageBufferRange = Integer.toUnsignedLong(limits.maxStorageBufferRange());
            this.minStorageBufferOffsetAlignment = (int) limits.minStorageBufferOffsetAlignment();
            this.minUniformBufferOffsetAlignment = (int) limits.minUniformBufferOffsetAlignment();
            this.maxPushConstantsSize = limits.maxPushConstantsSize();
            this.timestampPeriod = limits.timestampPeriod();
            this.maxMemoryAllocationSize = maintenance3.maxMemoryAllocationSize();
            this.subgroupSize = subgroup.subgroupSize();
            this.subgroupSupportedOperations = subgroup.supportedOperations();
            this.subgroupSupportedStages = subgroup.supportedStages();

            var memProps = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(this.physicalDevice, memProps);
            long heap = 0;
            for (int i = 0; i < memProps.memoryHeapCount(); i++) {
                var h = memProps.memoryHeaps(i);
                if ((h.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    heap = Math.max(heap, h.size());
                }
            }
            this.deviceLocalHeapSize = heap;
        }

        this.stream = new VkCommandStream(this);

        Logger.info("Voxy vulkan context: " + this.deviceName + " (" + this.driverName + "), voxy feature set enabled: " + this.voxyFeaturesEnabled
                + ", maxStorageBufferRange: " + this.maxStorageBufferRange + ", maxMemoryAllocationSize: " + this.maxMemoryAllocationSize
                + ", subgroupSize: " + this.subgroupSize);
    }

    public boolean isNvidia() {
        return this.vendorId == 0x10DE;
    }

    public boolean isAmd() {
        return this.vendorId == 0x1002;
    }

    public boolean isIntel() {
        return this.vendorId == 0x8086;
    }

    public boolean isMesa() {
        return switch (this.driverId) {
            case VK12.VK_DRIVER_ID_MESA_RADV, VK12.VK_DRIVER_ID_INTEL_OPEN_SOURCE_MESA, VK12.VK_DRIVER_ID_MESA_LLVMPIPE,
                 VK12.VK_DRIVER_ID_MESA_TURNIP, VK12.VK_DRIVER_ID_MESA_V3DV, VK12.VK_DRIVER_ID_MESA_PANVK,
                 VK12.VK_DRIVER_ID_MESA_VENUS, VK12.VK_DRIVER_ID_MESA_DOZEN, VK12.VK_DRIVER_ID_MESA_NVK,
                 VK12.VK_DRIVER_ID_MESA_HONEYKRISP -> true;
            default -> false;
        };
    }

    public boolean hasSubgroupArithmeticInCompute() {
        return (this.subgroupSupportedStages & VK_SHADER_STAGE_COMPUTE_BIT) != 0
                && (this.subgroupSupportedOperations & VK_SUBGROUP_FEATURE_BASIC_BIT) != 0
                && (this.subgroupSupportedOperations & VK_SUBGROUP_FEATURE_ARITHMETIC_BIT) != 0;
    }

    //Fence that signals once everything recorded into minecraft's current submission so far has completed
    public GpuFence createFence() {
        return this.frontendEncoder.createFence();
    }

    //Destroy once minecraft knows the gpu can no longer be using the object
    public void destroyLater(Destroyable destroyable) {
        this.encoder.queueForDestroy(destroyable);
    }

    public void setObjectName(int objectType, long handle, String name) {
        this.device.instance().debug().setObjectName(this.vkDevice, objectType, handle, name);
    }

    public static void check(int result, String what) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException("Vulkan call failed (" + result + "): " + what);
        }
    }
}

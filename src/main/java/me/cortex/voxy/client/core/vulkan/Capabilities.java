package me.cortex.voxy.client.core.vulkan;

//What the device voxy runs on supports, queried from minecraft's own vulkan device
public class Capabilities {
    public static final Capabilities INSTANCE = new Capabilities();

    public final boolean compute = true;
    public final boolean indirectParameters;//drawIndirectCount, part of the voxy feature set
    public final boolean INT64_t;//shaderInt64, part of the voxy feature set
    public final boolean subgroup;
    public final long ssboMaxSize;
    public final int ssboBindingAlignment;
    public final boolean isMesa;
    public final boolean isIntel;
    public final boolean isNvidia;
    public final boolean isAmd;

    public Capabilities() {
        var vk = VkContext.init();
        this.indirectParameters = vk.voxyFeaturesEnabled;
        this.INT64_t = vk.voxyFeaturesEnabled;
        this.subgroup = vk.hasSubgroupArithmeticInCompute();
        this.ssboMaxSize = vk.maxStorageBufferRange;
        this.ssboBindingAlignment = vk.minStorageBufferOffsetAlignment;
        this.isMesa = vk.isMesa();
        this.isIntel = vk.isIntel();
        this.isNvidia = vk.isNvidia();
        this.isAmd = vk.isAmd();
    }

    public static void init() {
    }
}

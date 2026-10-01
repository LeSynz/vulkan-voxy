package me.cortex.voxy.client.core.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;

import java.util.Set;

//Device features voxy needs on top of what minecraft requires. Registered as an *optional* feature set
// (see MixinVulkanFeatureSets) so minecraft enables them itself when supported and never fails device
// creation because of voxy.
public final class VoxyVulkanFeatures {
    public static final FeatureSet FEATURE_SET = new FeatureSet(
            "Voxy LoD renderer",
            Set.of(),
            Set.of(
                    //glMultiDrawElementsIndirectCount
                    new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "drawIndirectCount"),
                    //Occlusion cull raster writes visibility from the fragment shader
                    new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "fragmentStoresAndAtomics"),
                    //Storage buffers reachable from vertex shaders
                    new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "vertexPipelineStoresAndAtomics"),
                    //uint64_t quad format
                    new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderInt64")
            )
    );

    public static boolean isEnabled(VulkanDevice device) {
        return device.enabledFeatures.contains(FEATURE_SET);
    }

    private VoxyVulkanFeatures() {}
}

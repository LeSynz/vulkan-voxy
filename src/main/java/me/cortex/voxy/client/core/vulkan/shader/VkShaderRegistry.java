package me.cortex.voxy.client.core.vulkan.shader;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.hierachical.VkNodeCleaner;
import me.cortex.voxy.client.core.vulkan.render.VkHiZBuffer;
import me.cortex.voxy.client.core.vulkan.render.VkHierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.vulkan.render.VkSectionRenderer;
import me.cortex.voxy.client.core.rendering.hierachical.VkNodeGpuWriter;

import java.util.LinkedHashMap;
import java.util.Map;

//Every shader permutation the vulkan backend can compile, used by the offline SPIR-V validation test
// (src/test/.../VulkanShaderValidationTest) so that each one is checked with glslangValidator and spirv-val
public final class VkShaderRegistry {
    public static Map<String, VkShaderBuilder> allPermutations() {
        var out = new LinkedHashMap<String, VkShaderBuilder>();
        out.put("node_scatter", VkNodeGpuWriter.scatterShader());
        out.put("node_memcpy", VkNodeGpuWriter.memcpyShader());
        out.put("cleaner_sort", VkNodeCleaner.sorterShader());
        out.put("cleaner_result_transformer", VkNodeCleaner.resultTransformerShader());
        out.put("cleaner_batch_clear", VkNodeCleaner.batchClearShader());
        float[] tints = {1.0f, 0.5f, 0.8f, 0.6f};
        //Minecraft's vulkan backend always uses zero to one depth, reverse z depends on minecraft's depth state
        for (boolean reverseZ : new boolean[]{false, true}) {
            var props = new RenderProperties(true, reverseZ, false);
            String p = reverseZ ? "_revz" : "";
            out.put("hiz" + p, VkHiZBuffer.hizShader(props));
            out.put("terrain_opaque" + p, VkSectionRenderer.terrainShader(props, tints, false));
            out.put("terrain_translucent" + p, VkSectionRenderer.terrainShader(props, tints, true));
            out.put("section_cull" + p, VkSectionRenderer.cullShader(props));
            out.put("traversal" + p, VkHierarchicalOcclusionTraverser.traversalShader(props, false));
            out.put("traversal_stats" + p, VkHierarchicalOcclusionTraverser.traversalShader(props, true));
        }
        out.put("cmdgen", VkSectionRenderer.commandGenShader(false));
        out.put("cmdgen_stats", VkSectionRenderer.commandGenShader(true));
        out.put("prep", VkSectionRenderer.prepShader());
        out.put("prefixsum_subgroup", VkSectionRenderer.prefixSumShader(true));
        out.put("prefixsum_simple", VkSectionRenderer.prefixSumShader(false));
        out.put("translucent_gen", VkSectionRenderer.translucentGenShader());
        return out;
    }

    private VkShaderRegistry() {}
}

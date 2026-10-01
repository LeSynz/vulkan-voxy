package me.cortex.voxy.client.core.vulkan.shader;

import me.cortex.voxy.client.core.rendering.hierachical.VkNodeCleaner;
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
        return out;
    }

    private VkShaderRegistry() {}
}

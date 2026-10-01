package me.cortex.voxy.vulkan;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vulkan.shader.SpirvReflection;
import me.cortex.voxy.client.core.vulkan.shader.VkShaderCompiler;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.VK10.*;

//Voxy relies on glslang shifting *explicit* bindings per resource kind (GL has separate binding namespaces)
public class ShadercBindingShiftTest {
    @Test
    public void explicitBindingsAreShiftedPerKind() {
        String src = """
                #version 460
                layout(local_size_x=32) in;
                layout(binding=0, std140) uniform U { uint a; };
                layout(binding=0, std430) buffer S { uint b[]; };
                layout(binding=0) uniform sampler2D tex;
                layout(binding=0, r32f) uniform writeonly image2D img;
                layout(push_constant) uniform PC { uint x; uint y; };
                void main(){ b[gl_LocalInvocationIndex] = a + x + y + uint(texelFetch(tex, ivec2(0),0).x); imageStore(img, ivec2(0), vec4(1)); }
                """;
        var spv = VkShaderCompiler.compile("test.comp", src, ShaderType.COMPUTE);
        try {
            var res = SpirvReflection.reflect(spv);
            var map = new HashMap<Integer, Integer>();
            for (var b : res.bindings()) {map.put(b.binding(), b.descriptorType()); System.out.println("BINDING " + b);}
            assertEquals(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, map.get(VkShaderCompiler.SSBO_BINDING_BASE));
            assertEquals(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, map.get(VkShaderCompiler.UBO_BINDING_BASE));
            assertEquals(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, map.get(VkShaderCompiler.TEXTURE_BINDING_BASE));
            assertEquals(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, map.get(VkShaderCompiler.IMAGE_BINDING_BASE));
            assertEquals(4, map.size());
            assertEquals(8, res.pushConstantSize());
        } finally {
            MemoryUtil.memFree(spv);
        }
    }
}

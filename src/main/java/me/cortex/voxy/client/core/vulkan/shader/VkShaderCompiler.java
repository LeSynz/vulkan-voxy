package me.cortex.voxy.client.core.vulkan.shader;

import me.cortex.voxy.client.core.vulkan.shader.ShaderType;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.regex.Pattern;

import static org.lwjgl.util.shaderc.Shaderc.*;

//GLSL -> SPIR-V with shaderc (shipped by minecraft). Voxy's shaders are written against GL where SSBOs, UBOs,
// samplers and images have separate binding namespaces, vulkan has a single namespace per descriptor set, so
// the bindings are moved into disjoint ranges after compilation (SpirvBindingRemapper):
//   SSBO: binding + 0, UBO: binding + UBO_BINDING_BASE, sampler2D: binding + TEXTURE_BINDING_BASE, image: binding + IMAGE_BINDING_BASE
public final class VkShaderCompiler {
    public static final int SSBO_BINDING_BASE = 0;
    public static final int UBO_BINDING_BASE = 32;
    public static final int TEXTURE_BINDING_BASE = 64;
    public static final int IMAGE_BINDING_BASE = 96;

    private static final Pattern VERTEX_ID = Pattern.compile("\\bgl_VertexID\\b");
    private static final Pattern INSTANCE_ID = Pattern.compile("\\bgl_InstanceID\\b");
    private static final Pattern BASE_INSTANCE_ARB = Pattern.compile("\\bgl_BaseInstanceARB\\b");
    private static final Pattern DRAW_ID_ARB = Pattern.compile("\\bgl_DrawIDARB\\b");
    //Debug printf statements (rewritten by PrintfInjector on GL), not supported on vulkan
    private static final Pattern PRINTF = Pattern.compile("\\bprintf\\s*\\(\"[^\"]*\"[^;]*\\);");

    public static int shadercKind(ShaderType type) {
        return switch (type) {
            case VERTEX -> shaderc_glsl_vertex_shader;
            case FRAGMENT -> shaderc_glsl_fragment_shader;
            case COMPUTE -> shaderc_glsl_compute_shader;
            default -> throw new IllegalArgumentException("Shader type not supported on vulkan: " + type);
        };
    }

    //GL built ins that are named differently in vulkan GLSL
    public static String translateBuiltins(String src) {
        src = VERTEX_ID.matcher(src).replaceAll("gl_VertexIndex");
        //Vulkan's gl_InstanceIndex includes the base instance, GL's gl_InstanceID does not
        src = INSTANCE_ID.matcher(src).replaceAll("(gl_InstanceIndex-gl_BaseInstance)");
        src = BASE_INSTANCE_ARB.matcher(src).replaceAll("gl_BaseInstance");
        src = DRAW_ID_ARB.matcher(src).replaceAll("gl_DrawID");
        src = PRINTF.matcher(src).replaceAll(";");//No comment marker: the call may itself be inside a block comment
        return src;
    }

    public static final class CompileException extends RuntimeException {
        public final String source;
        public CompileException(String message, String source) {
            super(message);
            this.source = source;
        }
    }

    private static long COMPILER = 0;
    private static synchronized long compiler() {
        if (COMPILER == 0) {
            COMPILER = shaderc_compiler_initialize();
            if (COMPILER == 0) {
                throw new IllegalStateException("Failed to initialize shaderc");
            }
        }
        return COMPILER;
    }

    //Returns a MemoryUtil allocated buffer containing the SPIR-V, the caller must free it with MemoryUtil.memFree
    public static synchronized ByteBuffer compile(String name, String source, ShaderType type) {
        long options = shaderc_compile_options_initialize();
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            shaderc_compile_options_set_auto_map_locations(options, true);
            shaderc_compile_options_set_auto_bind_uniforms(options, false);

            long result = shaderc_compile_into_spv(compiler(), source, shadercKind(type), name, "main", options);
            if (result == 0) {
                throw new CompileException("shaderc returned no result for " + name, source);
            }
            try {
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    throw new CompileException("Failed to compile " + type + " shader " + name + ":\n" + shaderc_result_get_error_message(result), source);
                }
                var bytes = shaderc_result_get_bytes(result);
                var out = MemoryUtil.memAlloc(bytes.remaining());
                MemoryUtil.memCopy(bytes, out);
                SpirvBindingRemapper.remap(out);
                return out;
            } finally {
                shaderc_result_release(result);
            }
        } finally {
            shaderc_compile_options_release(options);
        }
    }

    private VkShaderCompiler() {}
}

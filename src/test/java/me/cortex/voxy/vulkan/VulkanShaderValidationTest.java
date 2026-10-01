package me.cortex.voxy.vulkan;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vulkan.shader.SpirvReflection;
import me.cortex.voxy.client.core.vulkan.shader.VkShaderRegistry;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

//Phase 1 step 4: every vulkan shader permutation is compiled to SPIR-V (with shaderc, exactly as at runtime, and
// independently with glslangValidator) and validated with spirv-val. Outputs are left in build/vk-shaders.
public class VulkanShaderValidationTest {
    private static final Path OUT = Path.of("build", "vk-shaders");

    private static String ext(ShaderType type) {
        return switch (type) {
            case VERTEX -> "vert";
            case FRAGMENT -> "frag";
            case COMPUTE -> "comp";
            default -> throw new IllegalArgumentException();
        };
    }

    private static boolean hasTool(String tool) {
        try {
            var p = new ProcessBuilder(tool, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    private static String run(List<String> cmd) throws IOException, InterruptedException {
        var p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "timeout";
        }
        return p.exitValue() == 0 ? null : out;
    }

    @Test
    public void allVulkanShadersCompileAndValidate() throws Exception {
        Files.createDirectories(OUT);
        boolean glslang = hasTool("glslangValidator");
        boolean spirvVal = hasTool("spirv-val");
        System.out.println("glslangValidator: " + glslang + ", spirv-val: " + spirvVal);

        List<String> failures = new ArrayList<>();
        int count = 0;
        for (var entry : VkShaderRegistry.allPermutations().entrySet()) {
            for (boolean intel : new boolean[]{false, true}) {
                String name = entry.getKey() + (intel ? "_intel" : "");
                var builder = entry.getValue();
                //Runtime path (shaderc + binding remap + reflection)
                for (var stage : builder.stages()) {
                    Files.writeString(OUT.resolve(name + "." + ext(stage)), builder.processedSource(stage, intel));
                }
                java.util.Map<ShaderType, java.nio.ByteBuffer> spirv;
                try {
                    spirv = builder.compileSpirv(intel);
                } catch (RuntimeException e) {
                    failures.add(name + ": shaderc: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                    continue;
                }
                try {
                    for (var stage : spirv.entrySet()) {
                        count++;
                        String base = name + "." + ext(stage.getKey());
                        var srcPath = OUT.resolve(base);
                        var spvPath = OUT.resolve(base + ".spv");
                        byte[] bytes = new byte[stage.getValue().remaining()];
                        stage.getValue().duplicate().get(bytes);
                        Files.write(spvPath, bytes);
                        var refl = SpirvReflection.reflect(stage.getValue());
                        var sb = new StringBuilder();
                        for (var b : refl.bindings()) sb.append(b.binding()).append(' ').append(b.descriptorType()).append(' ').append(b.name()).append('\n');
                        sb.append("push ").append(refl.pushConstantSize()).append('\n');
                        Files.writeString(OUT.resolve(base + ".bindings.txt"), sb.toString());

                        if (spirvVal) {
                            String err = run(List.of("spirv-val", "--target-env", "vulkan1.2", spvPath.toString()));
                            if (err != null) failures.add(base + " (shaderc): spirv-val: " + err);
                        }
                        if (glslang) {
                            var glsPath = OUT.resolve(base + ".glslang.spv");
                            String err = run(List.of("glslangValidator", "-V", "--target-env", "vulkan1.2", "--aml",
                                    "-S", ext(stage.getKey()), "-o", glsPath.toString(), srcPath.toString()));
                            if (err != null) {
                                failures.add(base + ": glslangValidator: " + err);
                            } else if (spirvVal) {
                                err = run(List.of("spirv-val", "--target-env", "vulkan1.2", glsPath.toString()));
                                if (err != null) failures.add(base + " (glslang): spirv-val: " + err);
                            }
                        }
                    }
                } finally {
                    spirv.values().forEach(MemoryUtil::memFree);
                }
            }
        }
        System.out.println("Validated " + count + " vulkan shader stages");
        if (!failures.isEmpty()) {
            fail(String.join("\n\n", failures));
        }
        assertTrue(count > 0);
    }
}

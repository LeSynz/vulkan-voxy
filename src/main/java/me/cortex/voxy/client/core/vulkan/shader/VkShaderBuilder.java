package me.cortex.voxy.client.core.vulkan.shader;

import me.cortex.voxy.client.core.vulkan.shader.ShaderLoader;
import me.cortex.voxy.client.core.vulkan.shader.ShaderType;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

//Vulkan counterpart of Shader.Builder: same #import/#define/replacement processing, compiled to SPIR-V
public class VkShaderBuilder {
    final Map<String, String> defines = new HashMap<>();
    final Map<String, String> replacements = new LinkedHashMap<>();
    private final Map<ShaderType, String> sources = new EnumMap<>(ShaderType.class);
    private String name = "voxy";

    public static VkShaderBuilder make() {
        return new VkShaderBuilder();
    }

    public VkShaderBuilder clone() {
        var clone = new VkShaderBuilder();
        clone.defines.putAll(this.defines);
        clone.sources.putAll(this.sources);
        clone.replacements.putAll(this.replacements);
        clone.name = this.name;
        return clone;
    }

    public VkShaderBuilder name(String name) {
        this.name = name;
        return this;
    }

    public VkShaderBuilder define(String name) {
        this.defines.put(name, "");
        return this;
    }

    public VkShaderBuilder defineIf(String name, boolean condition) {
        if (condition) {
            this.defines.put(name, "");
        }
        return this;
    }

    public VkShaderBuilder defineIf(String name, boolean condition, int value) {
        if (condition) {
            this.defines.put(name, Integer.toString(value));
        }
        return this;
    }

    public VkShaderBuilder define(String name, int value) {
        this.defines.put(name, Integer.toString(value));
        return this;
    }

    public VkShaderBuilder define(String name, float value) {
        this.defines.put(name, Float.toString(value) + "f");
        return this;
    }

    public VkShaderBuilder define(String name, String value) {
        this.defines.put(name, value);
        return this;
    }

    public VkShaderBuilder replace(String value, String replacement) {
        this.replacements.put(value, replacement);
        return this;
    }

    public VkShaderBuilder add(ShaderType type, String id) {
        if (this.name.equals("voxy")) {
            this.name = id;
        }
        return this.addSource(type, ShaderLoader.parse(id));
    }

    public VkShaderBuilder addSource(ShaderType type, String source) {
        this.sources.put(type, source);
        return this;
    }

    public VkShaderBuilder apply(Consumer<VkShaderBuilder> applyer) {
        applyer.accept(this);
        return this;
    }

    public Set<ShaderType> stages() {
        return this.sources.keySet();
    }

    public String getDefine(String name) {
        return this.defines.get(name);
    }

    //The exact source handed to shaderc for a stage
    public String processedSource(ShaderType type, boolean isIntel) {
        String src = this.sources.get(type);
        var defs = new LinkedHashMap<>(this.defines);
        if (isIntel) defs.put("IS_INTEL", "");
        if (ThreadUtils.isWindows) defs.put("IS_WINDOWS", "");
        String defStr = defs.entrySet().stream().map(a -> "#define " + a.getKey() + " " + a.getValue() + "\n").collect(Collectors.joining());
        src = src.substring(0, src.indexOf('\n') + 1) + defStr + src.substring(src.indexOf('\n') + 1);
        for (var replacement : this.replacements.entrySet()) {
            src = src.replace(replacement.getKey(), replacement.getValue());
        }
        return VkShaderCompiler.translateBuiltins(src);
    }

    //Compiles every stage, the returned buffers must be freed with MemoryUtil.memFree
    public Map<ShaderType, ByteBuffer> compileSpirv(boolean isIntel) {
        Map<ShaderType, ByteBuffer> out = new EnumMap<>(ShaderType.class);
        try {
            for (var type : this.sources.keySet()) {
                String src = this.processedSource(type, isIntel);
                String key = VkShaderCache.key(src, type);
                var cached = VkShaderCache.loadSpirv(key);
                if (cached != null) {
                    VkShaderCache.spirvHits++;
                    out.put(type, cached);
                    continue;
                }
                VkShaderCache.spirvMisses++;
                try {
                    long start = System.nanoTime();
                    var compiled = VkShaderCompiler.compile(this.name + "." + type.name().toLowerCase(Locale.ROOT), src, type);
                    VkShaderCache.spirvCompileNs += System.nanoTime() - start;
                    VkShaderCache.storeSpirv(key, compiled);
                    out.put(type, compiled);
                } catch (VkShaderCompiler.CompileException e) {
                    Logger.error(e.getMessage());
                    try {
                        Files.writeString(Path.of("SHADER_DUMP.txt"), src);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                    throw new RuntimeException("Shader compilation failed of type " + type.name() + ", see log for details, dumped shader", e);
                }
            }
        } catch (RuntimeException e) {
            out.values().forEach(MemoryUtil::memFree);
            throw e;
        }
        return out;
    }

    public VkComputePipeline compileCompute() {
        if (this.sources.size() != 1 || !this.sources.containsKey(ShaderType.COMPUTE)) {
            throw new IllegalStateException("Compute pipeline needs exactly one compute stage");
        }
        return new VkComputePipeline(this);
    }

    public VkGraphicsPipeline compileGraphics(VkGraphicsState state) {
        return new VkGraphicsPipeline(this, state);
    }
}

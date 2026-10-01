package me.cortex.voxy.client.core.vulkan.shader;

public interface IShaderProcessor {
    String process(ShaderType type, String source);
}

package me.cortex.voxy.client.core.vulkan.shader;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.util.spvc.Spvc.*;
import static org.lwjgl.vulkan.VK10.*;

//Descriptor/push constant reflection with SPIRV-Cross (lwjgl-spvc, shipped by minecraft)
public final class SpirvReflection {
    private static final int SPV_DECORATION_BINDING = 33;
    private static final int SPV_DECORATION_DESCRIPTOR_SET = 34;

    public record Binding(int binding, int descriptorType, String name) {}
    public record Result(List<Binding> bindings, int pushConstantSize) {}

    public static Result reflect(ByteBuffer spirv) {
        try (var stack = MemoryStack.stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            check(spvc_context_create(p), 0, "spvc_context_create");
            long ctx = p.get(0);
            try {
                var words = spirv.asIntBuffer();
                check(spvc_context_parse_spirv(ctx, words, words.remaining(), p), ctx, "parse");
                long ir = p.get(0);
                check(spvc_context_create_compiler(ctx, SPVC_BACKEND_NONE, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, p), ctx, "create compiler");
                long compiler = p.get(0);
                check(spvc_compiler_create_shader_resources(compiler, p), ctx, "create resources");
                long resources = p.get(0);

                List<Binding> bindings = new ArrayList<>();
                collect(compiler, resources, SPVC_RESOURCE_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, bindings, ctx);
                collect(compiler, resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, bindings, ctx);
                collect(compiler, resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, bindings, ctx);
                collect(compiler, resources, SPVC_RESOURCE_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, bindings, ctx);

                int pushSize = 0;
                {
                    PointerBuffer list = stack.mallocPointer(1);
                    PointerBuffer count = stack.mallocPointer(1);
                    check(spvc_resources_get_resource_list_for_type(resources, SPVC_RESOURCE_TYPE_PUSH_CONSTANT, list, count), ctx, "push constants");
                    var res = SpvcReflectedResource.create(list.get(0), (int) count.get(0));
                    for (int i = 0; i < res.remaining(); i++) {
                        long type = spvc_compiler_get_type_handle(compiler, res.get(i).base_type_id());
                        PointerBuffer size = stack.mallocPointer(1);
                        check(spvc_compiler_get_declared_struct_size(compiler, type, size), ctx, "struct size");
                        pushSize = Math.max(pushSize, (int) size.get(0));
                    }
                }
                return new Result(bindings, pushSize);
            } finally {
                spvc_context_destroy(ctx);
            }
        }
    }

    private static void collect(long compiler, long resources, int type, int descriptorType, List<Binding> out, long ctx) {
        try (var stack = MemoryStack.stackPush()) {
            PointerBuffer list = stack.mallocPointer(1);
            PointerBuffer count = stack.mallocPointer(1);
            check(spvc_resources_get_resource_list_for_type(resources, type, list, count), ctx, "resource list");
            int n = (int) count.get(0);
            if (n == 0) return;
            var res = SpvcReflectedResource.create(list.get(0), n);
            for (int i = 0; i < n; i++) {
                var r = res.get(i);
                int set = spvc_compiler_get_decoration(compiler, r.id(), SPV_DECORATION_DESCRIPTOR_SET);
                if (set != 0) {
                    throw new IllegalStateException("Voxy vulkan shaders only use descriptor set 0, got " + set + " for " + r.nameString());
                }
                int binding = spvc_compiler_get_decoration(compiler, r.id(), SPV_DECORATION_BINDING);
                out.add(new Binding(binding, descriptorType, r.nameString()));
            }
        }
    }

    private static void check(int result, long ctx, String what) {
        if (result != SPVC_SUCCESS) {
            throw new IllegalStateException("SPIRV-Cross " + what + " failed: " + (ctx != 0 ? spvc_context_get_last_error_string(ctx) : result));
        }
    }

    private SpirvReflection() {}
}

package me.cortex.voxy.client.core.vulkan.shader;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

//Moves GL style bindings (separate namespaces per resource kind) into disjoint ranges of the single vulkan
// descriptor set namespace by patching the Binding decorations of a SPIR-V module in place.
// (shaderc's binding base option does not shift explicit bindings of combined image samplers, so this is done here)
public final class SpirvBindingRemapper {
    private static final int OP_DECORATE = 71;
    private static final int OP_TYPE_IMAGE = 25;
    private static final int OP_TYPE_SAMPLER = 26;
    private static final int OP_TYPE_SAMPLED_IMAGE = 27;
    private static final int OP_TYPE_ARRAY = 28;
    private static final int OP_TYPE_RUNTIME_ARRAY = 29;
    private static final int OP_TYPE_POINTER = 32;
    private static final int OP_VARIABLE = 59;

    private static final int DECORATION_BLOCK = 2;
    private static final int DECORATION_BUFFER_BLOCK = 3;
    private static final int DECORATION_BINDING = 33;

    private static final int SC_UNIFORM_CONSTANT = 0;
    private static final int SC_UNIFORM = 2;
    private static final int SC_STORAGE_BUFFER = 12;

    public static void remap(ByteBuffer spirv) {
        IntBuffer words = spirv.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
        if (words.get(0) != 0x07230203) {
            throw new IllegalArgumentException("Not a SPIR-V module");
        }
        var bindingWordIndex = new Int2IntOpenHashMap();//variable id -> index of its binding literal
        var bufferBlocks = new IntOpenHashSet();
        var pointerStorage = new Int2IntOpenHashMap();//pointer type id -> storage class
        var pointerPointee = new Int2IntOpenHashMap();//pointer type id -> pointee type id
        var arrayElement = new Int2IntOpenHashMap();
        var imageSampled = new Int2IntOpenHashMap();//image type id -> sampled operand (1 sampled, 2 storage)
        var sampledImages = new IntOpenHashSet();
        var samplers = new IntOpenHashSet();
        var variablePointer = new Int2IntOpenHashMap();//variable id -> pointer type id

        int i = 5;
        int n = words.limit();
        while (i < n) {
            int word = words.get(i);
            int count = word >>> 16;
            int op = word & 0xFFFF;
            if (count == 0) throw new IllegalStateException("Malformed SPIR-V");
            switch (op) {
                case OP_DECORATE -> {
                    int target = words.get(i + 1);
                    int decoration = words.get(i + 2);
                    if (decoration == DECORATION_BINDING) bindingWordIndex.put(target, i + 3);
                    if (decoration == DECORATION_BUFFER_BLOCK) bufferBlocks.add(target);
                }
                case OP_TYPE_IMAGE -> imageSampled.put(words.get(i + 1), words.get(i + 7));
                case OP_TYPE_SAMPLER -> samplers.add(words.get(i + 1));
                case OP_TYPE_SAMPLED_IMAGE -> sampledImages.add(words.get(i + 1));
                case OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY -> arrayElement.put(words.get(i + 1), words.get(i + 2));
                case OP_TYPE_POINTER -> {
                    pointerStorage.put(words.get(i + 1), words.get(i + 2));
                    pointerPointee.put(words.get(i + 1), words.get(i + 3));
                }
                case OP_VARIABLE -> variablePointer.put(words.get(i + 2), words.get(i + 1));
                default -> {}
            }
            i += count;
        }

        for (var entry : bindingWordIndex.int2IntEntrySet()) {
            int var = entry.getIntKey();
            if (!variablePointer.containsKey(var)) continue;
            int ptr = variablePointer.get(var);
            int storage = pointerStorage.get(ptr);
            int pointee = pointerPointee.get(ptr);
            while (arrayElement.containsKey(pointee)) pointee = arrayElement.get(pointee);

            int base;
            if (storage == SC_STORAGE_BUFFER || (storage == SC_UNIFORM && bufferBlocks.contains(pointee))) {
                base = VkShaderCompiler.SSBO_BINDING_BASE;
            } else if (storage == SC_UNIFORM) {
                base = VkShaderCompiler.UBO_BINDING_BASE;
            } else if (storage == SC_UNIFORM_CONSTANT) {
                if (sampledImages.contains(pointee) || samplers.contains(pointee) || imageSampled.get(pointee) == 1) {
                    base = VkShaderCompiler.TEXTURE_BINDING_BASE;
                } else if (imageSampled.get(pointee) == 2) {
                    base = VkShaderCompiler.IMAGE_BINDING_BASE;
                } else {
                    continue;
                }
            } else {
                continue;
            }
            int idx = entry.getIntValue();
            words.put(idx, words.get(idx) + base);
        }
    }

    private SpirvBindingRemapper() {}
}

package me.cortex.voxy.client.core.vulkan.shader;

import me.cortex.voxy.client.core.vulkan.VkContext;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.lwjgl.vulkan.VK10.*;

//Startup cost cutters: compiled (and binding remapped) SPIR-V is cached on disk keyed by the hash of the exact source
// handed to shaderc, and one VkPipelineCache persists the drivers compiled pipelines between launches. Both are purely
// caches: any missing/stale/corrupt file just means the normal compile path runs.
public final class VkShaderCache {
    //Bump when shaderc options or SpirvBindingRemapper change, it is part of every key
    private static final int SPIRV_FORMAT_VERSION = 1;
    public static final boolean DISABLED = Boolean.getBoolean("voxy.noShaderCache");

    //Statistics for the startup log
    public static int spirvHits, spirvMisses, pipelinesCreated;
    public static long spirvCompileNs, spirvLoadNs, pipelineCreateNs;

    private static Path dir;
    private static long pipelineCache;
    private static int pipelinesAtLastSave;

    private static Path dir() {
        if (dir == null) {
            dir = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("shader-cache");
            try {
                Files.createDirectories(dir);
                prune(dir);
            } catch (IOException e) {
                Logger.warn("Could not create the shader cache directory, shader caching disabled", e);
                dir = null;
            }
        }
        return dir;
    }

    //Shaders change with every voxy update, keep the directory from growing forever: drop the least recently written
    // files once there are far more than a full set (a full set is ~25 files)
    private static void prune(Path dir) {
        try (var files = Files.list(dir)) {
            var all = files.filter(p -> p.getFileName().toString().endsWith(".spv")).sorted(java.util.Comparator.comparingLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            })).toList();
            if (all.size() > 400) {
                for (int i = 0; i < all.size() - 200; i++) {
                    Files.deleteIfExists(all.get(i));
                }
            }
        } catch (Exception e) {
            //Not important
        }
    }

    public static String key(String source, ShaderType type) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(("voxy-spirv-" + SPIRV_FORMAT_VERSION + "-" + type.name() + "\n").getBytes());
            md.update(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    //Returns a MemoryUtil allocated buffer (free with memFree) or null on a miss
    public static ByteBuffer loadSpirv(String key) {
        if (DISABLED) return null;
        long start = System.nanoTime();
        try {
            var d = dir();
            if (d == null) return null;
            var file = d.resolve(key + ".spv");
            if (!Files.isRegularFile(file)) return null;
            try (var ch = FileChannel.open(file, StandardOpenOption.READ)) {
                long size = ch.size();
                if (size < 20 || size > (64 << 20) || (size & 3) != 0) return null;
                var buf = MemoryUtil.memAlloc((int) size);
                while (buf.hasRemaining()) {
                    if (ch.read(buf) < 0) break;
                }
                if (buf.hasRemaining() || (buf.getInt(0) != 0x07230203)) {//SPIR-V magic number
                    MemoryUtil.memFree(buf);
                    return null;
                }
                buf.clear();
                return buf;
            }
        } catch (Exception e) {
            return null;
        } finally {
            spirvLoadNs += System.nanoTime() - start;
        }
    }

    public static void storeSpirv(String key, ByteBuffer spirv) {
        if (DISABLED) return;
        try {
            var d = dir();
            if (d == null) return;
            var tmp = d.resolve(key + ".tmp" + Thread.currentThread().threadId());
            var data = spirv.duplicate();
            try (var ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (data.hasRemaining()) ch.write(data);
            }
            Files.move(tmp, d.resolve(key + ".spv"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Logger.warn("Could not write the shader cache", e);
        }
    }

    //The VkPipelineCache handed to every pipeline creation (VK_NULL_HANDLE if caching is off)
    public static synchronized long pipelineCache() {
        if (DISABLED) return VK_NULL_HANDLE;
        if (pipelineCache != 0) return pipelineCache;
        ByteBuffer initial = null;
        try {
            var d = dir();
            if (d != null) {
                var file = d.resolve("pipeline.cache");
                if (Files.isRegularFile(file)) {
                    var bytes = Files.readAllBytes(file);
                    initial = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
                }
            }
        } catch (Exception e) {
            initial = null;
        }
        try (var stack = MemoryStack.stackPush()) {
            var ci = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            if (initial != null) {
                ci.pInitialData(initial);
            }
            var p = stack.mallocLong(1);
            int res = vkCreatePipelineCache(VkContext.get().vkDevice, ci, null, p);
            if (res != VK_SUCCESS) {
                Logger.warn("vkCreatePipelineCache failed (" + res + "), pipelines will not be cached");
                pipelineCache = VK_NULL_HANDLE;
                return VK_NULL_HANDLE;
            }
            pipelineCache = p.get(0);
        } finally {
            if (initial != null) MemoryUtil.memFree(initial);
        }
        return pipelineCache;
    }

    //Writes the pipeline cache to disk if pipelines were created since the last save
    public static synchronized void savePipelineCache() {
        if (DISABLED || pipelineCache == 0 || pipelinesCreated == pipelinesAtLastSave) return;
        pipelinesAtLastSave = pipelinesCreated;
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocPointer(1);
            var dev = VkContext.get().vkDevice;
            if (vkGetPipelineCacheData(dev, pipelineCache, size, null) != VK_SUCCESS || size.get(0) == 0) return;
            var data = MemoryUtil.memAlloc((int) size.get(0));
            try {
                size.put(0, data.remaining());
                if (vkGetPipelineCacheData(dev, pipelineCache, size, data) != VK_SUCCESS) return;
                data.limit((int) size.get(0));
                var d = dir();
                if (d == null) return;
                var tmp = d.resolve("pipeline.cache.tmp");
                try (var ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    while (data.hasRemaining()) ch.write(data);
                }
                Files.move(tmp, d.resolve("pipeline.cache"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                MemoryUtil.memFree(data);
            }
        } catch (Exception e) {
            Logger.warn("Could not save the pipeline cache", e);
        }
    }

    public static String summary() {
        return String.format("spirv cache %d hit/%d miss (load %.1f ms, shaderc %.1f ms), %d pipelines created in %.1f ms",
                spirvHits, spirvMisses, spirvLoadNs / 1e6, spirvCompileNs / 1e6, pipelinesCreated, pipelineCreateNs / 1e6);
    }

    private VkShaderCache() {}
}

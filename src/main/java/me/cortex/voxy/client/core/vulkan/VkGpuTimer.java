package me.cortex.voxy.client.core.vulkan;

import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

//GPU timestamps around the sections of voxy's frame (-Dvoxy.gpuTimers=true, or the benchmark). Disabled it costs nothing.
// Results are read back a few frames later without waiting, so it never stalls.
public final class VkGpuTimer {
    public static final boolean ENABLED = Boolean.getBoolean("voxy.gpuTimers") || Boolean.getBoolean("voxy.bench");
    private static final int MAX_MARKS = 24;
    private static final int FRAMES = 4;

    private static long pool;
    private static boolean usable;
    private static int slot;
    private static int markCount;
    private static final String[][] names = new String[FRAMES][MAX_MARKS];
    private static final int[] marksInSlot = new int[FRAMES];
    private static final boolean[] submitted = new boolean[FRAMES];
    private static final Map<String, double[]> totals = new LinkedHashMap<>();//name -> {sumMs, count}
    private static double frameTotalSum;
    private static long frameTotalCount;

    private static void init() {
        if (pool != 0 || usable) return;
        var ctx = VkContext.get();
        try (var stack = MemoryStack.stackPush()) {
            var count = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(ctx.physicalDevice, count, null);
            var props = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(ctx.physicalDevice, count, props);
            int validBits = props.get(ctx.queue.queueFamilyIndex()).timestampValidBits();
            if (validBits < 36 || ctx.timestampPeriod == 0) {
                Logger.warn("Voxy gpu timers unavailable (timestampValidBits=" + validBits + ")");
                usable = false;
                pool = -1;
                return;
            }
            var ci = VkQueryPoolCreateInfo.calloc(stack).sType$Default().queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(FRAMES * MAX_MARKS);
            var p = stack.mallocLong(1);
            VkContext.check(vkCreateQueryPool(ctx.vkDevice, ci, null, p), "vkCreateQueryPool");
            pool = p.get(0);
            usable = true;
        }
    }

    //Call once per voxy frame before recording any timed work, outside of a render pass
    public static void beginFrame(VkCommandBuffer cmd) {
        if (!ENABLED) return;
        init();
        if (!usable) return;
        collect();
        slot = (slot + 1) % FRAMES;
        markCount = 0;
        vkCmdResetQueryPool(cmd, pool, slot * MAX_MARKS, MAX_MARKS);
        mark(cmd, "start");
    }

    //Marks the point after the named section finished (time between this mark and the previous one is attributed to name)
    public static void mark(VkCommandBuffer cmd, String name) {
        if (!ENABLED || !usable || markCount >= MAX_MARKS) return;
        names[slot][markCount] = name;
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, pool, slot * MAX_MARKS + markCount);
        markCount++;
        marksInSlot[slot] = markCount;
        submitted[slot] = true;
    }

    //Reads back the slots that have finished (never waits)
    private static void collect() {
        var ctx = VkContext.get();
        for (int s = 0; s < FRAMES; s++) {
            if (s == slot || !submitted[s] || marksInSlot[s] < 2) continue;
            int n = marksInSlot[s];
            try (var stack = MemoryStack.stackPush()) {
                var data = stack.mallocLong(n * 2);//value + availability per query
                int res = vkGetQueryPoolResults(ctx.vkDevice, pool, s * MAX_MARKS, n, data, 16, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
                if (res != VK_SUCCESS && res != VK_NOT_READY) continue;
                boolean all = true;
                for (int i = 0; i < n; i++) all &= data.get(i * 2 + 1) != 0;
                if (!all) continue;
                double period = ctx.timestampPeriod / 1e6;//ticks -> ms
                for (int i = 1; i < n; i++) {
                    double ms = (data.get(i * 2) - data.get((i - 1) * 2)) * period;
                    var t = totals.computeIfAbsent(names[s][i], k -> new double[2]);
                    t[0] += ms;
                    t[1]++;
                }
                frameTotalSum += (data.get((n - 1) * 2) - data.get(0)) * period;
                frameTotalCount++;
                submitted[s] = false;
            }
        }
    }

    //Average gpu ms per voxy frame for each section, plus the whole voxy command buffer under "total"
    public static Map<String, Double> averages() {
        var out = new LinkedHashMap<String, Double>();
        for (var e : totals.entrySet()) {
            out.put(e.getKey(), e.getValue()[0] / Math.max(1, e.getValue()[1]));
        }
        out.put("total", frameTotalCount == 0 ? 0 : frameTotalSum / frameTotalCount);
        return out;
    }

    public static void reset() {
        totals.clear();
        frameTotalSum = 0;
        frameTotalCount = 0;
    }

    public static List<String> debugLines() {
        var lines = new ArrayList<String>();
        if (!ENABLED) return lines;
        var sb = new StringBuilder("GPU ms:");
        averages().forEach((k, v) -> sb.append(String.format(" %s=%.3f", k, v)));
        lines.add(sb.toString());
        return lines;
    }

    private VkGpuTimer() {}
}

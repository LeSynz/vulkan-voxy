package me.cortex.voxy.client.bench;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

//Dumps real world sections and the real (baked) model tables of the running game so the CPU benchmarks/tests can run
// on real data (-Dvoxy.bench.dump=<file>, see VoxyBenchmark). Format (little endian):
//  int magic, int version, int idMappingCount, int[idMappingCount], long[65536] model metadata, int[65536] fluid lut,
//  int sectionCount, then per section: long key, long[32768] data
public final class SectionDump {
    public static final int MAGIC = 0x56584453;

    private static Object get(Object o, Class<?> c, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    //levels/counts: how many of the sections closest to (sx,sy,sz) to dump per lod level
    public static void dump(VoxyRenderSystem vrs, Path file, int sx, int sy, int sz, int[] perLevel) {
        try {
            ModelFactory factory = ((ModelBakerySubsystem) get(vrs, VoxyRenderSystem.class, "modelService")).factory;
            int[] idMappings = factory._unsafeRawAccess();
            int count = idMappings.length;
            while (count > 0 && idMappings[count - 1] == -1) count--;
            long[] metadata = (long[]) get(factory, ModelFactory.class, "metadataCache");
            int[] fluidLut = (int[]) get(factory, ModelFactory.class, "fluidStateLUT");

            WorldEngine world = vrs.getEngine();
            List<long[]> chosen = new ArrayList<>();//{key}
            for (int lvl = 0; lvl < perLevel.length; lvl++) {
                if (perLevel[lvl] == 0) continue;
                var all = new ArrayList<Long>();
                world.storage.iteratePositions(lvl, all::add);
                final int l = lvl;
                //Section coordinates at this level are in units of (32 << lvl) blocks, the camera section is at level 0
                all.sort(Comparator.comparingLong(k -> {
                    long dx = WorldEngine.getX(k) - (sx >> l), dy = WorldEngine.getY(k) - (sy >> l), dz = WorldEngine.getZ(k) - (sz >> l);
                    return dx * dx + dy * dy + dz * dz;
                }));
                for (int i = 0; i < Math.min(perLevel[lvl], all.size()); i++) chosen.add(new long[]{all.get(i)});
            }

            long total = 16L + 4L * count + 8L * 65536 + 4L * 65536 + 4 + (8L + 8L * 32768) * chosen.size();
            try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var head = ByteBuffer.allocate((int) (16 + 4L * count + 8L * 65536 + 4L * 65536 + 4)).order(ByteOrder.LITTLE_ENDIAN);
                head.putInt(MAGIC).putInt(1).putInt(count);
                for (int i = 0; i < count; i++) head.putInt(idMappings[i]);
                for (long m : metadata) head.putLong(m);
                for (int f : fluidLut) head.putInt(f);
                head.putInt(chosen.size());
                head.flip();
                while (head.hasRemaining()) ch.write(head);
                var buf = ByteBuffer.allocate(8 + 8 * 32768).order(ByteOrder.LITTLE_ENDIAN);
                for (var c : chosen) {
                    WorldSection section = world.acquire(c[0]);
                    try {
                        buf.clear();
                        buf.putLong(c[0]);
                        buf.asLongBuffer().put(section._unsafeGetRawDataArray());
                        buf.position(0).limit(8 + 8 * 32768);
                        while (buf.hasRemaining()) ch.write(buf);
                    } finally {
                        section.release();
                    }
                }
            }
            Logger.info("[bench] dumped " + chosen.size() + " sections and " + count + " block ids to " + file + " (" + total / 1_000_000 + " MB)");
        } catch (Throwable t) {
            Logger.error("[bench] section dump failed", t);
        }
    }

    private SectionDump() {}
}

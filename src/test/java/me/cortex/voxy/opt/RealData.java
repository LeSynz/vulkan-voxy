package me.cortex.voxy.opt;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.world.WorldEngine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

//Real world sections + the real baked model tables dumped from a running game (client/bench/SectionDump.java, enabled with
// -Dvoxy.bench.dump=<file> on ./gradlew runBench). Located with the VOXY_REAL_DATA environment variable (or the
// voxy.test.realdata property), tests/benchmarks that need it are skipped when it is missing.
public final class RealData {
    public final int[] idMappings = new int[1 << 20];
    public final long[] metadata = new long[1 << 16];
    public final int[] fluidLut = new int[1 << 16];
    public final int blockIdCount;
    public final Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap<>();
    public final List<long[]> positions = new ArrayList<>();//{lvl, x, y, z}

    public static Path path() {
        String p = System.getProperty("voxy.test.realdata", System.getenv("VOXY_REAL_DATA"));
        return p == null || p.isEmpty() ? null : Path.of(p);
    }

    public static RealData loadOrNull() {
        var p = path();
        if (p == null || !Files.isRegularFile(p)) return null;
        try {
            return new RealData(p);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private RealData(Path file) throws IOException {
        Arrays.fill(this.idMappings, -1);
        try (var ch = FileChannel.open(file, StandardOpenOption.READ)) {
            var head = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            ch.read(head);
            head.flip();
            if (head.getInt() != 0x56584453) throw new IOException("bad magic");
            head.getInt();
            this.blockIdCount = head.getInt();
            var buf = ByteBuffer.allocate(4 * this.blockIdCount + 8 * 65536 + 4 * 65536 + 4).order(ByteOrder.LITTLE_ENDIAN);
            while (buf.hasRemaining()) if (ch.read(buf) < 0) break;
            buf.flip();
            for (int i = 0; i < this.blockIdCount; i++) this.idMappings[i] = buf.getInt();
            for (int i = 0; i < 65536; i++) this.metadata[i] = buf.getLong();
            for (int i = 0; i < 65536; i++) this.fluidLut[i] = buf.getInt();
            int n = buf.getInt();
            var sec = ByteBuffer.allocate(8 + 8 * 32768).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < n; i++) {
                sec.clear();
                while (sec.hasRemaining()) if (ch.read(sec) < 0) break;
                sec.flip();
                long key = sec.getLong();
                long[] data = new long[32768];
                sec.asLongBuffer().get(data);
                this.sections.put(key, data);
                this.positions.add(new long[]{WorldEngine.getLevel(key), WorldEngine.getX(key), WorldEngine.getY(key), WorldEngine.getZ(key)});
            }
        }
    }
}

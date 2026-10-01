package me.cortex.voxy.opt;

import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.util.Random;

//Deterministic synthetic 32^3 world sections (index = y<<10 | z<<5 | x)
public final class TestSections {
    private static long id(Random r, int block, int biome) {
        return Mapper.composeMappingId((byte) r.nextInt(16), block, biome);
    }

    public static WorldSection terrain(Random r, int lvl, int surface) {
        var sec = WorldSection._createRawUntrackedUnsafeSection(lvl, r.nextInt(64) - 32, r.nextInt(8) - 4, r.nextInt(64) - 32);
        long[] d = sec._unsafeGetRawDataArray();
        long stone = Mapper.composeMappingId((byte) 0, 1, 3);
        long dirt = Mapper.composeMappingId((byte) 0, 2, 3);
        int nonAir = 0;
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int h = surface + ((x * 5 + z * 3) % 7) - 3;
                    long v;
                    if (y < h - 4) {
                        v = r.nextInt(50) == 0 ? id(r, 3 + r.nextInt(4), 3) : stone;
                    } else if (y < h) {
                        v = dirt;
                    } else if (y == h) {
                        v = Mapper.composeMappingId((byte) 0x0F, 7, 1 + ((x + z) >> 3));
                    } else {
                        v = Mapper.composeMappingId((byte) (y > h + 1 ? 0x0F : 0x0E), 0, 0);
                    }
                    if (!Mapper.isAir(v)) nonAir++;
                    d[(y << 10) | (z << 5) | x] = v;
                }
            }
        }
        sec.addNonEmptyBlockCount(nonAir);
        return sec;
    }

    //Same as the original SaveLoadJMH data: mostly air with 10000 random voxels from 1024 ids
    public static WorldSection sparseNoise(Random r, int lvl) {
        var sec = WorldSection._createRawUntrackedUnsafeSection(lvl, (int) (r.nextFloat() * 100 - 50), (int) (r.nextFloat() * 10 - 5), (int) (r.nextFloat() * 100 - 50));
        var raw = sec._unsafeGetRawDataArray();
        int notAir = 0;
        for (int i = 0; i < 10000; i++) {
            int idx = r.nextInt(raw.length);
            long p = raw[idx];
            long n = raw[idx] = r.nextLong() & 1023;
            if (p == 0 && n != 0) notAir++;
            if (n == 0 && p != 0) notAir--;
        }
        sec.addNonEmptyBlockCount(notAir);
        return sec;
    }

    //Dense random: worst case for the run length shortcut and the LUT
    public static WorldSection denseNoise(Random r, int lvl, int distinct) {
        var sec = WorldSection._createRawUntrackedUnsafeSection(lvl, 1, 2, 3);
        var raw = sec._unsafeGetRawDataArray();
        long[] pool = new long[distinct];
        for (int i = 0; i < distinct; i++) pool[i] = r.nextLong();
        for (int i = 0; i < raw.length; i++) raw[i] = pool[r.nextInt(distinct)];
        return sec;
    }

    private TestSections() {}
}

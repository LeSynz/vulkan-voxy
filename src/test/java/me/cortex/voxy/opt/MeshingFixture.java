package me.cortex.voxy.opt;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.function.LongConsumer;

//Headless meshing setup: a ModelFactory whose model tables (the only part RenderDataFactory reads) are filled with
// synthetic model metadata in the format ModelFactory.processTextureBakeResult produces, and a WorldEngine backed
// by an in-memory map of synthetic sections (used for the neighbour face lookups)
public final class MeshingFixture {
    //Face byte bits (see ModelFactory/ModelQueries)
    private static final int OCC = 1, FULL = 2, CAN_OCC = 4, SELF_LIGHT = 8, NONE = 0xFF;
    //Flag byte bits
    private static final long BIOME = 1, TRANSLUCENT = 2, DOUBLE_SIDED = 4, CONTAINS_FLUID = 8, IS_FLUID = 16, CULLS_SAME = 32, FULLY_OPAQUE = 64;

    //Fixed model ids
    public static final int M_STONE = 1, M_GRASS = 2, M_WATER = 3, M_LEAVES = 4, M_GLASS = 5, M_FLOWER = 6, M_WATERLOGGED = 7, M_TORCH = 8, M_ORE = 9, M_LAVA = 10;
    public static final int FIXED_MODELS = 11;

    public static long meta(int[] faces, long flags, int emission) {
        long m = 0;
        for (int f = 5; f >= 0; f--) {
            m <<= 8;
            m |= faces[f] & 0xFF;
        }
        m |= flags << 48;
        m |= ((long) emission) << (48 + 7);
        return m;
    }

    private static int[] all(int v) {
        int[] a = new int[6];
        Arrays.fill(a, v);
        return a;
    }

    public static final class Models {
        public final ModelFactory factory;
        public final long[] metadata;
        public final int[] fluidLut;
        public final int[] idMappings;
        public final int modelCount;
        public final int blockCount;

        Models(ModelFactory factory, long[] metadata, int[] fluidLut, int[] idMappings, int modelCount, int blockCount) {
            this.factory = factory;
            this.metadata = metadata;
            this.fluidLut = fluidLut;
            this.idMappings = idMappings;
            this.modelCount = modelCount;
            this.blockCount = blockCount;
        }
    }

    //randomModels extra models with random (but well formed) metadata; every block id maps to a model
    public static Models createModels(Random r, int randomModels) {
        long[] metadata = new long[1 << 16];
        int[] fluidLut = new int[1 << 16];
        int[] idMappings = new int[1 << 20];
        Arrays.fill(fluidLut, -1);
        Arrays.fill(idMappings, -1);

        metadata[0] = 0;//air entry
        metadata[M_STONE] = meta(all(OCC | FULL | CAN_OCC), FULLY_OPAQUE, 0);
        metadata[M_GRASS] = meta(all(OCC | FULL | CAN_OCC), FULLY_OPAQUE | BIOME, 0);
        metadata[M_WATER] = meta(new int[]{FULL | CAN_OCC | SELF_LIGHT, CAN_OCC | SELF_LIGHT | FULL, FULL | CAN_OCC | SELF_LIGHT, FULL | CAN_OCC | SELF_LIGHT, FULL | CAN_OCC | SELF_LIGHT, FULL | CAN_OCC | SELF_LIGHT}, IS_FLUID | TRANSLUCENT | CULLS_SAME | BIOME, 0);
        metadata[M_LEAVES] = meta(all(FULL | CAN_OCC), BIOME | CULLS_SAME, 0);
        metadata[M_GLASS] = meta(all(FULL | CAN_OCC | SELF_LIGHT), TRANSLUCENT | CULLS_SAME, 0);
        metadata[M_FLOWER] = meta(new int[]{NONE, NONE, SELF_LIGHT, SELF_LIGHT, SELF_LIGHT, SELF_LIGHT}, DOUBLE_SIDED, 0);
        metadata[M_WATERLOGGED] = meta(new int[]{OCC | FULL | CAN_OCC, CAN_OCC | SELF_LIGHT | FULL, CAN_OCC, CAN_OCC, CAN_OCC, CAN_OCC}, CONTAINS_FLUID, 0);
        metadata[M_TORCH] = meta(new int[]{SELF_LIGHT, SELF_LIGHT, SELF_LIGHT, SELF_LIGHT, SELF_LIGHT, SELF_LIGHT}, 0, 14);
        metadata[M_ORE] = meta(all(OCC | FULL | CAN_OCC), FULLY_OPAQUE, 3);
        metadata[M_LAVA] = meta(all(FULL | CAN_OCC), IS_FLUID | CULLS_SAME, 15);
        fluidLut[M_WATER] = M_WATER;
        fluidLut[M_LAVA] = M_LAVA;
        fluidLut[M_WATERLOGGED] = M_WATER;

        int modelCount = FIXED_MODELS + randomModels;
        for (int m = FIXED_MODELS; m < modelCount; m++) {
            int[] faces = new int[6];
            boolean opaque = r.nextInt(3) == 0;
            for (int f = 0; f < 6; f++) {
                faces[f] = opaque ? (OCC | FULL | CAN_OCC) : (r.nextInt(5) == 0 ? NONE : r.nextInt(16));
            }
            long flags = opaque ? FULLY_OPAQUE : (r.nextLong() & (BIOME | TRANSLUCENT | DOUBLE_SIDED | CULLS_SAME));
            flags |= r.nextInt(4) == 0 ? BIOME : 0;
            int kind = r.nextInt(10);
            if (!opaque && kind == 0) flags |= IS_FLUID;
            else if (!opaque && kind == 1) flags |= CONTAINS_FLUID;
            metadata[m] = meta(faces, flags, r.nextInt(8) == 0 ? r.nextInt(16) : 0);
            if ((flags & IS_FLUID) != 0) fluidLut[m] = m;
            if ((flags & CONTAINS_FLUID) != 0) fluidLut[m] = r.nextBoolean() ? M_WATER : M_LAVA;
        }

        //Block ids: 0 is air, 1..FIXED_MODELS-1 map 1:1, the rest map to models (several block states per model)
        int blockCount = modelCount * 2;
        idMappings[0] = 0;
        for (int b = 1; b < blockCount; b++) {
            idMappings[b] = b < modelCount ? b : 1 + r.nextInt(modelCount - 1);
        }

        try {
            var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            var factory = (ModelFactory) unsafe.allocateInstance(ModelFactory.class);
            set(factory, "metadataCache", metadata);
            set(factory, "fluidStateLUT", fluidLut);
            set(factory, "idMappings", idMappings);
            return new Models(factory, metadata, fluidLut, idMappings, modelCount, blockCount);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void set(Object o, String name, Object v) throws ReflectiveOperationException {
        Field f = ModelFactory.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    //In memory section storage, positions not in the map load as air
    public static final class MapStorage extends SectionStorage {
        public final Long2ObjectOpenHashMap<long[]> sections = new Long2ObjectOpenHashMap<>();

        @Override
        public int loadSection(WorldSection into) {
            var data = this.sections.get(into.key);
            if (data == null) return 1;
            System.arraycopy(data, 0, into._unsafeGetRawDataArray(), 0, data.length);
            return 0;
        }

        @Override public void saveSection(WorldSection section) {}
        @Override public void putIdMapping(int id, ByteBuffer data) {}
        @Override public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {return new Int2ObjectOpenHashMap<>();}
        @Override public void flush() {}
        @Override public void close() {}
        @Override public void iteratePositions(int level, LongConsumer callback) {}
    }

    public static WorldEngine createWorld(MapStorage storage) {
        McBootstrap.init();
        return new WorldEngine(storage);
    }

    private static long v(int light, int block, int biome) {
        return Mapper.composeMappingId((byte) light, block, biome);
    }

    //A 32^3 section of terrain like data at (x,y,z) of the given level using the fixed models (and random block ids for variety)
    public static long[] terrain(Random r, Models models, int surface, int waterLevel) {
        long[] d = new long[32 * 32 * 32];
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int h = surface + ((x * 5 + z * 3) % 7) - 3 + ((x / 9 + z / 11) % 3);
                    long val;
                    if (y < h - 4) {
                        val = r.nextInt(40) == 0 ? v(0, M_ORE, 3) : (r.nextInt(200) == 0 ? v(0, FIXED_MODELS + r.nextInt(models.blockCount - FIXED_MODELS), 3) : v(0, M_STONE, 3));
                    } else if (y < h) {
                        val = v(0, M_STONE, 3);
                    } else if (y == h) {
                        val = h < waterLevel ? v(0, M_STONE, 2) : v(0xF0, M_GRASS, 1 + ((x + z) >> 3));
                    } else if (y <= waterLevel) {
                        val = r.nextInt(30) == 0 ? v(0xD0, M_WATERLOGGED, 2) : v(0xE0 - Math.min(0xE0, (waterLevel - y) << 4), M_WATER, 2);
                    } else if (y == h + 1 && r.nextInt(8) == 0) {
                        val = v(0xF0, r.nextInt(5) == 0 ? M_TORCH : M_FLOWER, 1);
                    } else if (y > h + 3 && y < h + 9 && ((x / 6 + z / 6) % 4 == 0) && r.nextInt(3) != 0) {
                        val = v(0xF0 | r.nextInt(16), M_LEAVES, 1);
                    } else if (y > h + 10 && (x % 13 == 2) && (z % 7 < 3)) {
                        val = v(0xF0, M_GLASS, 0);
                    } else {
                        val = v(y > h + 1 ? 0xF0 : 0xE0 | r.nextInt(16), 0, 0);
                    }
                    d[(y << 10) | (z << 5) | x] = val;
                }
            }
        }
        return d;
    }

    //Adversarial: random voxels over every model (incl. the random models), random light/biome
    public static long[] noise(Random r, Models models, float airChance) {
        long[] d = new long[32 * 32 * 32];
        for (int i = 0; i < d.length; i++) {
            if (r.nextFloat() < airChance) {
                d[i] = v(r.nextInt(256), 0, 0);
            } else {
                d[i] = v(r.nextInt(256), 1 + r.nextInt(models.blockCount - 1), r.nextInt(64));
            }
        }
        return d;
    }

    public static int nonAir(long[] data) {
        int c = 0;
        for (long l : data) if (!Mapper.isAir(l)) c++;
        return c;
    }

    private MeshingFixture() {}
}

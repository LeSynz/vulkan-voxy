package me.cortex.voxy.opt;

import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

//Deterministic synthetic voxel data for benchmarks and byte-identical tests
public final class TestWorlds {
    public static Mapper createMapper() {
        McBootstrap.init();
        return new Mapper(new MemoryStorageBackend());
    }

    public static long[] blockIds(Mapper mapper) {
        BlockState[] states = {Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(), Blocks.GLASS.defaultBlockState(),
                Blocks.SAND.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), Blocks.COAL_ORE.defaultBlockState(),
                Blocks.SHORT_GRASS.defaultBlockState(), Blocks.TORCH.defaultBlockState()};
        long[] ids = new long[states.length];
        for (int i = 0; i < states.length; i++) {
            ids[i] = mapper.getIdForBlockState(states[i]);
        }
        return ids;
    }

    //Terrain like: solid below a surface height, air above, some ores/water/leaves, varying light
    public static VoxelizedSection terrainSection(Random r, long[] blockIds, int surface) {
        var section = VoxelizedSection.createEmpty();
        int nonAir = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    long id;
                    int h = surface + ((x*7+z*3)%5) - 2;
                    if (y < h - 3) {
                        id = Mapper.composeMappingId((byte) 0, (int) (r.nextInt(40) == 0 ? blockIds[8] : blockIds[0]), 1);
                    } else if (y < h) {
                        id = Mapper.composeMappingId((byte) 0, (int) blockIds[1], 1);
                    } else if (y == h) {
                        id = Mapper.composeMappingId((byte) 0x0F, (int) blockIds[2], 1);
                    } else if (y == h + 1 && r.nextInt(10) == 0) {
                        id = Mapper.composeMappingId((byte) 0x0F, (int) blockIds[9], 1);
                    } else if (y > h + 4 && r.nextInt(60) == 0) {
                        id = Mapper.composeMappingId((byte) 0x0F, (int) blockIds[4], 2);
                    } else {
                        id = Mapper.composeMappingId((byte) (y > h ? 0x0F : (r.nextInt(16)<<4)), 0, 0);
                    }
                    if (!Mapper.isAir(id)) nonAir++;
                    section.section[(y<<8)|(z<<4)|x] = id;
                }
            }
        }
        section.lvl0NonAirCount = nonAir;
        return section;
    }

    //Worst case: every voxel random
    public static VoxelizedSection noiseSection(Random r, long[] blockIds) {
        var section = VoxelizedSection.createEmpty();
        int nonAir = 0;
        for (int i = 0; i < 16*16*16; i++) {
            int b = r.nextInt(blockIds.length + 2);
            long id = b >= blockIds.length ? Mapper.composeMappingId((byte) r.nextInt(), 0, 0)
                    : Mapper.composeMappingId((byte) r.nextInt(), (int) blockIds[b], r.nextInt(5));
            if (!Mapper.isAir(id)) nonAir++;
            section.section[i] = id;
        }
        section.lvl0NonAirCount = nonAir;
        return section;
    }

    private TestWorlds() {}
}

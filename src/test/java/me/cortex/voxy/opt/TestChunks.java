package me.cortex.voxy.opt;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.IdMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

//Real minecraft chunk section containers (block states, biomes, light) for conversion tests/benchmarks
public final class TestChunks {
    //Biome holders only need a registry key for voxy's mapper (no biome registry exists without a world)
    private static final class BiomeIds implements IdMap<Holder<Biome>> {
        final List<Holder<Biome>> holders = new ArrayList<>();
        BiomeIds() {
            HolderOwner<Biome> owner = new HolderOwner<>() {};
            for (String name : new String[]{"plains", "forest", "desert", "ocean", "river", "taiga"}) {
                this.holders.add(Holder.Reference.createStandAlone(owner, ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(name))));
            }
        }
        @Override public int getId(Holder<Biome> v) { return this.holders.indexOf(v); }
        @Override public Holder<Biome> byId(int id) { return id >= 0 && id < this.holders.size() ? this.holders.get(id) : null; }
        @Override public int size() { return this.holders.size(); }
        @Override public Iterator<Holder<Biome>> iterator() { return this.holders.iterator(); }
    }

    private static BiomeIds BIOMES;

    public record Section(PalettedContainer<BlockState> blocks, PalettedContainer<Holder<Biome>> biomes, DataLayer blockLight, DataLayer skyLight) {}

    public static Section create(Random r, int surface, int distinctBlocks, boolean biomeBorder, int lightMode) {
        McBootstrap.init();
        if (BIOMES == null) BIOMES = new BiomeIds();
        var blocks = new PalettedContainer<>(Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        BlockState[] extra = new BlockState[distinctBlocks];
        for (int i = 0; i < distinctBlocks; i++) {
            extra[i] = Block.BLOCK_STATE_REGISTRY.byId(1 + r.nextInt(Block.BLOCK_STATE_REGISTRY.size() - 1));
        }
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int h = surface + ((x * 5 + z * 3) % 5) - 2;
            BlockState s;
            if (y < h - 3) s = (distinctBlocks > 0 && r.nextInt(20) == 0) ? extra[r.nextInt(distinctBlocks)] : Blocks.STONE.defaultBlockState();
            else if (y < h) s = Blocks.DIRT.defaultBlockState();
            else if (y == h) s = Blocks.GRASS_BLOCK.defaultBlockState();
            else s = Blocks.AIR.defaultBlockState();
            if (s != Blocks.AIR.defaultBlockState()) blocks.set(x, y, z, s);
        }
        var biomes = new PalettedContainer<>(BIOMES.holders.get(0), Strategy.createForBiomes(BIOMES));
        if (biomeBorder) {
            for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
                biomes.set(x, y, z, BIOMES.holders.get((x + z) / 3 % BIOMES.holders.size()));
            }
        }
        DataLayer bl = null, sl = null;
        if (lightMode >= 1) {//sky light with data
            sl = new DataLayer();
            for (int i = 0; i < 4096; i++) sl.set(i & 15, i >> 8, (i >> 4) & 15, (i >> 8) > surface ? 15 : r.nextInt(16));
        }
        if (lightMode >= 2) {//block light too
            bl = new DataLayer();
            for (int i = 0; i < 4096; i++) bl.set(i & 15, i >> 8, (i >> 4) & 15, r.nextInt(8) == 0 ? r.nextInt(16) : 0);
        }
        if (lightMode == 3) {//filled (no backing array) layers
            sl = new DataLayer(15);
            bl = new DataLayer(r.nextInt(20));
        }
        return new Section(blocks, biomes, bl, sl);
    }

    private TestChunks() {}
}

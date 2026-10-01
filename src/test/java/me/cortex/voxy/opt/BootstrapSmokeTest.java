package me.cortex.voxy.opt;

import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class BootstrapSmokeTest {
    @Test
    public void mapperWorksAfterBootstrap() {
        McBootstrap.init();
        var mapper = new Mapper(new MemoryStorageBackend());
        int stone = mapper.getIdForBlockState(Blocks.STONE.defaultBlockState());
        assertTrue(stone > 0);
        System.out.println("stone id " + stone + " opacity " + mapper.getBlockStateOpacity(stone));
    }
}

package me.cortex.voxy.opt.reference;

import me.cortex.voxy.common.voxelization.ILightingSupplier;
import net.minecraft.world.level.chunk.DataLayer;

//Verbatim copy of VoxelIngestService.getLightingSupplier before optimisation (oracle)
public final class ReferenceLighting {
    public static ILightingSupplier create(DataLayer bla, DataLayer sla) {
        ILightingSupplier supplier = (x,y,z) -> (byte) 0;
        boolean sl = sla != null && !sla.isEmpty();
        boolean bl = bla != null && !bla.isEmpty();
        if (sl || bl) {
            if (sl && bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            } else if (bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = 0;
                    return (byte) (sky|(block<<4));
                };
            } else {
                supplier = (x,y,z)-> {
                    int block = 0;
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            }
        }
        return supplier;
    }

    private ReferenceLighting() {}
}

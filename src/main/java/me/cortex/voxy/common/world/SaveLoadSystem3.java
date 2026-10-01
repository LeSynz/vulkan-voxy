package me.cortex.voxy.common.world;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.other.Mapper;
import org.lwjgl.system.MemoryUtil;

public class SaveLoadSystem3 {
    public static final int STORAGE_VERSION = 0;

    private record SerializationCache(Lut lutMapCache, MemoryBuffer memoryBuffer) {
        public SerializationCache() {
            this(new Lut(), ThreadLocalMemoryBuffer.create(WorldSection.SECTION_VOLUME*2+WorldSection.SECTION_VOLUME*8+1024));
        }
    }

    //Open addressing long -> mapping index table specialised for serialize: mappings are handed out in order of
    // first insertion (exactly like the previous Long2ShortOpenHashMap putIfAbsent(key, size()) usage) and a
    // generation stamp replaces clearing the table between sections
    private static final class Lut {
        private long[] keys;
        private short[] values;
        private int[] stamps;
        private int mask;
        private int size;
        private int generation = 1;

        Lut() {
            this.allocate(1<<12);
        }

        private void allocate(int capacity) {
            this.keys = new long[capacity];
            this.values = new short[capacity];
            this.stamps = new int[capacity];
            this.mask = capacity - 1;
        }

        void clear() {
            this.size = 0;
            if (++this.generation == 0) {//Wrapped, invalidate everything explicitly
                java.util.Arrays.fill(this.stamps, 0);
                this.generation = 1;
            }
        }

        int size() {
            return this.size;
        }

        private static int slot(long key, int mask) {
            return (int) it.unimi.dsi.fastutil.HashCommon.mix(key) & mask;
        }

        //Returns the existing mapping of key, or -1 after assigning it the mapping size()
        int putIfAbsent(long key) {
            final int gen = this.generation;
            final int mask = this.mask;
            final long[] keys = this.keys;
            final int[] stamps = this.stamps;
            int h = slot(key, mask);
            while (true) {
                if (stamps[h] != gen) {
                    stamps[h] = gen;
                    keys[h] = key;
                    this.values[h] = (short) this.size;
                    if (++this.size * 2 > mask) {
                        this.grow();
                    }
                    return -1;
                }
                if (keys[h] == key) {
                    return this.values[h];
                }
                h = (h + 1) & mask;
            }
        }

        private void grow() {
            final long[] oldKeys = this.keys;
            final short[] oldValues = this.values;
            final int[] oldStamps = this.stamps;
            final int gen = this.generation;
            this.allocate(oldKeys.length * 2);
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldStamps[i] == gen) {
                    int h = slot(oldKeys[i], this.mask);
                    while (this.stamps[h] == gen) {
                        h = (h + 1) & this.mask;
                    }
                    this.stamps[h] = gen;
                    this.keys[h] = oldKeys[i];
                    this.values[h] = oldValues[i];
                }
            }
        }
    }
    public static int lin2z(int i) {//y,z,x
        int x = i&0x1F;
        int y = (i>>10)&0x1F;
        int z = (i>>5)&0x1F;
        return Integer.expand(x,0b1001001001001)|Integer.expand(y,0b10010010010010)|Integer.expand(z,0b100100100100100);

        //zyxzyxzyxzyxzyx
    }

    public static int z2lin(int i) {
        int x = Integer.compress(i, 0b1001001001001);
        int y = Integer.compress(i, 0b10010010010010);
        int z = Integer.compress(i, 0b100100100100100);
        return x|(y<<10)|(z<<5);
    }

    private static final ThreadLocal<SerializationCache> CACHE = ThreadLocal.withInitial(SerializationCache::new);

    //TODO: Cache like long2short and the short and other data to stop allocs
    public static MemoryBuffer serialize(WorldSection section) {
        var cache = CACHE.get();
        var data = section.data;

        Lut LUT = cache.lutMapCache; LUT.clear();

        MemoryBuffer buffer = cache.memoryBuffer().createUntrackedUnfreeableReference();
        long ptr = buffer.address;

        MemoryUtil.memPutLong(ptr, section.key); ptr += 8;
        long metadataPtr = ptr; ptr += 8;

        long blockPtr = ptr; ptr += WorldSection.SECTION_VOLUME*2;
        long prev = data[0]; MemoryUtil.memPutLong(ptr, prev); ptr+=8; LUT.putIfAbsent(prev);
        short mapping = 0;
        for (long block : data) {
            if (prev != block) {
                prev = block;
                mapping = (short) LUT.putIfAbsent(block);
                if (mapping == -1) {
                    mapping = (short) (LUT.size()-1);
                    MemoryUtil.memPutLong(ptr, block); ptr+=8;
                }
            }
            MemoryUtil.memPutShort(blockPtr, mapping); blockPtr+=2;
        }
        if (LUT.size() >= 1<<16) {
            throw new IllegalStateException();
        }

        //TODO: note! can actually have the first (last?) byte of metadata be the storage version!
        long metadata = 0;
        metadata |= Integer.toUnsignedLong(LUT.size());//Bottom 2 bytes
        metadata |= Byte.toUnsignedLong(section.getNonEmptyChildren())<<16;//Next byte
        //5 bytes free

        MemoryUtil.memPutLong(metadataPtr, metadata);
        //TODO: do hash

        return buffer.subSize(ptr-buffer.address);//Does not get freed
    }

    public static boolean deserialize(WorldSection section, MemoryBuffer data) {
        long ptr = data.address;
        long key = MemoryUtil.memGetLong(ptr); ptr += 8;

        if (section.key != key) {
            //throw new IllegalStateException("Decompressed section not the same as requested. got: " + key + " expected: " + section.key);
            Logger.error("Decompressed section not the same as requested. got: " + key + " expected: " + section.key);
            return false;
        }

        final long metadata = MemoryUtil.memGetLong(ptr); ptr += 8;
        section.nonEmptyChildren = (byte) ((metadata>>>16)&0xFF);
        final long lutBasePtr = ptr + WorldSection.SECTION_VOLUME * 2;

        final var blockData = section.data;
        for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
            blockData[i] = MemoryUtil.memGetLong(lutBasePtr + Short.toUnsignedLong(MemoryUtil.memGetShort(ptr)) * 8L);ptr += 2;
        }

        if (section.lvl == 0) {
            int notEmpty = 0;
            for (long block : blockData) {
                notEmpty += Mapper.isNotAirInt(block);
            }
            section.nonEmptyBlockCount = notEmpty;
        }

        ptr = lutBasePtr + (metadata & 0xFFFF) * 8L;
        return true;
    }
}

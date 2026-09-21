package me.example.autobuilder;

import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads Sponge .schem v2 and v3. Iteration order is bottom-up (y, then z, then x). */
public class Schematic {
    public final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();

    public static Schematic load(Path path) throws Exception {
        NbtCompound root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
        if (root.contains("Schematic")) root = root.getCompound("Schematic");

        int w = root.getShort("Width") & 0xFFFF;
        int l = root.getShort("Length") & 0xFFFF;

        NbtCompound paletteNbt;
        byte[] data;
        if (root.contains("Blocks")) { // v3
            NbtCompound b = root.getCompound("Blocks");
            paletteNbt = b.getCompound("Palette");
            data = b.getByteArray("Data");
        } else { // v2
            paletteNbt = root.getCompound("Palette");
            data = root.getByteArray("BlockData");
        }

        Map<Integer, BlockState> palette = new HashMap<>();
        for (String key : paletteNbt.getKeys()) {
            palette.put(paletteNbt.getInt(key),
                    BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), key, false).blockState());
        }

        Schematic s = new Schematic();
        int i = 0, idx = 0;
        while (i < data.length) {
            int val = 0, shift = 0;
            byte b;
            do {
                b = data[i++];
                val |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);

            int x = idx % w;
            int z = (idx / w) % l;
            int y = idx / (w * l);
            idx++;

            BlockState state = palette.get(val);
            if (state == null || state.isAir()) continue;
            s.blocks.put(new BlockPos(x, y, z), state);
        }
        return s;
    }
}

package me.example.autobuilder;

import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads Sponge .schem v2 and v3. Iteration order is bottom-up (y, then z, then x). */
public class Schematic {
    public final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
    /** Block ids from the file that no longer exist and had no known remap; those positions were skipped. */
    public final List<String> skippedTypes = new ArrayList<>();

    // Old block ids that were renamed in later versions. Base id only; any [properties] are carried over as-is.
    private static final Map<String, String> LEGACY_REMAP = Map.of(
            "minecraft:grass", "minecraft:short_grass",           // renamed in 1.20.3
            "minecraft:grass_path", "minecraft:dirt_path",        // renamed in 1.17
            "minecraft:web", "minecraft:cobweb",
            "minecraft:sign", "minecraft:oak_sign",
            "minecraft:wall_sign", "minecraft:oak_wall_sign"
    );

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

        Schematic s = new Schematic();
        Map<Integer, BlockState> palette = new HashMap<>();
        for (String key : paletteNbt.getKeys()) {
            palette.put(paletteNbt.getInt(key), parseBlockState(key, s.skippedTypes));
        }

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

    /** Parses a palette key like "minecraft:oak_stairs[facing=north]", remapping known renamed ids. Returns null (skip) if it can't be resolved at all. */
    private static BlockState parseBlockState(String key, List<String> skippedOut) {
        try {
            return BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), key, false).blockState();
        } catch (Exception ignored) {
            // fall through to remap attempt
        }
        String remapped = remap(key);
        if (remapped != null) {
            try {
                return BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), remapped, false).blockState();
            } catch (Exception ignored) {
                // fall through to skip
            }
        }
        String baseId = key.contains("[") ? key.substring(0, key.indexOf('[')) : key;
        if (!skippedOut.contains(baseId)) skippedOut.add(baseId);
        return null;
    }

    private static String remap(String key) {
        int br = key.indexOf('[');
        String baseId = br < 0 ? key : key.substring(0, br);
        String props = br < 0 ? "" : key.substring(br);
        String newBase = LEGACY_REMAP.get(baseId);
        return newBase == null ? null : newBase + props;
    }
}

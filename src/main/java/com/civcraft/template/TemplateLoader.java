package com.civcraft.template;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;

/** Reads Sponge Schematic v2/v3 files into {@link Template}s. */
public final class TemplateLoader {

    private TemplateLoader() {
    }

    @SuppressWarnings("unchecked")
    public static Template load(String id, InputStream in) throws IOException {
        Map<String, Object> root = Nbt.readGzipped(in);
        Map<String, Object> schem = root.containsKey("Schematic") ? (Map<String, Object>) root.get("Schematic") : root;
        int version = ((Number) schem.getOrDefault("Version", 2)).intValue();
        int sx = ((Number) schem.get("Width")).intValue() & 0xFFFF;
        int sy = ((Number) schem.get("Height")).intValue() & 0xFFFF;
        int sz = ((Number) schem.get("Length")).intValue() & 0xFFFF;

        Map<String, Object> container = version >= 3 ? (Map<String, Object>) schem.get("Blocks") : schem;
        Map<String, Object> paletteTag = (Map<String, Object>) container.get("Palette");
        byte[] data = (byte[]) container.get(version >= 3 ? "Data" : "BlockData");
        List<Object> entities = (List<Object>) container.getOrDefault(version >= 3 ? "BlockEntities" : "BlockEntities", List.of());

        BlockData[] palette = new BlockData[paletteTag.size()];
        for (Map.Entry<String, Object> e : paletteTag.entrySet()) {
            int index = ((Number) e.getValue()).intValue();
            if (index < 0 || index >= palette.length) throw new IOException("Palette index out of range in " + id);
            palette[index] = parseBlock(e.getKey());
        }
        int[] blocks = new int[sx * sy * sz];
        int pos = 0;
        for (int i = 0; i < blocks.length; i++) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                if (pos >= data.length) throw new IOException("Truncated block data in " + id);
                b = data[pos++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            if (value < 0 || value >= palette.length) throw new IOException("Block palette id out of range in " + id);
            blocks[i] = value;
        }

        List<Template.Marker> markers = new ArrayList<>();
        Map<Integer, List<String>> signs = new HashMap<>();
        BlockData air = Bukkit.createBlockData(Material.AIR);
        int airIndex = -1;
        for (Object o : entities) {
            Map<String, Object> entity = (Map<String, Object>) o;
            int[] p = (int[]) entity.get("Pos");
            Map<String, Object> tag = version >= 3 ? (Map<String, Object>) entity.getOrDefault("Data", Map.of()) : entity;
            List<String> lines = signLines(tag);
            if (lines == null) continue;
            int index = p[0] + p[2] * sx + p[1] * sx * sz;
            if (index < 0 || index >= blocks.length) continue;
            String first = lines.getFirst().trim();
            if (first.startsWith("/")) {
                String[] head = first.substring(1).split("\\s+");
                List<String> args = new ArrayList<>(Arrays.asList(head).subList(1, head.length));
                lines.subList(1, lines.size()).stream().map(String::trim).filter(s -> !s.isEmpty()).forEach(args::add);
                String facing = facingOf(palette[blocks[index]]);
                markers.add(new Template.Marker(p[0], p[1], p[2], head[0].toLowerCase(), args, facing));
                if (airIndex < 0) {
                    palette = Arrays.copyOf(palette, palette.length + 1);
                    airIndex = palette.length - 1;
                    palette[airIndex] = air;
                }
                blocks[index] = airIndex;
            } else if (lines.stream().anyMatch(s -> !s.isBlank())) {
                signs.put(index, lines);
            }
        }
        return new Template(id, sx, sy, sz, palette, blocks, markers, signs);
    }

    private static BlockData parseBlock(String state) {
        BlockData data;
        try {
            data = Bukkit.createBlockData(state);
        } catch (IllegalArgumentException e) {
            data = Bukkit.createBlockData(Material.AIR);
        }
        // Template leaves must never decay.
        if (data instanceof Leaves leaves) leaves.setPersistent(true);
        return data;
    }

    private static String facingOf(BlockData data) {
        if (data instanceof org.bukkit.block.data.Directional d) return d.getFacing().name().toLowerCase();
        if (data instanceof org.bukkit.block.data.Rotatable r) {
            return switch (r.getRotation()) {
                case NORTH, NORTH_NORTH_EAST, NORTH_NORTH_WEST -> "north";
                case EAST, EAST_NORTH_EAST, EAST_SOUTH_EAST, NORTH_EAST -> "east";
                case WEST, WEST_NORTH_WEST, WEST_SOUTH_WEST, NORTH_WEST -> "west";
                default -> "south";
            };
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> signLines(Map<String, Object> tag) {
        Object front = tag.get("front_text");
        if (front instanceof Map<?, ?> frontMap && frontMap.get("messages") instanceof List<?> messages) {
            List<String> lines = new ArrayList<>();
            for (Object m : messages) lines.add(plain(m));
            while (lines.size() < 4) lines.add("");
            return lines;
        }
        if (tag.containsKey("Text1")) {
            List<String> lines = new ArrayList<>();
            for (int i = 1; i <= 4; i++) lines.add(plain(tag.getOrDefault("Text" + i, "")));
            return lines;
        }
        return null;
    }

    /** Sign text may be a plain string, a JSON text component, or an NBT text compound. */
    private static String plain(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object text = map.get("text");
            return text == null ? "" : text.toString();
        }
        String s = String.valueOf(value);
        if (s.startsWith("{") || s.startsWith("\"")) {
            try {
                return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(s));
            } catch (RuntimeException ignored) {
                return s;
            }
        }
        return s;
    }
}

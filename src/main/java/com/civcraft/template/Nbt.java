package com.civcraft.template;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal read-only NBT decoder, enough for Sponge schematics. Compounds become {@link Map}s,
 * lists become {@link List}s, arrays become Java arrays, numbers keep their boxed type.
 */
public final class Nbt {

    private Nbt() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readGzipped(InputStream in) throws IOException {
        try (DataInputStream data = new DataInputStream(new GZIPInputStream(in))) {
            byte type = data.readByte();
            if (type != 10) throw new IOException("Root tag is not a compound");
            data.readUTF();
            return (Map<String, Object>) read(data, type, 0);
        }
    }

    private static Object read(DataInputStream in, byte type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT nested too deeply");
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                byte[] bytes = new byte[checkedLength(in.readInt())];
                in.readFully(bytes);
                yield bytes;
            }
            case 8 -> in.readUTF();
            case 9 -> {
                byte elementType = in.readByte();
                int length = checkedLength(in.readInt());
                List<Object> list = new ArrayList<>(length);
                for (int i = 0; i < length; i++) list.add(read(in, elementType, depth + 1));
                yield list;
            }
            case 10 -> {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    byte t = in.readByte();
                    if (t == 0) break;
                    String name = in.readUTF();
                    map.put(name, read(in, t, depth + 1));
                }
                yield map;
            }
            case 11 -> {
                int[] ints = new int[checkedLength(in.readInt())];
                for (int i = 0; i < ints.length; i++) ints[i] = in.readInt();
                yield ints;
            }
            case 12 -> {
                long[] longs = new long[checkedLength(in.readInt())];
                for (int i = 0; i < longs.length; i++) longs[i] = in.readLong();
                yield longs;
            }
            default -> throw new IOException("Unknown NBT tag type " + type);
        };
    }

    private static int checkedLength(int length) throws IOException {
        if (length < 0 || length > 64 * 1024 * 1024) throw new IOException("Invalid NBT array length " + length);
        return length;
    }
}

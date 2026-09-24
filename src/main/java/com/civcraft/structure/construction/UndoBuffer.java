package com.civcraft.structure.construction;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The original terrain replaced by a structure, captured cell by cell while it is built (so nothing is read from
 * disk or cleared up front like the legacy undo files). Cells are template indices ({@code x + z·sx + y·sx·sz});
 * values are block data strings. Only the first replacement of a cell is recorded.
 */
public final class UndoBuffer {

    private static final int VERSION = 1;

    private final String world;
    private final int ox;
    private final int oy;
    private final int oz;
    private final int sx;
    private final int sy;
    private final int sz;
    private final List<String> palette = new ArrayList<>();
    private final Map<String, Integer> paletteIndex = new HashMap<>();
    private final BitSet captured = new BitSet();
    private int[] cells = new int[256];
    private int[] values = new int[256];
    private int size;
    private boolean dirty;

    public UndoBuffer(String world, int ox, int oy, int oz, int sx, int sy, int sz) {
        this.world = world;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
    }

    public String world() {
        return world;
    }

    public boolean isCaptured(int cell) {
        return captured.get(cell);
    }

    /** Records the original block of a cell the first time it is replaced. */
    public void capture(int cell, String blockData) {
        if (captured.get(cell)) return;
        captured.set(cell);
        Integer p = paletteIndex.get(blockData);
        if (p == null) {
            p = palette.size();
            palette.add(blockData);
            paletteIndex.put(blockData, p);
        }
        if (size == cells.length) {
            cells = Arrays.copyOf(cells, size * 2);
            values = Arrays.copyOf(values, size * 2);
        }
        cells[size] = cell;
        values[size] = p;
        size++;
        dirty = true;
    }

    public int size() {
        return size;
    }

    public boolean dirty() {
        return dirty;
    }

    public void clean() {
        dirty = false;
    }

    /** World position {x, y, z} of a cell. */
    public int[] position(int cell) {
        int x = cell % sx;
        int z = (cell / sx) % sz;
        int y = cell / (sx * sz);
        return new int[]{ox + x, oy + y, oz + z};
    }

    public int cell(int i) {
        return cells[i];
    }

    public String value(int i) {
        return palette.get(values[i]);
    }

    /** Immutable copy for writing on another thread. */
    public UndoBuffer snapshot() {
        UndoBuffer copy = new UndoBuffer(world, ox, oy, oz, sx, sy, sz);
        copy.palette.addAll(palette);
        copy.paletteIndex.putAll(paletteIndex);
        copy.cells = Arrays.copyOf(cells, Math.max(1, size));
        copy.values = Arrays.copyOf(values, Math.max(1, size));
        copy.size = size;
        copy.captured.or(captured);
        return copy;
    }

    /** Writes atomically (temp file + move). Safe to call off the main thread on a snapshot. */
    public void write(File file) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File tmp = new File(file.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(tmp))))) {
            out.writeInt(VERSION);
            out.writeUTF(world);
            out.writeInt(ox);
            out.writeInt(oy);
            out.writeInt(oz);
            out.writeInt(sx);
            out.writeInt(sy);
            out.writeInt(sz);
            out.writeInt(palette.size());
            for (String p : palette) out.writeUTF(p);
            out.writeInt(size);
            for (int i = 0; i < size; i++) {
                out.writeInt(cells[i]);
                writeVarInt(out, values[i]);
            }
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static UndoBuffer read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            int version = in.readInt();
            if (version != VERSION) throw new IOException("Unsupported undo version " + version);
            UndoBuffer buffer = new UndoBuffer(in.readUTF(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
            int paletteSize = in.readInt();
            if (paletteSize < 0 || paletteSize > 1_000_000) throw new IOException("Corrupt palette size");
            for (int i = 0; i < paletteSize; i++) {
                String p = in.readUTF();
                buffer.paletteIndex.put(p, buffer.palette.size());
                buffer.palette.add(p);
            }
            int count = in.readInt();
            if (count < 0 || count > 50_000_000) throw new IOException("Corrupt undo size");
            buffer.cells = new int[Math.max(1, count)];
            buffer.values = new int[Math.max(1, count)];
            for (int i = 0; i < count; i++) {
                int cell = in.readInt();
                int value = readVarInt(in);
                if (value < 0 || value >= paletteSize) throw new IOException("Corrupt undo entry");
                buffer.cells[i] = cell;
                buffer.values[i] = value;
                buffer.captured.set(cell);
            }
            buffer.size = count;
            return buffer;
        }
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        byte b;
        do {
            if (shift > 28) throw new IOException("VarInt too long");
            b = in.readByte();
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }
}

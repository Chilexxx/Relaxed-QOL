package com.relaxedqol.client.schematic;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The cells of a copy (which block of its list stands where), held in one of two ways:
 *
 * in the game's memory - the fastest, and what a copy uses unless Low Impact is on;
 *
 * in a file on disk that the system shows to the game piece by piece (Low Impact): the game's own memory is not
 * used for it at all, and the system keeps only the pieces that are being worked on, so a copy of thousands of
 * chunks costs a few megabytes instead of a gigabyte. The file is thrown away when the copy is done with.
 */
final class Cells {
    /** More cells than this are not taken in one copy, whatever room there is. */
    static final long MOST = 1_000_000_000L;
    private static final ValueLayout.OfChar CHAR = ValueLayout.JAVA_CHAR;
    private static final java.lang.ref.Cleaner FORGOTTEN = java.lang.ref.Cleaner.create();

    /** The file behind cells on disk: closed once, by whoever is done with the copy (or when nobody holds it any more). */
    private static final class Disk implements Runnable {
        final Arena arena = Arena.ofShared();
        final Path file;
        private boolean closed;

        Disk(Path file) {
            this.file = file;
        }

        @Override
        public synchronized void run() {
            if (closed) return;
            closed = true;
            try {
                arena.close();
            } catch (RuntimeException ignored) {
                // still being read by a thread that is about to notice: the file goes with the next sweep
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // swept away the next time a copy is made
            }
        }
    }

    private final char[] heap;
    private final MemorySegment mapped;
    private final Disk disk;
    private final int offset;
    /** A part of other cells: keeps those alive as long as it is. */
    @SuppressWarnings("unused")
    private final Cells whole;
    final int length;

    private Cells(char[] heap, MemorySegment mapped, Disk disk, int offset, int length, Cells whole) {
        this.heap = heap;
        this.mapped = mapped;
        this.disk = disk;
        this.offset = offset;
        this.length = length;
        this.whole = whole;
    }

    /** Stops with a plain sentence when there is no room for this many cells (in memory, or on disk). */
    static void check(long count, boolean onDisk) {
        if (count > MOST) throw new IllegalStateException("That is too much for one copy (" + count / 1_000_000 + " million cells) - take fewer chunks.");
        if (onDisk) {
            long need = count * 2 + (256L << 20);
            long free;
            try {
                free = Files.getFileStore(folder()).getUsableSpace();
            } catch (IOException e) {
                return; // cannot tell: making the file will say
            }
            if (need > free) {
                throw new IllegalStateException("Not enough room on the disk for that much (needs about " + (need >> 20) + " MB in " + folder() + ", "
                    + (free >> 20) + " MB free) - take fewer chunks.");
            }
            return;
        }
        // two bytes a cell and nothing else of that size: the file is written from the cells and read into them
        long need = count * 2 + (64L << 20);
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        if (need > free) {
            System.gc();
            free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        }
        if (need > free) {
            throw new IllegalStateException("Not enough memory for that much (needs about " + (need >> 20) + " MB, " + (free >> 20)
                + " MB free) - take fewer chunks, give the game more memory, or turn on Low Impact (.schem lowimpact).");
        }
    }

    /** Room for this many cells, all empty. Stops with a plain sentence when there is none. */
    static Cells make(long count, boolean onDisk) {
        check(count, onDisk);
        if (!onDisk) return new Cells(new char[(int) count], null, null, 0, (int) count, null);
        sweep();
        Disk disk = null;
        try {
            Path file = Files.createTempFile(folder(), "relaxed-copy-", ".cells");
            file.toFile().deleteOnExit();
            disk = new Disk(file);
            MemorySegment segment;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, count * 2, disk.arena);
            }
            Cells out = new Cells(null, segment, disk, 0, (int) count, null);
            FORGOTTEN.register(out, disk);
            return out;
        } catch (IOException | RuntimeException e) {
            if (disk != null) disk.run();
            throw new IllegalStateException("Could not make the file for a Low Impact copy: " + e.getMessage());
        }
    }

    private static Path folder() {
        return Path.of(System.getProperty("java.io.tmpdir"));
    }

    /** Files of copies that were never thrown away (the game was closed hard): one that is still in use stays. */
    private static void sweep() {
        try (DirectoryStream<Path> old = Files.newDirectoryStream(folder(), "relaxed-copy-*.cells")) {
            long hourAgo = System.currentTimeMillis() - 3_600_000L;
            for (Path p : old) {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < hourAgo) Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // in use by another game window
                }
            }
        } catch (IOException ignored) {
            // nothing to sweep
        }
    }

    boolean onDisk() {
        return mapped != null;
    }

    char get(int index) {
        return heap != null ? heap[offset + index] : mapped.getAtIndex(CHAR, (long) offset + index);
    }

    void set(int index, char value) {
        if (heap != null) heap[offset + index] = value;
        else mapped.setAtIndex(CHAR, (long) offset + index, value);
    }

    /** The cells from..to (to not included) of these, without copying them. */
    Cells part(int from, int to) {
        return new Cells(heap, mapped, disk, offset + from, to - from, this);
    }

    /** Done with: cells on disk give their file back. Reading them after this stops with an error. */
    void close() {
        if (disk != null) disk.run();
    }
}

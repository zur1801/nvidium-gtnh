package me.cortex.nvidium.cache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import me.cortex.nvidium.Nvidium;

/**
 * On-disk store of built section geometry for one dimension of one server, so terrain can be shown immediately after
 * rejoining instead of waiting for every section to be meshed again.
 * <p>
 * Sections are grouped into files of 8x8 chunk columns. All file access happens on a single background thread, so
 * reads always observe earlier writes. Writes are buffered in memory and flushed periodically.
 */
public class MeshCache {

    private static final int MAGIC = 0x4E564D43; // NVMC
    private static final int VERSION = 1;
    private static final byte[] TOMBSTONE = new byte[0];

    private final File dir;
    private final ExecutorService io;
    private final Map<Long, Map<Long, byte[]>> pendingWrites = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Entry> loaded = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    private final AtomicLong pendingBytes = new AtomicLong();
    private volatile boolean failed;

    // Diagnostics
    final AtomicLong regionFilesRead = new AtomicLong();
    final AtomicLong entriesRead = new AtomicLong();

    public record Entry(long sectionKey, byte[] payload) {}

    public MeshCache(File dir) {
        this.dir = dir;
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Nvidium mesh cache IO");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
    }

    public static long regionKey(int chunkX, int chunkZ) {
        return ((long) (chunkX >> 3) << 32) | ((chunkZ >> 3) & 0xFFFFFFFFL);
    }

    private File regionFile(long regionKey) {
        int rx = (int) (regionKey >> 32);
        int rz = (int) regionKey;
        return new File(dir, "r." + rx + "." + rz + ".nvc");
    }

    /** Records new geometry for a section; {@code payload == null} records that the section is now empty. */
    public void put(long regionKey, long sectionKey, byte[] payload) {
        if (failed) return;
        byte[] value = payload == null ? TOMBSTONE : payload;
        byte[] previous = pendingWrites.computeIfAbsent(regionKey, k -> new ConcurrentHashMap<>())
            .put(sectionKey, value);
        pendingBytes.addAndGet(value.length - (previous == null ? 0 : previous.length));
    }

    /** Asynchronously reads a region; its sections show up in {@link #pollLoaded()}. */
    public void requestRegion(long regionKey) {
        if (failed) return;
        io.execute(() -> {
            File file = regionFile(regionKey);
            if (!file.isFile()) return;
            try {
                var entries = readRegion(file);
                regionFilesRead.incrementAndGet();
                entriesRead.addAndGet(entries.size());
                for (var entry : entries.long2ObjectEntrySet()) {
                    loaded.add(new Entry(entry.getLongKey(), entry.getValue()));
                }
            } catch (IOException e) {
                Nvidium.LOGGER.warn("Discarding unreadable mesh cache file {}", file, e);
                file.delete();
            }
        });
    }

    public Entry pollLoaded() {
        return loaded.poll();
    }

    /** Queues a flush of all buffered writes unless one is already queued. */
    public void flushAsync() {
        if (failed || pendingWrites.isEmpty() || !flushQueued.compareAndSet(false, true)) return;
        io.execute(() -> {
            flushQueued.set(false);
            flushPending();
        });
    }

    public long getPendingBytes() {
        return pendingBytes.get();
    }

    /** Writes everything that is buffered and stops the IO thread. Blocks until done. */
    public void close() {
        io.execute(this::flushPending);
        io.shutdown();
        try {
            if (!io.awaitTermination(60, TimeUnit.SECONDS)) {
                Nvidium.LOGGER.warn("Timed out writing the mesh cache");
            }
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
        loaded.clear();
    }

    private void flushPending() {
        List<Long> regions = new ArrayList<>(pendingWrites.keySet());
        for (long regionKey : regions) {
            Map<Long, byte[]> changes = pendingWrites.remove(regionKey);
            if (changes == null) continue;
            for (byte[] value : changes.values()) {
                pendingBytes.addAndGet(-value.length);
            }
            File file = regionFile(regionKey);
            try {
                Long2ObjectLinkedOpenHashMap<byte[]> entries = file.isFile() ? readRegion(file)
                    : new Long2ObjectLinkedOpenHashMap<>();
                for (var change : changes.entrySet()) {
                    if (change.getValue() == TOMBSTONE) {
                        entries.remove((long) change.getKey());
                    } else {
                        entries.put((long) change.getKey(), change.getValue());
                    }
                }
                writeRegion(file, entries);
            } catch (IOException e) {
                Nvidium.LOGGER.error("Failed to write mesh cache file {}, disabling the mesh cache", file, e);
                failed = true;
                pendingWrites.clear();
                return;
            }
        }
    }

    private static Long2ObjectLinkedOpenHashMap<byte[]> readRegion(File file) throws IOException {
        Long2ObjectLinkedOpenHashMap<byte[]> entries = new Long2ObjectLinkedOpenHashMap<>();
        try (DataInputStream in = new DataInputStream(
            new BufferedInputStream(new InflaterInputStream(new FileInputStream(file)), 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IOException("Unknown mesh cache format");
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                int length = in.readInt();
                if (length < 0 || length > (64 << 20)) throw new IOException("Corrupt entry length " + length);
                byte[] payload = new byte[length];
                in.readFully(payload);
                entries.put(key, payload);
            }
        } catch (EOFException e) {
            throw new IOException("Truncated mesh cache file", e);
        }
        return entries;
    }

    private void writeRegion(File file, Long2ObjectLinkedOpenHashMap<byte[]> entries) throws IOException {
        if (entries.isEmpty()) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }
        File tmp = new File(dir, file.getName() + ".tmp");
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DataOutputStream out = new DataOutputStream(
            new BufferedOutputStream(
                new DeflaterOutputStream(new FileOutputStream(tmp), deflater, 1 << 16),
                1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(entries.size());
            for (var entry : entries.long2ObjectEntrySet()) {
                out.writeLong(entry.getLongKey());
                out.writeInt(entry.getValue().length);
                out.write(entry.getValue());
            }
        } finally {
            deflater.end();
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}

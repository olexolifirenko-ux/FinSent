package com.finsent.core.io;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.util.GlobalSystem;

/**
 * The pipeline's disk boundary: it owns the data directory, the per-stream file layout
 * ({@link DataStream}) and the read/write serialization, and it knows nothing of what the data
 * means. It exposes two operations &mdash; {@link #load} reads a stream's recent day-files for
 * recovery, and {@link #commit} writes a batch of {@link WriteUnit}s.
 *
 * <p>A cycle's writes are committed as <b>one atomic batch</b>: the batch is rendered and written
 * to sibling temp files, then every temp is renamed into place. A single daemon thread drains the
 * commit queue so the collector/urgent threads never block on disk; the queued unit is the whole
 * batch, so a cycle's files are never interleaved with another's. Within a batch, repeated writes
 * to the same day-file coalesce to the last (registries render full current state, so the latest
 * supersedes). {@link #flush()} blocks until the queue is empty (used on shutdown); leftover temp
 * files from an interrupted commit are tolerated by recovery and cleaned on the next write.
 */
public final class PersistenceService
{
    private static final String NAME = "PersistenceService";
    private static final String TMP_SUFFIX = ".tmp";

    private final Path dataDir_;
    private final Object lock_ = new Object();
    private final Deque<List<WriteUnit>> queue_ = new ArrayDeque<>();
    private final Thread writer_;
    private boolean processing_ = false;
    private volatile boolean running_ = true;

    public PersistenceService(Path dataDir)
    {
        dataDir_ = dataDir;
        writer_ = new Thread(this::drainLoop, "FS-Persistence");
        writer_.setDaemon(true);
        writer_.start();
    }

    /**
     * Read the most recent {@code lookbackDays} day-files of {@code stream} (newest first by
     * name) as raw payloads for a registry to hydrate. Missing/corrupt files are skipped.
     */
    public List<LoadedDay> load(DataStream stream, int lookbackDays)
    {
        List<LoadedDay> days = new ArrayList<>();
        for (Path file : recentDayFiles(stream, lookbackDays))
        {
            days.add(new LoadedDay(dayOf(file, stream), read(stream, file)));
        }
        return days;
    }

    /** Read one specific day-file of {@code stream} as a raw payload, or {@code null} when absent. */
    public LoadedDay loadDay(DataStream stream, String day)
    {
        LoadedDay result = null;
        Path file = pathFor(stream, day);
        if (Files.isRegularFile(file))
        {
            result = new LoadedDay(day, read(stream, file));
        }
        return result;
    }

    /** Queue a batch for atomic commit. A null/empty batch is a no-op. */
    public void commit(List<WriteUnit> batch)
    {
        if (batch != null && !batch.isEmpty())
        {
            List<WriteUnit> coalesced = coalesce(batch);
            synchronized (lock_)
            {
                queue_.addLast(coalesced);
                lock_.notifyAll();
            }
        }
    }

    /** Block until all queued batches have been written. */
    public void flush()
    {
        synchronized (lock_)
        {
            while (!queue_.isEmpty() || processing_)
            {
                awaitQuietly();
            }
        }
    }

    /** Flush outstanding batches and stop the writer thread. */
    public void shutdown()
    {
        flush();
        running_ = false;
        synchronized (lock_)
        {
            lock_.notifyAll();
        }
    }

    private void drainLoop()
    {
        while (running_)
        {
            List<WriteUnit> batch = takeNext();
            if (batch != null)
            {
                writeBatch(batch);
            }
        }
    }

    private List<WriteUnit> takeNext()
    {
        List<WriteUnit> batch = null;
        synchronized (lock_)
        {
            while (running_ && queue_.isEmpty())
            {
                awaitQuietly();
            }
            if (!queue_.isEmpty())
            {
                batch = queue_.pollFirst();
                processing_ = true;
            }
        }
        return batch;
    }

    private void writeBatch(List<WriteUnit> batch)
    {
        try
        {
            commitToDisk(batch);
        }
        catch (IOException batchFailed)
        {
            GlobalSystem.error().writes(NAME, "Batch commit failed", batchFailed);
        }
        finally
        {
            synchronized (lock_)
            {
                processing_ = false;
                lock_.notifyAll();
            }
        }
    }

    /** Two-phase atomic batch: write every temp file first, then rename them all into place. */
    private void commitToDisk(List<WriteUnit> batch) throws IOException
    {
        List<Path> temps = new ArrayList<>();
        List<Path> targets = new ArrayList<>();
        try
        {
            for (WriteUnit unit : batch)
            {
                Path target = pathFor(unit.stream(), unit.day());
                Path dir = target.toAbsolutePath().getParent();
                Files.createDirectories(dir);
                Path tmp = Files.createTempFile(dir, target.getFileName().toString(), TMP_SUFFIX);
                Files.write(tmp, render(unit));
                temps.add(tmp);
                targets.add(target);
            }
            for (int i = 0; i < temps.size(); i++)
            {
                moveReplacing(temps.get(i), targets.get(i));
            }
        }
        finally
        {
            for (Path tmp : temps)
            {
                Files.deleteIfExists(tmp);
            }
        }
    }

    private static byte[] render(WriteUnit unit) throws IOException
    {
        byte[] bytes;
        if (unit.stream().format() == DataStream.Format.JSONL)
        {
            bytes = Json.toJsonlBytes(unit.payload());
        }
        else
        {
            bytes = Json.toPrettyBytes(unit.payload());
        }
        return bytes;
    }

    private JsonNode read(DataStream stream, Path file)
    {
        JsonNode payload;
        if (stream.format() == DataStream.Format.JSONL)
        {
            payload = readJsonlArray(file);
        }
        else
        {
            payload = Json.readObjectOrEmpty(file);
        }
        return payload;
    }

    private ArrayNode readJsonlArray(Path file)
    {
        ArrayNode array = Json.newArray();
        try
        {
            for (ObjectNode record : Json.readJsonl(file))
            {
                array.add(record);
            }
        }
        catch (IOException readFailed)
        {
            GlobalSystem.warning().writes(NAME, "Recovery read failed: " + file, readFailed);
        }
        return array;
    }

    /** Last write to a given (stream, day) wins; insertion order is otherwise preserved. */
    private static List<WriteUnit> coalesce(List<WriteUnit> batch)
    {
        Map<String, WriteUnit> byTarget = new LinkedHashMap<>();
        for (WriteUnit unit : batch)
        {
            byTarget.put(unit.stream().name() + '/' + unit.day(), unit);
        }
        return new ArrayList<>(byTarget.values());
    }

    private List<Path> recentDayFiles(DataStream stream, int limit)
    {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dataDir_))
        {
            try (Stream<Path> entries = Files.list(dataDir_))
            {
                files = entries
                        .filter(p -> matchesDayFile(p, stream))
                        .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                        .limit(limit)
                        .collect(Collectors.toList());
            }
            catch (IOException listFailed)
            {
                GlobalSystem.warning().writes(NAME, "Could not list " + dataDir_, listFailed);
            }
        }
        return files;
    }

    private static boolean matchesDayFile(Path file, DataStream stream)
    {
        String name = file.getFileName().toString();
        return name.startsWith(stream.prefix()) && name.endsWith(stream.suffix());
    }

    private static String dayOf(Path file, DataStream stream)
    {
        String name = file.getFileName().toString();
        return name.substring(stream.prefix().length(), name.length() - stream.suffix().length());
    }

    private Path pathFor(DataStream stream, String day)
    {
        return dataDir_.resolve(stream.prefix() + day + stream.suffix());
    }

    private static void moveReplacing(Path tmp, Path target) throws IOException
    {
        try
        {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException atomicUnsupported)
        {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void awaitQuietly()
    {
        try
        {
            lock_.wait();
        }
        catch (InterruptedException interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }
}

package com.finsent.analyse;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.io.LoadedDay;
import com.finsent.core.io.PersistenceService;
import com.finsent.core.registry.AnalysisRegistry;

/**
 * The analyser's persistence boundary for its own output (the {@code analysis_*.json} stream). It
 * owns a dedicated {@link PersistenceService} and an {@link AnalysisRegistry}, so interpreted data
 * never routes through the collector (which owns only collected data). Symmetric to the collector's
 * use of the persistence layer: {@link #recover} loads recent days into the registry, the
 * {@code record*} methods mutate it and commit the resulting write-units as one atomic batch, and
 * reads are served from memory.
 */
public final class AnalysisStore
{
    private final PersistenceService persistence_;
    private final AnalysisRegistry registry_ = new AnalysisRegistry();

    /** Create a store writing under {@code dataDir} (the same data directory the collector uses). */
    public AnalysisStore(Path dataDir)
    {
        persistence_ = new PersistenceService(dataDir);
    }

    /** Rebuild the registry's runtime state from the most recent {@code lookbackDays}. */
    public void recover(int lookbackDays)
    {
        registry_.hydrate(persistence_.load(registry_.stream(), lookbackDays));
    }

    /**
     * Lazily load one day's analysis records into the registry for on-demand reads (e.g. the
     * backfill's "already analysed?" check on an older day). A no-op when the day is already
     * resident or the file is absent.
     */
    public void recoverDay(String day)
    {
        if (!registry_.isResident(day))
        {
            LoadedDay loaded = persistence_.loadDay(registry_.stream(), day);
            if (loaded != null)
            {
                registry_.ensureDayResident(loaded);
            }
        }
    }

    /** Store (overwriting) the analysis record for a window and commit it atomically. */
    public void record(String day, String intervalKey, ObjectNode record)
    {
        persistence_.commit(registry_.putInterval(day, intervalKey, record));
    }

    /** Merge a scheduled-data-release alert (#21) into the window's interval and commit it atomically. */
    public void recordEconAlert(String day, String intervalKey, ObjectNode econAlert)
    {
        persistence_.commit(registry_.putEconAlert(day, intervalKey, econAlert));
    }

    /** The analysis record for an interval, or an empty object if absent. */
    public ObjectNode get(String day, String intervalKey)
    {
        return registry_.get(day, intervalKey);
    }

    /** Whether the interval already carries resonant articles (gates the macro-alert path). */
    public boolean hasResonant(String day, String intervalKey)
    {
        return registry_.hasResonant(day, intervalKey);
    }

    /** Block until all committed batches have been written. */
    public void flush()
    {
        persistence_.flush();
    }

    /** Flush outstanding writes and stop the persistence writer (shutdown hook). */
    public void shutdown()
    {
        persistence_.shutdown();
    }
}

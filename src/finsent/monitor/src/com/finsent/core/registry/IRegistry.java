package com.finsent.core.registry;

import java.util.List;

import com.finsent.core.io.DataStream;
import com.finsent.core.io.LoadedDay;

/**
 * A runtime, in-memory registry of one data type. A registry holds its data and enforces its
 * domain invariants (ids, dedup, watermarks, merges); it does not touch the filesystem. The
 * {@link com.finsent.core.io.PersistenceService} reads the data files and the registry rebuilds
 * its state from those raw records in {@link #hydrate} (the Java equivalent of the Python
 * {@code get_state()} recovery). Mutations return {@link com.finsent.core.io.WriteUnit}s for the
 * collector to commit; the registry never persists itself.
 */
public interface IRegistry
{
    /** The data stream this registry owns (its load source and the tag on its write-units). */
    DataStream stream();

    /**
     * Rebuild runtime state from the day payloads read by the persistence layer. Implementations
     * tolerate missing/partial payloads, matching the Python recovery which treats the data files
     * as best-effort sources of truth.
     */
    void hydrate(List<LoadedDay> days);

    /** Whether {@code day} is currently held in the in-memory working set. */
    boolean isResident(String day);

    /**
     * Add one already-loaded day to the working set for on-demand read access (lazy recovery of an
     * older day for re-analysis), if not already resident. Unlike {@link #hydrate}, this must not
     * disturb id/watermark recovery state &mdash; loading an older day must never regress the
     * monotonic article-id counter.
     */
    void ensureDayResident(LoadedDay loaded);
}

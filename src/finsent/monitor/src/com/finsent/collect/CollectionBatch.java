package com.finsent.collect;

import java.util.ArrayList;
import java.util.List;

import com.finsent.core.io.WriteUnit;

/**
 * The collector's per-cycle unit of work: it accumulates the {@link WriteUnit}s produced by the
 * registries as a cycle runs, so the whole cycle can be committed to disk as one atomic batch.
 * A fresh batch is started for each collection cycle (and the urgent poller keeps its own), so
 * the data fetched within a cycle is persisted together or not at all. The batch only collects
 * write-units; the {@link com.finsent.core.io.PersistenceService} performs the commit. Internal to
 * {@link FSCollector} &mdash; a cycle's accumulator, not part of the collector's public API.
 */
final class CollectionBatch
{
    private final List<WriteUnit> writes_ = new ArrayList<>();

    /** Add the write-unit(s) a registry mutation produced (an empty list is a no-op). */
    public void add(List<WriteUnit> units)
    {
        writes_.addAll(units);
    }

    /** The write-units accumulated so far, in the order they were produced. */
    public List<WriteUnit> writes()
    {
        return writes_;
    }

    public boolean isEmpty()
    {
        return writes_.isEmpty();
    }
}

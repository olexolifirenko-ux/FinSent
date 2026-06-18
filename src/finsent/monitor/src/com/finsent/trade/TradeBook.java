package com.finsent.trade;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.io.DataStream;
import com.finsent.core.io.PersistenceService;

/**
 * The trader's persistence boundary, symmetric to the analyser's {@code AnalysisStore}: it owns a
 * dedicated {@link PersistenceService} and a {@link TradeRegistry}, so trade data is written under
 * the same data directory the rest of the pipeline uses without routing through the collector or
 * analyser stores. {@link #recover} reloads the closed ledger and any still-open position from the
 * most recent days; the {@code record*} methods mutate the registry and commit the resulting
 * write-units as one atomic batch; reads are served from memory.
 */
public final class TradeBook
{
    private final PersistenceService persistence_;
    private final TradeRegistry registry_ = new TradeRegistry();

    /** Create a book writing under {@code dataDir} (the same data directory the collector uses). */
    public TradeBook(Path dataDir)
    {
        persistence_ = new PersistenceService(dataDir);
    }

    /** Reload the closed ledger and any open position from the most recent {@code lookbackDays}. */
    public void recover(int lookbackDays)
    {
        registry_.hydrateClosed(persistence_.load(DataStream.TRADES, lookbackDays));
        registry_.hydrateOpen(persistence_.load(DataStream.OPEN_POSITION, lookbackDays));
    }

    /** The recovered open position, or null when flat (adopted by the trader on startup). */
    public Position openPosition()
    {
        return Position.fromSnapshot(registry_.openSnapshot());
    }

    /** Persist the open-position snapshot (on entry and on every trail update) and commit atomically. */
    public void recordPosition(Position position)
    {
        persistence_.commit(registry_.setOpen(position.toSnapshot(), position.day()));
    }

    /** Append a closed trade to the ledger, flatten the snapshot, and commit atomically. */
    public void recordClose(Position position, ObjectNode closedTrade)
    {
        persistence_.commit(registry_.close(closedTrade, position.day()));
    }

    /** The closed trades recorded for {@code day} (for the {@code trade status} P&amp;L summary). */
    public ArrayNode closedForDay(String day)
    {
        return registry_.closedForDay(day);
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

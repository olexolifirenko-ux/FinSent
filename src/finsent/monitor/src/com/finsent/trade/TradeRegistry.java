package com.finsent.trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.io.DataStream;
import com.finsent.core.io.LoadedDay;
import com.finsent.core.io.WriteUnit;

/**
 * In-memory book of the trader's closed-trade ledger ({@link DataStream#TRADES}, an array per day)
 * and its single current open position ({@link DataStream#OPEN_POSITION}, one object per day, empty
 * when flat). Pure state with the rest of the pipeline's Unit-of-Work contract: mutations change the
 * map and return the affected day(s) as {@link WriteUnit}s for the {@link TradeBook} to commit; it
 * never touches disk. There is at most one open position at a time (the v1 single-net-position cap).
 */
public final class TradeRegistry
{
    private final Object lock_ = new Object();
    private final Map<String, ArrayNode> closedByDay_ = new HashMap<>();
    private ObjectNode openSnapshot_; // null when flat
    private String openDay_;          // the day-file the open snapshot lives in

    /** Hydrate the closed-trade ledger from recent {@link DataStream#TRADES} day-files. */
    public void hydrateClosed(List<LoadedDay> days)
    {
        synchronized (lock_)
        {
            for (LoadedDay loaded : days)
            {
                if (loaded.payload() instanceof ArrayNode)
                {
                    closedByDay_.put(loaded.day(), (ArrayNode) loaded.payload());
                }
            }
        }
    }

    /** Adopt the most-recent non-flat open-position snapshot from recent day-files (restart recovery). */
    public void hydrateOpen(List<LoadedDay> days)
    {
        synchronized (lock_)
        {
            for (LoadedDay loaded : days)
            {
                adoptIfNewer(loaded);
            }
        }
    }

    private void adoptIfNewer(LoadedDay loaded)
    {
        if (loaded.payload() instanceof ObjectNode && ((ObjectNode) loaded.payload()).hasNonNull("side"))
        {
            ObjectNode snapshot = (ObjectNode) loaded.payload();
            boolean newer = openSnapshot_ == null
                    || snapshot.path("opened_at").asText("").compareTo(openSnapshot_.path("opened_at").asText("")) > 0;
            if (newer)
            {
                openSnapshot_ = snapshot;
                openDay_ = loaded.day();
            }
        }
    }

    /** Store the current open-position snapshot (used on entry and on every trail update). */
    public List<WriteUnit> setOpen(ObjectNode snapshot, String day)
    {
        synchronized (lock_)
        {
            openSnapshot_ = snapshot.deepCopy();
            openDay_ = day;
        }
        return List.of(new WriteUnit(DataStream.OPEN_POSITION, day, snapshot.deepCopy()));
    }

    /** Append a closed trade to {@code tradeDay}'s ledger and flatten the open-position snapshot. */
    public List<WriteUnit> close(ObjectNode closedTrade, String tradeDay)
    {
        String openDay;
        ArrayNode ledger;
        synchronized (lock_)
        {
            closedByDay_.computeIfAbsent(tradeDay, d -> Json.newArray()).add(closedTrade.deepCopy());
            ledger = closedByDay_.get(tradeDay).deepCopy();
            openDay = openDay_ != null ? openDay_ : tradeDay;
            openSnapshot_ = null;
            openDay_ = null;
        }
        List<WriteUnit> writes = new ArrayList<>();
        writes.add(new WriteUnit(DataStream.TRADES, tradeDay, ledger));
        writes.add(new WriteUnit(DataStream.OPEN_POSITION, openDay, Json.newObject()));
        return writes;
    }

    /** The current open-position snapshot, or null when flat (a private copy). */
    public ObjectNode openSnapshot()
    {
        synchronized (lock_)
        {
            return openSnapshot_ == null ? null : openSnapshot_.deepCopy();
        }
    }

    /** The closed-trade ledger for {@code day} (an empty array when none; a private copy). */
    public ArrayNode closedForDay(String day)
    {
        synchronized (lock_)
        {
            ArrayNode ledger = closedByDay_.get(day);
            return ledger == null ? Json.newArray() : ledger.deepCopy();
        }
    }
}

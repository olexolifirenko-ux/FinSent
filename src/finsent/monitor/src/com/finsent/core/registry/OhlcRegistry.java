package com.finsent.core.registry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.Times;
import com.finsent.core.io.DataStream;
import com.finsent.core.io.LoadedDay;
import com.finsent.core.io.WriteUnit;

/**
 * Registry for BTC OHLC bars, stored as a flat per-day time series &mdash; one {@code {ts,o,h,l,c,v}}
 * bar per line in {@code btc_price_*.jsonl}, sorted by {@code ts} and deduplicated (newest fetch wins
 * on a re-pull of a still-forming candle). Replaces the old per-interval strip layout, which stored a
 * ~40-minute strip under every 10-minute window key and so duplicated each 1-minute bar ~4&times;.
 *
 * <p>Bars are routed to the file of their own {@code ts} day on {@link #merge}, so a bar near midnight
 * lands in its real day's file (never duplicated across two days); reads ({@link #barsInRange}) span
 * the day boundary transparently. Consumers ask for the bars around a timestamp; the per-window
 * bucketing the analyser used to slice is now just a range query.
 */
public final class OhlcRegistry implements IRegistry
{
    private static final String TS = "ts";
    private static final long MS_PER_MIN = 60_000L;

    private final Object lock_ = new Object();
    // day -> (ts -> bar), each day sorted by ts and deduplicated.
    private final Map<String, TreeMap<String, ObjectNode>> byDay_ = new HashMap<>();

    @Override
    public DataStream stream()
    {
        return DataStream.OHLC;
    }

    @Override
    public void hydrate(List<LoadedDay> days)
    {
        synchronized (lock_)
        {
            for (LoadedDay loaded : days)
            {
                if (loaded.payload() instanceof ArrayNode)
                {
                    ingest(loaded.day(), (ArrayNode) loaded.payload());
                }
            }
        }
    }

    @Override
    public boolean isResident(String day)
    {
        synchronized (lock_)
        {
            return byDay_.containsKey(day);
        }
    }

    @Override
    public void ensureDayResident(LoadedDay loaded)
    {
        synchronized (lock_)
        {
            if (loaded.payload() instanceof ArrayNode && !byDay_.containsKey(loaded.day()))
            {
                ingest(loaded.day(), (ArrayNode) loaded.payload());
            }
        }
    }

    /**
     * Merge bars into the series, each routed to the file of its own {@code ts} day (dedup by ts,
     * newest wins). Returns one write-unit per day the merge changed.
     */
    public List<WriteUnit> merge(ArrayNode bars)
    {
        Set<String> changedDays = new LinkedHashSet<>();
        synchronized (lock_)
        {
            for (JsonNode bar : bars)
            {
                String ts = bar.path(TS).asText("");
                if (!ts.isEmpty() && bar instanceof ObjectNode)
                {
                    String day = Times.dayOf(ts);
                    byDay_.computeIfAbsent(day, d -> new TreeMap<>()).put(ts, ((ObjectNode) bar).deepCopy());
                    changedDays.add(day);
                }
            }
        }
        List<WriteUnit> writes = new ArrayList<>();
        for (String day : changedDays)
        {
            writes.add(writeUnitForDay(day));
        }
        return writes;
    }

    /** Bars with {@code ts} in {@code [fromTs, toTs]} (inclusive ISO instants), sorted; copies. Spans days. */
    public ArrayNode barsInRange(String fromTs, String toTs)
    {
        ArrayNode result = Json.newArray();
        synchronized (lock_)
        {
            String fromDay = Times.dayOf(fromTs);
            collectRange(fromDay, fromTs, toTs, result);
            String toDay = Times.dayOf(toTs);
            if (!toDay.equals(fromDay))
            {
                collectRange(toDay, fromTs, toTs, result);
            }
        }
        return result;
    }

    /**
     * Max-high and min-low over every bar in {@code [fromTs, toTs]} across ALL resident days in the span
     * &mdash; unlike {@link #barsInRange}, which only visits the from-day and to-day buckets (built for
     * sub-day strips) and would skip the middle of a multi-day span. Returns {@code {high, low}}, or an
     * empty object when no bars fall in range. Used for the multi-day {@code btc_regime} read; tolerant of
     * partial residency (computes over whatever days are loaded &mdash; a shorter lookback, never invented
     * data).
     */
    public ObjectNode extremes(String fromTs, String toTs)
    {
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        boolean found = false;
        String fromDay = Times.dayOf(fromTs);
        String toDay = Times.dayOf(toTs);
        synchronized (lock_)
        {
            for (Map.Entry<String, TreeMap<String, ObjectNode>> entry : byDay_.entrySet())
            {
                String day = entry.getKey();
                if (day.compareTo(fromDay) >= 0 && day.compareTo(toDay) <= 0)
                {
                    for (ObjectNode bar : entry.getValue().subMap(fromTs, true, toTs, true).values())
                    {
                        high = Math.max(high, bar.path("h").asDouble());
                        low = Math.min(low, bar.path("l").asDouble());
                        found = true;
                    }
                }
            }
        }
        ObjectNode result = Json.newObject();
        if (found)
        {
            result.put("high", high);
            result.put("low", low);
        }
        return result;
    }

    /** The newest bar's {@code ts} across all resident days, or {@code ""} when none (for incremental fetch). */
    public String latestTs()
    {
        String latest = "";
        synchronized (lock_)
        {
            for (TreeMap<String, ObjectNode> series : byDay_.values())
            {
                if (!series.isEmpty() && series.lastKey().compareTo(latest) > 0)
                {
                    latest = series.lastKey();
                }
            }
        }
        return latest;
    }

    /** Epoch-ms of the bar one minute after {@code latestTs()} (0 when none), bounding incremental fetches. */
    public long nextBarStartMs()
    {
        String latest = latestTs();
        return latest.isEmpty() ? 0L : Times.parseIso(latest).toEpochMilli() + MS_PER_MIN;
    }

    private void collectRange(String day, String fromTs, String toTs, ArrayNode out)
    {
        TreeMap<String, ObjectNode> series = byDay_.get(day);
        if (series != null)
        {
            for (ObjectNode bar : series.subMap(fromTs, true, toTs, true).values())
            {
                out.add(bar.deepCopy());
            }
        }
    }

    private void ingest(String day, ArrayNode bars)
    {
        TreeMap<String, ObjectNode> series = byDay_.computeIfAbsent(day, d -> new TreeMap<>());
        for (JsonNode bar : bars)
        {
            String ts = bar.path(TS).asText("");
            if (!ts.isEmpty() && bar instanceof ObjectNode)
            {
                series.put(ts, ((ObjectNode) bar).deepCopy());
            }
        }
    }

    private WriteUnit writeUnitForDay(String day)
    {
        ArrayNode payload = Json.newArray();
        synchronized (lock_)
        {
            TreeMap<String, ObjectNode> series = byDay_.get(day);
            if (series != null)
            {
                for (ObjectNode bar : series.values())
                {
                    payload.add(bar.deepCopy());
                }
            }
        }
        return new WriteUnit(DataStream.OHLC, day, payload);
    }
}

package com.finsent.core.registry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.io.DataStream;
import com.finsent.core.io.LoadedDay;
import com.finsent.core.io.WriteUnit;

/**
 * Base for registries whose data is a per-day JSON object keyed by interval ({@code HH:MM})
 * &mdash; the macro, options and OHLC context files. Holds an in-memory map of recent day
 * objects; mutations change that map and return the affected day as a {@link WriteUnit} for the
 * collector to commit. Reads are served from memory only (the working set hydrated at startup);
 * historical days outside that set are reloaded by the collector through the persistence layer,
 * not by the registry.
 */
abstract class AbstractDayFileRegistry implements IRegistry
{
    private final DataStream stream_;
    private final Object lock_ = new Object();
    private final Map<String, ObjectNode> byDay_ = new HashMap<>();

    AbstractDayFileRegistry(DataStream stream)
    {
        stream_ = stream;
    }

    @Override
    public DataStream stream()
    {
        return stream_;
    }

    @Override
    public void hydrate(List<LoadedDay> days)
    {
        synchronized (lock_)
        {
            for (LoadedDay loaded : days)
            {
                if (loaded.payload() instanceof ObjectNode)
                {
                    byDay_.put(loaded.day(), (ObjectNode) loaded.payload());
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
            if (loaded.payload() instanceof ObjectNode && !byDay_.containsKey(loaded.day()))
            {
                byDay_.put(loaded.day(), (ObjectNode) loaded.payload());
            }
        }
    }

    /**
     * Set {@code value} under {@code intervalKey} for {@code day}. When {@code skipIfPresent} is
     * true and the key already exists, nothing changes and no write-unit is returned (the Python
     * "already stored for interval" guard). Otherwise the updated day is returned for commit.
     */
    protected List<WriteUnit> setInterval(String day, String intervalKey, ObjectNode value, boolean skipIfPresent)
    {
        boolean stored;
        synchronized (lock_)
        {
            ObjectNode data = byDay_.computeIfAbsent(day, d -> Json.newObject());
            if (skipIfPresent && data.has(intervalKey))
            {
                stored = false;
            }
            else
            {
                data.set(intervalKey, value);
                stored = true;
            }
        }
        return stored ? writeUnitsFor(day) : List.of();
    }

    /**
     * Apply {@code mutator} to the day object under the registry lock; return the day as a
     * write-unit only if it reports a change ({@code true}). Lets subclasses express conditional
     * writes (e.g. "store strip only if not already present with bars").
     */
    protected List<WriteUnit> updateDay(String day, Predicate<ObjectNode> mutator)
    {
        boolean changed;
        synchronized (lock_)
        {
            changed = mutator.test(byDay_.computeIfAbsent(day, d -> Json.newObject()));
        }
        return changed ? writeUnitsFor(day) : List.of();
    }

    /** Run a read on the day object (an empty object when the day is not in memory). */
    protected <R> R readDay(String day, Function<ObjectNode, R> reader)
    {
        ObjectNode data;
        synchronized (lock_)
        {
            JsonNode held = byDay_.get(day);
            data = held == null ? Json.newObject() : held.deepCopy();
        }
        return reader.apply(data);
    }

    private List<WriteUnit> writeUnitsFor(String day)
    {
        ObjectNode copy;
        synchronized (lock_)
        {
            copy = byDay_.get(day).deepCopy();
        }
        return List.of(new WriteUnit(stream_, day, copy));
    }
}

package com.finsent.core.registry;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.io.DataStream;
import com.finsent.core.io.WriteUnit;

/**
 * Registry for per-interval context snapshots stored one object per day, keyed by
 * {@code HH:MM}. Backs the macro ({@code macro_context_*.json}) and options
 * ({@code options_context_*.json}) context files. Ports the store/load helpers of the
 * Python collector: a snapshot is written once per interval (skip if already present),
 * and a key range can be read back for multi-interval delta computation.
 */
public final class IntervalSnapshotRegistry extends AbstractDayFileRegistry
{
    public IntervalSnapshotRegistry(DataStream stream)
    {
        super(stream);
    }

    /**
     * Store a snapshot for the interval if none exists yet. Returns the write-unit(s) for the
     * changed day, or an empty list when a snapshot already exists for the interval.
     */
    public List<WriteUnit> putIfAbsent(String day, String intervalKey, ObjectNode snapshot)
    {
        return setInterval(day, intervalKey, snapshot, true);
    }

    /** The snapshot for an interval, or an empty object if absent. */
    public ObjectNode get(String day, String intervalKey)
    {
        return readDay(day, data ->
        {
            JsonNode value = data.get(intervalKey);
            return value instanceof ObjectNode ? (ObjectNode) value : Json.newObject();
        });
    }

    /**
     * All snapshots whose interval key is within {@code [fromKey, toKey]} inclusive,
     * sorted by key. Ports {@code collect.load_options_snapshots_range}.
     */
    public List<Map.Entry<String, JsonNode>> range(String day, String fromKey, String toKey)
    {
        return readDay(day, data -> collectRange(data, fromKey, toKey));
    }

    private static List<Map.Entry<String, JsonNode>> collectRange(ObjectNode data, String fromKey, String toKey)
    {
        List<Map.Entry<String, JsonNode>> result = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = data.fields();
        while (fields.hasNext())
        {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().compareTo(fromKey) >= 0 && field.getKey().compareTo(toKey) <= 0)
            {
                result.add(field);
            }
        }
        result.sort(Map.Entry.comparingByKey());
        return result;
    }
}

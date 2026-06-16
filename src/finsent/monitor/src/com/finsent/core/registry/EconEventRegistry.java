package com.finsent.core.registry;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.io.DataStream;
import com.finsent.core.io.WriteUnit;

/**
 * Registry for resolved scheduled-economic events (#21): one JSON object per day keyed by event name in
 * {@code econ_actuals_*.json}, each value the mechanical {@code EconEventSignals} signal. An event is
 * stored once (the day's first resolution wins), so {@link #resolved} guards against re-fetching.
 */
public final class EconEventRegistry extends AbstractDayFileRegistry
{
    public EconEventRegistry()
    {
        super(DataStream.ECON);
    }

    /** True once {@code eventName} has been resolved + stored for {@code day} (so we do not re-fetch). */
    public boolean resolved(String day, String eventName)
    {
        return readDay(day, data -> data.has(eventName));
    }

    /** Store a resolved event's signal under its name (once per day). */
    public List<WriteUnit> store(String day, String eventName, ObjectNode signal)
    {
        return setInterval(day, eventName, signal, true);
    }

    /** The resolved signal for an event, or an empty object when absent. */
    public ObjectNode get(String day, String eventName)
    {
        return readDay(day, data ->
        {
            JsonNode value = data.get(eventName);
            return value instanceof ObjectNode ? (ObjectNode) value.deepCopy() : Json.newObject();
        });
    }
}

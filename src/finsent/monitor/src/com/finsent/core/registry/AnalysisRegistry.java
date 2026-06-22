package com.finsent.core.registry;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.io.DataStream;
import com.finsent.core.io.WriteUnit;

/**
 * Registry for the analyser's per-interval output, stored one JSON object per day keyed by
 * {@code HH:MM} ({@code analysis_*.json}). Unlike the context registries an interval is
 * <b>overwritten</b> on each analysis (the latest call for a window supersedes), matching the
 * Python analyser which rewrites {@code day_analysis[interval_key]} wholesale. A scheduled-release
 * econ alert is merged into the interval as an {@code econ_alert} sub-key (creating a skeleton
 * interval when none exists yet).
 */
public final class AnalysisRegistry extends AbstractDayFileRegistry
{
    public AnalysisRegistry()
    {
        super(DataStream.ANALYSIS);
    }

    /** Store (overwriting) the analysis record for an interval; returns the changed day's write-unit. */
    public List<WriteUnit> putInterval(String day, String intervalKey, ObjectNode record)
    {
        return setInterval(day, intervalKey, record, false);
    }

    /**
     * Merge a scheduled-data-release alert (#21) into the interval under the {@code econ_alert}
     * sub-key, creating a skeleton interval when the window has no analysis entry yet (the release
     * carries no resonant news, so its window may have nothing else).
     */
    public List<WriteUnit> putEconAlert(String day, String intervalKey, ObjectNode econAlert)
    {
        return attach(day, intervalKey, "econ_alert", econAlert);
    }

    /** Merge {@code payload} under {@code subKey} of the interval, creating a skeleton when absent. */
    private List<WriteUnit> attach(String day, String intervalKey, String subKey, ObjectNode payload)
    {
        return updateDay(day, dayData ->
        {
            JsonNode existing = dayData.get(intervalKey);
            ObjectNode interval;
            if (existing instanceof ObjectNode)
            {
                interval = (ObjectNode) existing;
            }
            else
            {
                interval = skeleton(payload.path("analyzed_at").asText());
                dayData.set(intervalKey, interval);
            }
            interval.set(subKey, payload);
            return true;
        });
    }

    /** The analysis record for an interval, or an empty object if absent. */
    public ObjectNode get(String day, String intervalKey)
    {
        return readDay(day, data ->
        {
            JsonNode value = data.get(intervalKey);
            return value instanceof ObjectNode ? (ObjectNode) value : Json.newObject();
        });
    }

    /** Whether the interval has at least one resonant article (Python's {@code resonant_article_ids} guard). */
    public boolean hasResonant(String day, String intervalKey)
    {
        return readDay(day, data ->
        {
            JsonNode interval = data.get(intervalKey);
            JsonNode ids = interval == null ? null : interval.get("resonant_article_ids");
            return ids != null && ids.size() > 0;
        });
    }

    /** The empty interval record a macro-only alert attaches to (Python's skeleton). */
    private static ObjectNode skeleton(String analyzedAt)
    {
        ObjectNode interval = Json.newObject();
        interval.put("analyzed_at", analyzedAt);
        interval.set("article_ids", Json.newArray());
        interval.set("screener", Json.newArray());
        interval.putNull("prediction_record");
        return interval;
    }
}

package com.finsent.collect;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import com.finsent.core.Json;
import com.finsent.util.GlobalSystem;

/**
 * Loads the static econ-event definitions catalog ({@code cfg/econ_definitions.json}, BACKLOG #21): the
 * stable per-event fields -- BLS {@code series}/{@code kind}, {@code unit}, the {@code hot_direction}
 * polarity and the {@code inline_band}/{@code high_band} surprise thresholds -- keyed by event name. The
 * volatile release time + consensus live in the per-day schedule ({@link EconScheduleConfig}) and are
 * joined to a definition by name at arm time. Re-read each arm; a malformed entry is skipped, and an
 * absent/unreadable file yields an empty map (the module self-disables until the catalog exists).
 */
public final class EconDefinitionsConfig
{
    private static final String NAME = "EconDefinitionsConfig";

    private EconDefinitionsConfig()
    {
    }

    public static Map<String, EconEventDef> load(File file)
    {
        Map<String, EconEventDef> defs = new LinkedHashMap<>();
        if (file != null && file.isFile())
        {
            readInto(file, defs);
        }
        return defs;
    }

    private static void readInto(File file, Map<String, EconEventDef> defs)
    {
        try
        {
            JsonNode root = Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            for (JsonNode node : root)
            {
                EconEventDef def = parse(node);
                if (def != null)
                {
                    defs.put(def.name(), def);
                }
            }
        }
        catch (IOException | RuntimeException badFile)
        {
            GlobalSystem.warning().writes(NAME, "Cannot read " + file.getName(), badFile);
        }
    }

    private static EconEventDef parse(JsonNode node)
    {
        EconEventDef def = null;
        String name = node.path("name").asText("");
        if (!name.isEmpty())
        {
            def = new EconEventDef(name, node.path("series").asText(""), node.path("kind").asText("level"),
                    node.path("unit").asText("%"), node.path("hot_direction").asText("bearish"),
                    node.path("inline_band").asDouble(0.0), node.path("high_band").asDouble(Double.MAX_VALUE));
        }
        return def;
    }
}

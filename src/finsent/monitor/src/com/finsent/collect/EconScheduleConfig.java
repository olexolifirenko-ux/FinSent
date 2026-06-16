package com.finsent.collect;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.finsent.core.Json;
import com.finsent.core.Times;
import com.finsent.util.GlobalSystem;

/**
 * Loads one day's econ release schedule ({@code data/<date>/econ_schedule_<date>.json}, BACKLOG #21): the
 * hand-entered {@code {name, release, consensus}} rows for releases on that date. A plain input file
 * (read, never written by the app), joined to the static {@link EconEventDef} catalog by name when the
 * scheduler arms the day. A malformed entry is skipped; an absent/unreadable file yields an empty list.
 */
public final class EconScheduleConfig
{
    private static final String NAME = "EconScheduleConfig";

    private EconScheduleConfig()
    {
    }

    public static List<EconRelease> load(File file)
    {
        List<EconRelease> releases = new ArrayList<>();
        if (file != null && file.isFile())
        {
            readInto(file, releases);
        }
        return releases;
    }

    private static void readInto(File file, List<EconRelease> releases)
    {
        try
        {
            JsonNode root = Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            for (JsonNode node : root)
            {
                EconRelease release = parse(node);
                if (release != null)
                {
                    releases.add(release);
                }
            }
        }
        catch (IOException | RuntimeException badFile)
        {
            GlobalSystem.warning().writes(NAME, "Cannot read " + file.getName(), badFile);
        }
    }

    private static EconRelease parse(JsonNode node)
    {
        EconRelease release = null;
        String name = node.path("name").asText("");
        String releaseTime = node.path("release").asText("");
        if (!name.isEmpty() && !releaseTime.isEmpty())
        {
            try
            {
                release = new EconRelease(name, Times.parseIso(releaseTime), node.path("consensus").asDouble());
            }
            catch (RuntimeException malformed)
            {
                release = null; // a bad release timestamp drops just this row, not the whole file
            }
        }
        return release;
    }
}

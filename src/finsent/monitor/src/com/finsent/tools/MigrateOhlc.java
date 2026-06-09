package com.finsent.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.core.Json;
import com.finsent.core.Times;

/**
 * One-time migration of the legacy per-interval OHLC files to the flat JSONL series.
 *
 * <p>Old layout ({@code btc_price_*.json}): a per-day object keyed by {@code HH:MM}, each value
 * {@code {fetched_at, bars[]}} &mdash; the same ~40-minute strip stored under every 10-minute window,
 * so each 1-minute bar was duplicated ~4&times;. New layout ({@code btc_price_*.jsonl}): one
 * {@code {ts,o,h,l,c,v}} bar per line, sorted by {@code ts}, deduplicated, and routed to the file of the
 * bar's own day. This tool reads every {@code btc_price_*.json} in the data dir, de-duplicates all bars
 * by {@code ts}, writes the {@code .jsonl} files, and renames each consumed {@code .json} to
 * {@code .json.bak}. Pure file I/O &mdash; no framework bootstrap.
 *
 * <p>Usage: {@code java -cp <classpath> com.finsent.tools.MigrateOhlc <dataDir>}
 */
public final class MigrateOhlc
{
    private static final String TS = "ts";

    private MigrateOhlc()
    {
    }

    public static void main(String[] args) throws IOException
    {
        File dir = new File(args.length > 0 ? args[0] : "data");
        File[] files = dir.listFiles((d, name) -> name.startsWith("btc_price_") && name.endsWith(".json"));
        if (files == null || files.length == 0)
        {
            System.out.println("No legacy btc_price_*.json in " + dir.getAbsolutePath() + " -- nothing to migrate.");
        }
        else
        {
            migrate(dir, files);
        }
    }

    private static void migrate(File dir, File[] files) throws IOException
    {
        Map<String, TreeMap<String, ObjectNode>> byDay = new TreeMap<>();
        int sourceBars = 0;
        for (File file : files)
        {
            sourceBars += ingest(file, byDay);
        }
        int written = write(dir, byDay);
        for (File file : files)
        {
            Files.move(file.toPath(), new File(dir, file.getName() + ".bak").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.printf("Migrated %d file(s): %d source bar-entries -> %d unique bar(s) across %d day-file(s)."
                + " Old files renamed *.json.bak.%n", files.length, sourceBars, written, byDay.size());
    }

    /** Read one legacy file, routing every window's bars into {@code byDay} keyed by the bar's ts day. */
    private static int ingest(File file, Map<String, TreeMap<String, ObjectNode>> byDay) throws IOException
    {
        int count = 0;
        JsonNode root = Json.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        for (JsonNode interval : root) // each value is {fetched_at, bars[]}
        {
            for (JsonNode bar : interval.path("bars"))
            {
                String ts = bar.path(TS).asText("");
                if (!ts.isEmpty() && bar instanceof ObjectNode)
                {
                    byDay.computeIfAbsent(Times.dayOf(ts), d -> new TreeMap<>())
                            .put(ts, ((ObjectNode) bar).deepCopy());
                    count++;
                }
            }
        }
        return count;
    }

    private static int write(File dir, Map<String, TreeMap<String, ObjectNode>> byDay) throws IOException
    {
        int total = 0;
        for (Map.Entry<String, TreeMap<String, ObjectNode>> entry : byDay.entrySet())
        {
            StringBuilder body = new StringBuilder();
            for (ObjectNode bar : entry.getValue().values())
            {
                body.append(Json.toCompactString(bar)).append('\n');
                total++;
            }
            Files.writeString(new File(dir, "btc_price_" + entry.getKey() + ".jsonl").toPath(),
                    body.toString(), StandardCharsets.UTF_8);
        }
        return total;
    }
}

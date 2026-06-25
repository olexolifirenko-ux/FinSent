package com.finsent.collect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.analyse.FastMoveReady;
import com.finsent.core.Json;
import com.finsent.core.Times;
import com.finsent.core.event.IEventListener;
import com.finsent.util.GlobalSystem;

/**
 * Alert-only telemetry sink for the FastMove observation phase: appends every {@link FastMoveReady} fire
 * (of any conviction, including {@code skip}) to {@code <dataDir>/<day>/fastmove_<day>.jsonl}, one compact
 * JSON line per fire. Decoupled from the poller and the trader -- it just records what the detector fired,
 * so the alert-only run produces a clean, parseable record to score against the collected price (see
 * {@code build/fastmove_score.py}) rather than grepping logs. Runs on the event-bus dispatch thread; fires
 * are rare, so the small synchronous append never meaningfully stalls delivery, and a failed write is
 * isolated (logged) -- telemetry must never break the pipeline.
 */
public final class FastMoveRecorder implements IEventListener<FastMoveReady>
{
    private static final String NAME = "FastMoveRecorder";

    private final Path dataDir_;

    public FastMoveRecorder(Path dataDir)
    {
        dataDir_ = dataDir;
    }

    @Override
    public void onEvent(FastMoveReady signal)
    {
        try
        {
            append(signal);
        }
        catch (IOException recordFailed)
        {
            // Telemetry is best-effort: a failed append must not break event delivery.
            GlobalSystem.error().writes(NAME, "FastMove fire not recorded (" + signal.day() + " "
                    + signal.intervalKey() + ")", recordFailed);
        }
    }

    private void append(FastMoveReady signal) throws IOException
    {
        ObjectNode line = Json.newObject();
        line.put("ts", Times.formatUtcIso(signal.firedAt()));
        line.put("interval_key", signal.intervalKey());
        line.put("direction", signal.direction());
        line.put("conviction", signal.conviction().label());
        line.put("magnitude_pct", signal.magnitudePct());
        line.put("r2", signal.r2());
        line.put("span_min", signal.spanMinutes());
        line.put("setup", signal.setup());
        if (signal.anchorPrice() != null)
        {
            line.put("price", signal.anchorPrice());
        }
        Path file = dataDir_.resolve(signal.day()).resolve("fastmove_" + signal.day() + ".jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.toCompactString(line) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}

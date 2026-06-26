package com.finsent.collect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.fasterxml.jackson.databind.node.ObjectNode;

import com.finsent.analyse.CompressionWarning;
import com.finsent.core.Json;
import com.finsent.core.Times;
import com.finsent.core.event.IEventListener;
import com.finsent.util.GlobalSystem;

/**
 * Telemetry sink for the funding-compression pre-move early-warning: appends every {@link CompressionWarning}
 * to {@code <dataDir>/<day>/compression_<day>.jsonl}, one compact JSON line per episode. Decoupled from the
 * poller (mirrors {@link FastMoveRecorder}). The point is measurement: joined against {@code fastmove_<day>.jsonl}
 * it yields the KPI that matters for earliness -- the LEAD TIME between a compression warning and the
 * subsequent FastMove fire in the primed direction (and how often compression fizzles with no fire). Runs on
 * the event-bus thread; a failed append is isolated (logged), never breaks delivery.
 */
public final class CompressionRecorder implements IEventListener<CompressionWarning>
{
    private static final String NAME = "CompressionRecorder";

    private final Path dataDir_;

    public CompressionRecorder(Path dataDir)
    {
        dataDir_ = dataDir;
    }

    @Override
    public void onEvent(CompressionWarning warning)
    {
        try
        {
            append(warning);
        }
        catch (IOException recordFailed)
        {
            GlobalSystem.error().writes(NAME, "compression warning not recorded (" + warning.day() + " "
                    + warning.intervalKey() + ")", recordFailed);
        }
    }

    private void append(CompressionWarning warning) throws IOException
    {
        ObjectNode line = Json.newObject();
        line.put("ts", Times.formatUtcIso(warning.firedAt()));
        line.put("interval_key", warning.intervalKey());
        line.put("primed_direction", warning.primedDirection());
        line.put("funding_drop_pct", warning.fundingDropPct());
        if (warning.anchorPrice() != null)
        {
            line.put("price", warning.anchorPrice());
        }
        Path file = dataDir_.resolve(warning.day()).resolve("compression_" + warning.day() + ".jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.toCompactString(line) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}

package com.finsent.collect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.finsent.analyse.FastMoveReady;
import com.finsent.analyse.signal.Conviction;
import com.finsent.core.Json;

/**
 * Verifies {@link FastMoveRecorder} writes each fire as one compact JSON line to
 * {@code <dataDir>/<day>/fastmove_<day>.jsonl}, with the expected fields, and appends across fires.
 */
public class FastMoveRecorder_utest
{
    private Path dir_;

    @Before
    public void setUp() throws IOException
    {
        dir_ = Files.createTempDirectory("fs-fastmove-recorder-utest");
    }

    @After
    public void tearDown() throws IOException
    {
        try (Stream<Path> paths = Files.walk(dir_))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(FastMoveRecorder_utest::deleteQuietly);
        }
    }

    @Test
    public void recordsAFireAsAJsonlLine() throws IOException
    {
        new FastMoveRecorder(dir_).onEvent(fire("13:20", "bearish", Conviction.FULL, -1.52, 30, 61776.0));

        List<String> lines = Files.readAllLines(dir_.resolve("20260624").resolve("fastmove_20260624.jsonl"));
        assertEquals(1, lines.size());
        ObjectNode line = (ObjectNode) Json.parse(lines.get(0));
        assertEquals("2026-06-24T13:20:00Z", line.path("ts").asText());
        assertEquals("13:20", line.path("interval_key").asText());
        assertEquals("bearish", line.path("direction").asText());
        assertEquals("full", line.path("conviction").asText());
        assertEquals(-1.52, line.path("magnitude_pct").asDouble(), 1e-9);
        assertEquals(30, line.path("span_min").asInt());
        assertEquals(1.8, line.path("velocity_ratio").asDouble(), 1e-9);
        assertEquals(61776.0, line.path("price").asDouble(), 1e-9);
    }

    @Test
    public void appendsMultipleFiresToTheSameDayFile() throws IOException
    {
        FastMoveRecorder recorder = new FastMoveRecorder(dir_);
        recorder.onEvent(fire("13:20", "bearish", Conviction.FULL, -1.52, 30, 61776.0));
        recorder.onEvent(fire("16:50", "bearish", Conviction.REDUCED, -1.10, 15, 59661.0));

        List<String> lines = Files.readAllLines(dir_.resolve("20260624").resolve("fastmove_20260624.jsonl"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"conviction\":\"full\""));
        assertTrue(lines.get(1).contains("\"conviction\":\"reduced\""));
    }

    private static FastMoveReady fire(String key, String direction, Conviction conviction, double magnitude,
            int span, double price)
    {
        Instant firedAt = Instant.parse("2026-06-24T" + key + ":00Z");
        return new FastMoveReady("20260624", key, direction, conviction, price, magnitude, 0.85, span, 1.8, "", firedAt);
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored)
        {
            // Best-effort temp cleanup; a leftover file must not fail the test.
        }
    }
}

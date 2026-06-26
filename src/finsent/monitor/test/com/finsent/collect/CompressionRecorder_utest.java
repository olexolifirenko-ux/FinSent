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

import com.finsent.analyse.CompressionWarning;
import com.finsent.core.Json;

/**
 * Verifies {@link CompressionRecorder} writes each pre-move warning as one compact JSON line to
 * {@code <dataDir>/<day>/compression_<day>.jsonl}, with the expected fields, and appends across episodes.
 */
public class CompressionRecorder_utest
{
    private Path dir_;

    @Before
    public void setUp() throws IOException
    {
        dir_ = Files.createTempDirectory("fs-compression-recorder-utest");
    }

    @After
    public void tearDown() throws IOException
    {
        try (Stream<Path> paths = Files.walk(dir_))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(CompressionRecorder_utest::deleteQuietly);
        }
    }

    @Test
    public void recordsAWarningAsAJsonlLine() throws IOException
    {
        new CompressionRecorder(dir_).onEvent(warning("13:00", "bearish", 66.67, 62000.0));

        List<String> lines = Files.readAllLines(dir_.resolve("20260624").resolve("compression_20260624.jsonl"));
        assertEquals(1, lines.size());
        ObjectNode line = (ObjectNode) Json.parse(lines.get(0));
        assertEquals("2026-06-24T13:00:00Z", line.path("ts").asText());
        assertEquals("13:00", line.path("interval_key").asText());
        assertEquals("bearish", line.path("primed_direction").asText());
        assertEquals(66.67, line.path("funding_drop_pct").asDouble(), 1e-9);
        assertEquals(62000.0, line.path("price").asDouble(), 1e-9);
    }

    @Test
    public void appendsAcrossEpisodes() throws IOException
    {
        CompressionRecorder recorder = new CompressionRecorder(dir_);
        recorder.onEvent(warning("11:40", "bearish", 50.0, 62900.0));
        recorder.onEvent(warning("18:30", "bullish", 45.0, 58500.0));

        List<String> lines = Files.readAllLines(dir_.resolve("20260624").resolve("compression_20260624.jsonl"));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"primed_direction\":\"bearish\""));
        assertTrue(lines.get(1).contains("\"primed_direction\":\"bullish\""));
    }

    private static CompressionWarning warning(String key, String primed, double dropPct, double price)
    {
        Instant firedAt = Instant.parse("2026-06-24T" + key + ":00Z");
        return new CompressionWarning("20260624", key, primed, dropPct, price, firedAt);
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
